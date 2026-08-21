package sibarum.kronometer;

/**
 * How aggressively a signal's future should be computed ahead of {@code now}.
 *
 * <p>Prediction is never a correctness question — it cannot be, because a wrong prediction is retracted
 * before any effect acts on it. It is purely an economic one: precomputing a hundred milliseconds and
 * then invalidating at five throws away ninety-five milliseconds of work.
 */
public enum Predict {

    /**
     * Fill the whole window from {@code now} to {@code min(now + lookahead, horizon)}.
     *
     * <p>The default, and right for anything with a long horizon: a curve, an oscillator, anything
     * derived only from time. The kernel demotes it to {@link #LAZY} on its own if the measured waste
     * says eager work is a net loss.
     */
    EAGER,

    /**
     * Fill one step ahead, no more. For values that are technically predictable but change often
     * enough that filling a window mostly produces landfill.
     */
    LAZY,

    /** Never precompute. Evaluate at {@code now}, every time. */
    NEVER
}
