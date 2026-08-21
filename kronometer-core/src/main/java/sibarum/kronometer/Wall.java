package sibarum.kronometer;

import java.util.concurrent.locks.LockSupport;

/**
 * The wall clock, as the kernel sees it: a reading, and a way to wait for one.
 *
 * <p>Injectable, and that is the whole point. The slip model (§5.1) is pure arithmetic — debt
 * accrual, bounded repayment, resync thresholds — so with a scripted {@code Wall} every one of those
 * becomes a deterministic unit test rather than a sleep and a hope. A test's {@code Wall} answers
 * {@link #parkUntil} by simply jumping its own reading to the deadline, which is what "waiting" means
 * when nobody is really waiting.
 */
public interface Wall {

    /** A monotonic reading in nanoseconds. Only differences are meaningful. */
    long nanos();

    /** Block until {@link #nanos()} would return at least {@code deadlineNanos}. */
    void parkUntil(long deadlineNanos) throws InterruptedException;

    /** The real one, with the default spin tail. */
    static Wall system() {
        return SystemWall.DEFAULT;
    }

    /**
     * The real one, with an explicit spin tail: how long before a deadline to stop parking and start
     * spinning.
     *
     * <p>This is the knob that decides realtime accuracy, and it is a straight trade of CPU for
     * precision. {@link LockSupport#parkNanos} is only as precise as the platform timer — on Windows
     * it routinely overshoots by around a millisecond — so a tail shorter than that overshoot is never
     * reached, and arrival is late by the difference. A tail longer than it burns a core for the
     * duration on every wait.
     */
    static Wall system(Dur spinTail) {
        if (spinTail.isNegative()) {
            throw new IllegalArgumentException("spin tail cannot be negative: " + spinTail);
        }
        return new SystemWall(spinTail.nanos());
    }
}

/**
 * {@link System#nanoTime()} with a spin tail.
 *
 * <p>{@link LockSupport#parkNanos} is only as precise as the platform timer — on Windows that is
 * around a millisecond, which is most of a frame — so the last stretch is spun rather than parked.
 * The tail costs a fraction of a core and buys sub-microsecond arrival; on a virtual thread it holds
 * the carrier, which is harmless here because the kernel is the only thing that should be running
 * while it waits for its own deadline.
 */
final class SystemWall implements Wall {

    /**
     * 1.5 ms, chosen from measurement rather than taste.
     *
     * <p>M2 measured per-frame jitter of 400–600 ns… no: 400–600 <em>micro</em>seconds, with a 500 µs
     * tail — because Windows' {@code parkNanos} overshoots by about a millisecond, so the tail was
     * never reached and every frame arrived late by the difference. 1.5 ms covers the observed
     * overshoot and costs roughly 9 % of one core at 60 Hz, which is the price of arriving on time.
     * Applications that would rather have the core than the precision can ask for a shorter one.
     */
    private static final long DEFAULT_SPIN_TAIL_NANOS = 1_500_000L;

    static final SystemWall DEFAULT = new SystemWall(DEFAULT_SPIN_TAIL_NANOS);

    private final long spinTailNanos;

    SystemWall(long spinTailNanos) {
        this.spinTailNanos = spinTailNanos;
    }

    @Override
    public long nanos() {
        return System.nanoTime();
    }

    @Override
    public void parkUntil(long deadlineNanos) throws InterruptedException {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (remaining > spinTailNanos) {
                LockSupport.parkNanos(remaining - spinTailNanos);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public String toString() {
        return "Wall.system(spinTail=" + new Dur(spinTailNanos) + ")";
    }
}
