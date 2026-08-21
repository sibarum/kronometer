package sibarum.kronometer.bench;

import sibarum.kronometer.Curve;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Predict;
import sibarum.kronometer.Prediction;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Signal;
import sibarum.kronometer.Cell;

import java.util.concurrent.atomic.AtomicLong;

/**
 * M5: does evaluating ahead actually buy anything, and where?
 *
 * <p>Two different claims, easy to conflate, measured separately.
 *
 * <ol>
 *   <li><b>Throughput, on a refill.</b> Filling a window is embarrassingly parallel because the work is
 *       pure, so a wide window should cost roughly its serial cost divided by the cores. The audio-shaped
 *       case is where this matters: 100 ms of lookahead at 48 kHz is 4 800 samples per refill, and doing
 *       those one at a time on the timeline is exactly the stall the design exists to avoid.</li>
 *   <li><b>Jitter, in steady state.</b> Here the honest answer is that prediction buys <em>nothing</em>
 *       in total CPU — a sliding window needs one new sample per step either way. What it buys is
 *       <em>when</em> the work happens: the read at {@code now} becomes an index lookup, and the
 *       computation has already been paid for a window earlier. That is a latency claim, not a
 *       throughput one, and reporting it as a speedup would be dishonest.</li>
 * </ol>
 */
public final class PredictBench {

    /** Something with real arithmetic in it, so parallelism has something to divide. */
    private static double expensive(double seed) {
        double acc = seed;
        for (int i = 0; i < 200; i++) {
            acc = Math.fma(Math.sin(acc), 0.5, Math.cos(acc * 0.25));
        }
        return acc;
    }

    private record Result(long evaluations, double nanosPerStep, long filled, long hits) { }

    public static void main(String[] args) {
        System.out.printf("Kronometer M5 — precomputation%n");
        System.out.printf("  runtime      %s%n", "runtime".equals(
                System.getProperty("org.graalvm.nativeimage.imagecode"))
                ? "GraalVM native-image" : System.getProperty("java.vm.name"));
        System.out.printf("  cores        %d%n", Runtime.getRuntime().availableProcessors());

        System.out.printf("%n1. Wide window — 100 ms of lookahead at 48 kHz%n");
        System.out.printf("  %-26s %12s %14s %12s%n", "", "ns/step", "evaluations", "buffered reads");
        run("lazy (NEVER)", Predict.NEVER, Dur.hz(48_000), Dur.ms(100), 48_000);
        run("predicted (EAGER)", Predict.EAGER, Dur.hz(48_000), Dur.ms(100), 48_000);

        System.out.printf("%n2. Narrow window — 60 Hz, two frames of lookahead%n");
        System.out.printf("  %-26s %12s %14s %12s%n", "", "ns/step", "evaluations", "buffered reads");
        run("lazy (NEVER)", Predict.NEVER, Dur.hz(60), Dur.ms(33), 20_000);
        run("predicted (EAGER)", Predict.EAGER, Dur.hz(60), Dur.ms(33), 20_000);

        System.out.printf("%nReading it: (1) should show a speedup close to the core count, because a%n"
                + "wide window is filled in parallel bursts, and the read at now becomes an index%n"
                + "lookup. (2) should show little or nothing: a two-frame window tops up one sample%n"
                + "per step, which is below the batch size worth dispatching to a pool.%n");
    }

    /**
     * Warm properly and take the best of several runs.
     *
     * <p>The first version of this ran 480 steps with one warm pass, and reported prediction as 70 %
     * *slower* — which was entirely JIT warmup and setup, not prediction. A separate breakdown put the
     * real per-step kernel cost at 520–822 ns, against the 18 000 ns this harness was reporting. The
     * moral is the one M0 already taught: a distribution can look authoritative and still be measuring
     * the wrong thing.
     */
    private static void run(String label, Predict policy, Dur period, Dur lookahead, int steps) {
        for (int warm = 0; warm < 3; warm++) {
            measure(policy, period, lookahead, steps);
        }
        Result best = null;
        for (int i = 0; i < 5; i++) {
            Result result = measure(policy, period, lookahead, steps);
            if (best == null || result.nanosPerStep() < best.nanosPerStep()) {
                best = result;
            }
        }
        System.out.printf("  %-26s %12.0f %,14d %,12d%n",
                label, best.nanosPerStep(), best.evaluations(), best.hits());
    }

    private static Result measure(Predict policy, Dur period, Dur lookahead, int steps) {
        AtomicLong evaluations = new AtomicLong();
        Prediction<Double> prediction;
        long elapsed;

        try (Kron kron = Kron.virtual()) {
            Cell<Double> input = kron.cell("input", 0.0);
            Signal<Double> heavy = kron.computed("heavy", () -> {
                evaluations.incrementAndGet();
                return expensive(input.get());
            });
            Rate domain = kron.fixed("domain", period).lookahead(lookahead);
            prediction = domain.predict(heavy, policy);

            kron.spork(() -> input.drive(Curve.ramp(0.0, 1.0, period.times(steps))));
            kron.effect(domain, heavy::get);

            long started = System.nanoTime();
            kron.runUntil(Moment.ORIGIN.plus(period.times(steps)));
            elapsed = System.nanoTime() - started;
        }

        return new Result(evaluations.get(), elapsed / (double) steps,
                prediction.filled(), prediction.hits());
    }

    private PredictBench() {
    }
}
