package sibarum.kronometer;

/**
 * How much slip may be repaid in one step.
 *
 * <p>Repaying as fast as possible is {@link Settlement#CATCH_UP}, and for continuous media that is
 * its own artifact — the recovery is as audible as the stall was. So repayment is bounded by
 * <em>perception</em> rather than by available CPU, and the debt drains over hundreds of steps
 * instead of one.
 *
 * <p>Useful bounds: {@code rate(0.005)} is a 0.5 % time-scale change, inaudible; {@code
 * atMost(ms(2))} shaves a couple of milliseconds off a frame, invisible.
 */
@FunctionalInterface
public interface Repayment {

    /**
     * The most slip that may be repaid at a step spanning {@code stepNanos} of logical time.
     *
     * <p>The kernel additionally clamps this to the debt outstanding and to the wall-clock headroom
     * actually available, so an implementation may return more than it can have.
     */
    long allowanceNanos(long stepNanos);

    /** A fixed ceiling per step. */
    static Repayment atMost(Dur cap) {
        long nanos = cap.nanos();
        if (nanos < 0) {
            throw new IllegalArgumentException("repayment cap cannot be negative: " + cap);
        }
        return step -> nanos;
    }

    /**
     * A proportion of each step — a time-scale change. {@code rate(0.005)} runs logical time 0.5 %
     * fast until the debt clears.
     */
    static Repayment rate(double proportion) {
        if (!(proportion >= 0) || proportion > 1) {
            throw new IllegalArgumentException("proportion must be in [0, 1]: " + proportion);
        }
        return step -> (long) (step * proportion);
    }

    /** Repay everything available, immediately. What {@link Settlement#CATCH_UP} uses. */
    static Repayment unbounded() {
        return step -> Long.MAX_VALUE;
    }

    /** Never repay. What {@link Settlement#STRETCH} uses. */
    static Repayment none() {
        return step -> 0;
    }
}
