package sibarum.kronometer;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A fixed domain's steps, run back to back on a thread of their own, with a time of their own: counted by the steps
 * that finish, not kept by the timeline. A world whose steps get slow slows down, while everything on the timeline,
 * menus and the interface's own animation, keeps the wall's time.
 *
 * <pre>{@code
 * Rate physics = kron.tempo().child("playback", speed).fixed("physics", hz(60));
 * Dilated world = physics.dilated(2);
 * world.onDue(() -> worker.wake());          // on the timeline: a step has come due
 *
 * // on the worker's thread, as often as it is woken:
 * while (world.take()) {
 *     step(world.dt());
 *     world.finished(System.nanoTime());
 * }
 *
 * // on the render thread:
 * draw(newest, world.phase(System.nanoTime()));
 * }</pre>
 *
 * <h2>Why not a settlement</h2>
 *
 * {@link Settlement#STRETCH} lets the timeline's debt grow, so logical time runs slow: but there is one timeline,
 * and slip is its property, not a domain's ({@link Rate}). Stretching it would slow every animation in the
 * application along with the world. This leaves the timeline alone. The domain still says when a step is due, on its
 * grid and in its tempo, so a world never runs ahead of the wall; what a step that is due but not yet run becomes is
 * this class's business, and its answer is to forgive it.
 *
 * <h2>Game time is counted</h2>
 *
 * {@link #time()} is the steps that have finished, times the domain's period. A step owed by more than
 * {@code mostBehind} is never run: it is {@linkplain #forgiven() forgiven}, and game time simply falls behind the
 * wall's. That is dilation, and {@link #dilation()} reads it: steps finished over steps due, 1 at full speed and
 * less as the world slows. Nothing is ever repaid in a burst, which would be a stall followed by the world
 * hurrying.
 *
 * <h2>The picture blends by a prediction</h2>
 *
 * The thread that draws shows the newest finished step, blended from the one before it by {@link #phase}: the wall
 * time since that step finished, over the predicted time to the next finish, held at 1. The prediction is an
 * average of the intervals between finishes, corrected by each one. At full speed that interval is the domain's
 * period, because a step waits to come due; slowed, it is the step's own cost. Either way the picture moves at the
 * pace the steps really land at, and holds on the newest rather than run past it when one is late.
 *
 * <h2>Threads</h2>
 *
 * The domain's handler runs on the timeline. {@link #take} and {@link #finished} are the stepping thread's, one
 * thread. Everything else may be read from any thread.
 */
public final class Dilated implements AutoCloseable {

    /** How much of each new interval the prediction takes in. */
    static final double GAIN = 0.25;

    /** Steps due a {@link #dilation} reading is taken over: few enough to follow a change, enough to be steady. */
    static final int READING_STEPS = 60;

    private final Rate domain;
    private final int mostBehind;
    private final Consumer<Step> recorder = this::record;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final AtomicLong due = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong forgiven = new AtomicLong();

    private volatile boolean held;
    private volatile boolean closed;
    private volatile Moment releasedAt;

    // The stepping thread's: steps taken to run, counting those forgiven.
    private long taken;

    // Guarded by this: the prediction, and the reading's last snapshot.
    private long lastFinish;
    private boolean skipInterval;
    private double predicted;
    private long readDue;
    private long readFinished;
    private double reading = 1;

    Dilated(Rate domain, int mostBehind) {
        if (domain.kind() != Rate.Kind.FIXED) {
            throw new IllegalArgumentException("a dilated domain is a fixed one, whose grid says when a step is due; "
                    + domain.name() + " is " + domain.kind());
        }
        if (mostBehind < 1) {
            throw new IllegalArgumentException("at least one step may be owed, or none is ever run; got " + mostBehind);
        }
        this.domain = domain;
        this.mostBehind = mostBehind;
        domain.each(recorder);
    }

    /** On the timeline: a step is due, unless it is from before a release. */
    private void record(Step step) {
        Moment released = releasedAt;
        if (released != null && step.at().isBefore(released)) {
            return;
        }
        due.incrementAndGet();
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /**
     * Run {@code listener} on the timeline each time a step comes due: what wakes the stepping thread. It must be
     * quick and must not block, since the timeline waits for it.
     */
    public void onDue(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * The stepping thread: whether a step is due, and if so, that it is being run. Steps owed past
     * {@code mostBehind} are forgiven first, so a slow world slows rather than hurry to catch up.
     */
    public boolean take() {
        long now = due.get();
        if (taken >= now) {
            return false;
        }
        if (now - taken > mostBehind) {
            forgiven.addAndGet(now - taken - mostBehind);
            taken = now - mostBehind;
        }
        taken++;
        return true;
    }

    /**
     * The stepping thread: the step taken last has finished, at {@code nanos} on {@link System#nanoTime}'s clock.
     * Game time moves by a step, and the prediction of the next interval takes in this one.
     */
    public void finished(long nanos) {
        finished.incrementAndGet();
        synchronized (this) {
            if (lastFinish != 0 && !skipInterval) {
                double interval = nanos - lastFinish;
                predicted = predicted == 0 ? interval : predicted + GAIN * (interval - predicted);
            }
            skipInterval = false;
            lastFinish = nanos;
        }
    }

    /**
     * The stepping thread: every step owed so far is forgiven, as after the world was replaced and the old one's steps
     * mean nothing.
     */
    public void discard() {
        long now = due.get();
        if (now > taken) {
            forgiven.addAndGet(now - taken);
            taken = now;
        }
        synchronized (this) {
            skipInterval = true;
        }
    }

    /**
     * How far {@code nanos} is past the newest finished step, towards the next, from 0 to 1: the wall time since it
     * finished over the predicted interval, held at 1 rather than run past it. 1 before two steps have finished,
     * so a picture blending towards the newest shows it.
     */
    public synchronized float phase(long nanos) {
        if (lastFinish == 0 || predicted <= 0) {
            return 1f;
        }
        return (float) Math.clamp((nanos - lastFinish) / predicted, 0.0, 1.0);
    }

    /** The predicted interval between finished steps, or zero before there have been two. */
    public synchronized Dur predicted() {
        return Dur.ns((long) predicted);
    }

    /** Game time: the steps that have finished, times the domain's period. */
    public Dur time() {
        return domain.period().times(finished.get());
    }

    /** The domain's step, in game time. */
    public Dur dt() {
        return domain.period();
    }

    /**
     * Steps finished over steps due, over the last {@link #READING_STEPS} or more to come due: 1 at full speed, less as
     * the world slows. Taken afresh only once that many have come due since the last time, and what it was then until
     * they have, so a reader asking every frame sees a steady number. A reading, for whoever shows it; meant for one
     * reader.
     */
    public synchronized double dilation() {
        long d = due.get();
        long f = finished.get();
        if (d - readDue >= READING_STEPS) {
            reading = Math.min(1.0, (f - readFinished) / (double) (d - readDue));
            readDue = d;
            readFinished = f;
        }
        return reading;
    }

    /** Steps that have come due. */
    public long due() {
        return due.get();
    }

    /** Steps that have finished. */
    public long finished() {
        return finished.get();
    }

    /** Steps that came due and were never run, because the world was behind or was replaced. */
    public long forgiven() {
        return forgiven.get();
    }

    /**
     * Stops the world, or starts it again. Held, nothing comes due and the domain parks if it has nothing else on it.
     * Released, nothing due before the release is run, and the interval across the pause is not taken into the
     * prediction. On the timeline, or the host's thread between ticks, as {@link Rate#each} is.
     */
    public void hold(boolean hold) {
        if (hold == held || closed) {
            return;
        }
        held = hold;
        if (hold) {
            domain.remove(recorder);
        } else {
            releasedAt = domain.kron().now();
            synchronized (this) {
                skipInterval = true;
            }
            domain.each(recorder);
        }
    }

    /** Whether the world is stopped; see {@link #hold}. */
    public boolean held() {
        return held;
    }

    /** The domain whose steps these are. */
    public Rate domain() {
        return domain;
    }

    /** Stops counting: the handler comes off the domain, which parks if it has no other. */
    @Override
    public void close() {
        closed = true;
        domain.remove(recorder);
    }
}
