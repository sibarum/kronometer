package sibarum.kronometer;

/**
 * A clock stepped from outside — once per presented frame, by a render loop calling
 * {@link Kron#tick}.
 *
 * <p>Logical time follows the tick stream, so a tween samples exactly once per frame the user
 * actually sees, with no aliasing between the animation rate and the display rate. Pacing belongs to
 * whoever is ticking, so this clock carries no slip of its own: if the app is slow, ticks arrive
 * later and logical time follows.
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
 */
public final class Driven implements Clock {

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

    private final Mode mode;

    Driven(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public long awaitUntil(long targetNanos) {
        return targetNanos;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    public String toString() {
        return "Clock.driven(" + mode + ")";
    }
}
