package sibarum.kronometer;

/**
 * A report that the wall and logical time moved apart, or back together.
 *
 * <p>Overrun is observable, never a silent stutter — a stall you cannot see is a stall you cannot
 * fix. The useful signal is not any single event but the <b>trend in {@link #slipAfter()}</b>: a slip
 * that drains is a hiccup, a slip that plateaus is a capacity problem, and a slip that climbs is a
 * system heading for a {@link Kind#RESYNC}.
 *
 * @param kind       what happened
 * @param at         the logical moment it happened at
 * @param amount     how much time was involved — the shortfall, the jump, or the repayment
 * @param slipAfter  the outstanding debt once this event was applied
 * @param settlement the policy in force
 */
public record Overrun(Kind kind, Moment at, Dur amount, Dur slipAfter, Settlement settlement) {

    public enum Kind {
        /** A deadline was missed. The debt grew by {@link Overrun#amount()}. */
        LATE,
        /** Headroom appeared and some of the debt was paid back. */
        REPAID,
        /** Logical time jumped forward, forgiving the gap. {@link Settlement#SKIP}. */
        SKIPPED,
        /**
         * The debt passed {@code maxSlip} and was written off in one deliberate discontinuity.
         * The least-bad option, taken once and reported, rather than unbounded creeping lag.
         */
        RESYNC
    }

    @Override
    public String toString() {
        return kind + " " + amount + " at " + at + " (slip now " + slipAfter + ", " + settlement + ")";
    }
}
