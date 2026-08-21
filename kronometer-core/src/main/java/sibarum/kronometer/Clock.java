package sibarum.kronometer;

import java.util.function.Consumer;

/**
 * How logical time relates to wall-clock time. The kernel does not know what a second is — it asks a
 * {@code Clock}, and that one seam is what lets the same shred code run live and under test.
 */
public interface Clock {

    /**
     * Block until logical {@code targetNanos} may be entered, and return the logical moment
     * <em>actually</em> entered.
     *
     * <p>Normally that is {@code targetNanos}. It is later when logical time had to jump — a
     * {@link Settlement#SKIP} forgiving a gap, or a hard resync writing off a debt — which is how a
     * clock tells the kernel that the schedule moved rather than merely that it waited. It is never
     * earlier.
     */
    long awaitUntil(long targetNanos) throws InterruptedException;

    /** Whether logical time jumps to the next scheduled moment rather than being paced. */
    boolean isVirtual();

    /** The outstanding debt between logical and wall-clock time. Always zero for unpaced clocks. */
    default Dur slip() {
        return Dur.ZERO;
    }

    /**
     * Wall-clock time remaining before {@code logicalNanos} falls due — the budget available to
     * whatever is running now. {@link Dur#FOREVER} for clocks that are not paced against a wall.
     */
    default Dur slackUntil(long logicalNanos) {
        return Dur.FOREVER;
    }

    /** Observe overruns, repayments, skips and resyncs. */
    default void onOverrun(Consumer<Overrun> listener) {
        // Unpaced clocks cannot overrun.
    }

    /**
     * Logical time jumps straight to the next scheduled moment: a ten-minute scenario runs in
     * microseconds, and it runs identically every time.
     *
     * <p>Stricter than the realtime clock on purpose — anything genuinely nondeterministic has to be
     * modelled rather than observed, so {@link Kron#post(Runnable)} without a moment is rejected.
     */
    static Clock virtual() {
        return VirtualClock.INSTANCE;
    }

    /** Paced against the real wall clock, with the default settlement policy. */
    static Realtime realtime() {
        return new Realtime(Wall.system());
    }

    /** Paced against a supplied wall — the seam that makes the slip model unit-testable. */
    static Realtime realtime(Wall wall) {
        return new Realtime(wall);
    }

    /** Stepped externally, once per presented frame. */
    static Driven driven() {
        return new Driven(Driven.Mode.INLINE);
    }

    static Driven driven(Driven.Mode mode) {
        return new Driven(mode);
    }
}

final class VirtualClock implements Clock {

    static final VirtualClock INSTANCE = new VirtualClock();

    private VirtualClock() {
    }

    @Override
    public long awaitUntil(long targetNanos) {
        return targetNanos;
    }

    @Override
    public boolean isVirtual() {
        return true;
    }

    @Override
    public String toString() {
        return "Clock.virtual()";
    }
}
