package sibarum.kronometer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A clock stepped from outside — once per presented frame, by a render loop calling
 * {@link Kron#tick}.
 *
 * <p>Logical time follows the tick stream, so a tween samples exactly once per frame the user
 * actually sees, with no aliasing between the animation rate and the display rate. Pacing belongs to
 * whoever is ticking: if the app is slow, ticks arrive later and logical time follows.
 *
 * <h2>Why {@code INLINE} does not literally run on the caller's thread</h2>
 *
 * The design said it would. Measurement says it must not. A render thread is a platform thread, and
 * M0 found that a platform kernel thread makes <em>every shred handoff</em> ten times more expensive
 * — 1 493 ns becomes 15 497 ns — because each one then crosses between the OS scheduler and the
 * virtual-thread scheduler. A hundred shreds in a frame would cost 1.5 ms of pure scheduling.
 *
 * <p>So {@code INLINE} keeps the guarantee that actually matters — <b>{@code tick()} returns with the
 * batch complete</b>, so effects have run before the frame is submitted — and delivers it by handing
 * the batch to a persistent virtual kernel thread and blocking until it finishes. That costs one
 * thread round-trip per frame rather than one per handoff: about 0.08 % of a 60 Hz frame instead of
 * 9 %.
 *
 * <h2>A tick is bounded, and this clock does carry slip after all</h2>
 *
 * The design said it carried none, on the grounds that the host paces it. That holds while the host
 * is <em>there</em>. A host at a breakpoint, behind a modal drag, or on a laptop that just came back
 * from sleep is not pacing anything, and the tick that follows asks for the whole absence at once.
 *
 * <p>Unbounded, that is not a stutter but a hang: one tick of a twenty-second gap on a 50 Hz domain
 * is a thousand steps, and a first tick given a raw {@link System#nanoTime()} reading — the obvious
 * wrong guess, since a {@code Moment} counts from the kernel's own origin — is seventeen million.
 * Measured, that is about twenty seconds of frozen render thread on the first frame of an
 * integration, with no error and nothing in the log.
 *
 * <p>So {@link #maxAdvance(Dur)} bounds how far one tick may carry logical time, defaulting to one
 * second, and the excess is <b>forgiven</b>: logical time falls permanently behind the tick stream by
 * that much, which is what {@link #slip()} now reports, and each forgiveness is announced through
 * {@link #onOverrun} as a {@link Overrun.Kind#SKIPPED}. Forgiving is the only settlement that makes
 * sense here — nobody wants twenty seconds of simulation replayed into a window that was not being
 * looked at.
 *
 * <p>It is the same clamp {@link Rate#maxCatchUp} is for a domain, needed at the kernel because a
 * domain never notices: it walks the gap one grid line at a time, always exactly on schedule, and so
 * is never <em>behind</em> for {@code maxCatchUp} to catch.
 */
public final class Driven implements Clock {

    /** Generous enough that no plausible frame or scripted tick trips it, tight enough to be a bound. */
    private static final Dur DEFAULT_MAX_ADVANCE = Dur.ms(1_000);

    /** Who runs the batch, and whether {@link Kron#tick} waits for it. */
    public enum Mode {
        /**
         * {@code tick()} returns once the batch is complete. Effects run in phase with the frame
         * about to be submitted — one pass, nothing drawn out of phase with what was computed. The
         * caller inherits the segment budget: a shred that will not yield now stalls the render loop,
         * which is exactly where you want to notice it.
         */
        INLINE,

        /**
         * {@code tick()} signals and returns immediately; the batch runs on the kernel thread. Shred
         * code never delays the render loop, at the cost of a frame of latency and the in-phase
         * guarantee. Ticks arriving while a batch is in flight coalesce into the newest deadline.
         */
        HANDOFF
    }

    private static final long UNSET = Long.MIN_VALUE;

    private final Mode mode;
    private final Wall wall;

    private Dur maxAdvance = DEFAULT_MAX_ADVANCE;
    private long originNanos = UNSET;
    private long forgivenNanos;
    private Consumer<Overrun> listener = overrun -> { };

    Driven(Mode mode) {
        this(mode, Wall.system());
    }

    Driven(Mode mode, Wall wall) {
        this.mode = mode;
        this.wall = Objects.requireNonNull(wall, "wall");
    }

    public Mode mode() {
        return mode;
    }

    /**
     * The most logical time one tick may carry. Default one second; {@link Dur#FOREVER} to opt out.
     *
     * <p>Opting out is a real choice for a headless scripted run, where the ticks are the script and a
     * long jump is deliberate. It is never the right choice for a render loop.
     */
    public Driven maxAdvance(Dur maxAdvance) {
        Objects.requireNonNull(maxAdvance, "maxAdvance");
        if (maxAdvance.nanos() <= 0) {
            throw new IllegalArgumentException("maxAdvance must be positive: " + maxAdvance);
        }
        this.maxAdvance = maxAdvance;
        return this;
    }

    public Dur maxAdvanceLimit() {
        return maxAdvance;
    }

    @Override
    public long awaitUntil(long targetNanos) {
        return targetNanos;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    /**
     * How far logical time has fallen behind the tick stream, through forgiven gaps.
     *
     * <p>Zero for an application that never stalls, and it never drains: a forgiven gap is written
     * off, not deferred. Read it to find out whether the run you are looking at skipped anything.
     */
    @Override
    public Dur slip() {
        return new Dur(forgivenNanos);
    }

    @Override
    public void onOverrun(Consumer<Overrun> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    // -------------------------------------------------------------- internals

    /** Elapsed since the first call, so a host need not keep an origin of its own. */
    long elapsedNanos() {
        long reading = wall.nanos();
        if (originNanos == UNSET) {
            originNanos = reading;
        }
        return reading - originNanos;
    }

    /**
     * The logical moment a tick of {@code tickNanos} lands on, given logical time is at {@code nowNanos}.
     *
     * <p>Where the forgiveness happens, and it accumulates rather than resetting: every later tick is
     * offset by the whole debt, so the tick stream and logical time stay a fixed distance apart until
     * the next stall widens it.
     */
    long logicalFor(long tickNanos, long nowNanos) {
        long target = tickNanos - forgivenNanos;
        long advance = target - nowNanos;
        if (maxAdvance.nanos() != Dur.FOREVER.nanos() && advance > maxAdvance.nanos()) {
            long forgiven = advance - maxAdvance.nanos();
            forgivenNanos += forgiven;
            target = nowNanos + maxAdvance.nanos();
            listener.accept(new Overrun(Overrun.Kind.SKIPPED, new Moment(target),
                    new Dur(forgiven), new Dur(forgivenNanos), Settlement.SKIP));
        }
        return target;
    }

    @Override
    public String toString() {
        return "Clock.driven(" + mode
                + (maxAdvance.nanos() == Dur.FOREVER.nanos() ? "" : ", maxAdvance " + maxAdvance)
                + (forgivenNanos == 0 ? "" : ", forgiven " + new Dur(forgivenNanos)) + ")";
    }
}
