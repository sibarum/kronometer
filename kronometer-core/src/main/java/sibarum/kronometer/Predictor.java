package sibarum.kronometer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fills prediction buffers by evaluating pure signals ahead of {@code now}, in parallel.
 *
 * <h2>Its own threads, never the kernel's carrier</h2>
 *
 * §3.1's sharp edge. The kernel runs on a virtual thread and an application may pin the whole carrier
 * pool to one thread, in which case scheduling pure evaluation onto it would not merely be slow — it
 * would deadlock against the serialization that makes the baton fast. Platform threads here settle that
 * by construction, and pure math wants real cores anyway.
 *
 * <h2>The fill is a burst, and the timeline is paused for it</h2>
 *
 * A deliberate narrowing for M5, and worth being explicit about. Filling <em>concurrently</em> with the
 * timeline would mean workers reading {@link Cell} state while a shred mutates it, which needs every
 * readable field safely published — a much larger correctness surface than this milestone should open.
 * So the kernel hands the pool a batch and blocks until it finishes.
 *
 * <p>That is still the win it was meant to be, and it is what real audio engines do: one parallel burst
 * renders a hundred milliseconds of future, and every read after that is an index lookup. What it costs
 * is that the burst's wall time is charged to the current segment, so it spends {@link Kron#slack()} and
 * can turn into slip — which is measurable with the instruments already built, rather than mysterious.
 */
final class Predictor {

    private final Kron kron;
    private ExecutorService pool;

    /** Waste above this demotes EAGER to LAZY… */
    private static final double DEMOTE_ABOVE = 0.5;
    /** …and below this restores it. The gap is the hysteresis. */
    private static final double RESTORE_BELOW = 0.1;
    private static final long MINIMUM_SAMPLES = 32;
    /** Below this many samples, dispatching to the pool costs more than the work. Measured, not guessed. */
    private static final int PARALLEL_THRESHOLD = 16;

    Predictor(Kron kron) {
        this.kron = kron;
    }

    /**
     * Bring {@code domain}'s prediction buffers up to date, as of the current moment.
     *
     * <p>Called from the domain's own step, on the timeline, so nothing is mutating while the pool runs.
     */
    void fill(Rate domain, List<Prediction<?>> predictions) {
        if (!predictions.isEmpty() && domain.kind() == Rate.Kind.FIXED) {
            fillFixed(domain, predictions);
        }
    }

    private void fillFixed(Rate domain, List<Prediction<?>> predictions) {
        Moment now = kron.now();
        List<Callable<Runnable>> work = new ArrayList<>();

        for (Prediction<?> prediction : predictions) {
            prediction.reconsider(DEMOTE_ABOVE, RESTORE_BELOW, MINIMUM_SAMPLES);
            collect(domain, prediction, now, work);
        }
        if (work.isEmpty()) {
            return;
        }

        // Evaluate in parallel, then apply the results serially back on this thread. Splitting it that
        // way keeps every buffer mutation on the timeline, so the buffers need no synchronization of
        // their own — the parallel part is purely the arithmetic, which is the part that is pure.
        //
        // But only above a threshold. Dispatching to the pool and waiting costs a platform-thread
        // round trip, which M0 measured at ~14 us on this platform, so handing it one sample is far
        // more expensive than just computing the sample. Measured: at 60 Hz with a two-frame window the
        // steady-state top-up is one sample per step, and routing that through the pool made prediction
        // three times *slower* than not predicting. Small batches stay on the timeline.
        List<Runnable> results = new ArrayList<>(work.size());
        if (work.size() < PARALLEL_THRESHOLD) {
            try {
                for (Callable<Runnable> task : work) {
                    results.add(task.call());
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "precomputation failed; a signal body is not pure or threw", e);
            }
        } else {
            try {
                for (Future<Runnable> future : pool().invokeAll(work)) {
                    results.add(future.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                throw new IllegalStateException(
                        "precomputation failed; a signal body is not pure or threw", e.getCause());
            }
        }
        results.forEach(Runnable::run);
    }

    private <T> void collect(
            Rate domain, Prediction<T> prediction, Moment now, List<Callable<Runnable>> work) {

        if (prediction.policy() == Predict.NEVER) {
            return;
        }
        Signal<T> signal = prediction.signal();
        Moment horizon = signal.horizon();
        if (!horizon.isAfter(now)) {
            return;                                  // volatile: nothing about its future is knowable
        }

        if (prediction.isComplete()) {
            return;                                  // a constant tail covers the rest of the window
        }
        long here = domain.gridIndexAtOrBefore(now);
        long from = Math.max(here + 1, prediction.highestFilled() + 1);
        long window = prediction.policy() == Predict.EAGER
                ? domain.gridIndexAtOrBefore(deadline(domain, now, horizon))
                : domain.gridIndexAfter(now);

        // Refill in bursts, not in dribbles. Topping the window up by one sample every step does
        // precisely the work lazy evaluation would have done, one sample at a time, on the timeline —
        // measured as exactly no improvement at all. Waiting until the buffer is half empty and then
        // refilling the whole window means most steps are a pure buffer read and the evaluation happens
        // in one parallel batch, which is the only shape in which "embarrassingly parallel" cashes out.
        // It is also what an audio engine does, for the same reason.
        if (prediction.policy() == Predict.EAGER) {
            long stocked = prediction.highestFilled() - here;
            long capacity = window - here;
            if (capacity > 1 && stocked * 2 > capacity) {
                return;
            }
        }
        Moment varyingUntil = signal.varyingUntil();

        for (long index = from; index <= window; index++) {
            if (prediction.has(index)) {
                continue;
            }
            Moment at = domain.gridLineAt(index);
            if (at.isAfter(horizon)) {
                break;
            }
            if (!at.isBefore(varyingUntil) && !varyingUntil.equals(Moment.FOREVER)) {
                // Past the point where it changes: one constant covers the whole tail.
                long tailIndex = index;
                work.add(() -> {
                    T value = evaluate(signal, at);
                    return () -> prediction.storeConstantFrom(tailIndex, value);
                });
                break;
            }
            long slot = index;
            work.add(() -> {
                T value = evaluate(signal, at);
                return () -> prediction.store(slot, value);
            });
        }
    }

    /** The far edge of the window: the lookahead, clipped by what is actually knowable. */
    private Moment deadline(Rate domain, Moment now, Moment horizon) {
        Dur lookahead = domain.lookahead();
        if (lookahead.isZero()) {
            lookahead = domain.period();            // at least one step, or prediction does nothing
        }
        Moment wanted = now.plus(lookahead);
        return horizon.equals(Moment.FOREVER) || wanted.isBefore(horizon) ? wanted : horizon;
    }

    private <T> T evaluate(Signal<T> signal, Moment at) {
        return kron.graph().evaluateAhead(at, () -> signal.at(at));
    }

    private ExecutorService pool() {
        if (pool == null) {
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            AtomicInteger counter = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread t = new Thread(runnable, "kron-predict-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            };
            pool = Executors.newFixedThreadPool(threads, factory);
        }
        return pool;
    }

    void close() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }
}
