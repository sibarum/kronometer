package sibarum.kronometer;

/**
 * How a slip debt is settled.
 *
 * <p>When the machine cannot keep up there is one honest description of what happens:
 *
 * <pre>    wall(m) = m + slip</pre>
 *
 * <p>Slip is a <b>debt</b>. An underrun forces it up — the work did not finish in time, so the
 * schedule moves later, and no alternative preserves the output. It comes down only when the machine
 * gets ahead again, and nothing guarantees that it will. So the question is never how to avoid slip;
 * it is how the debt is settled.
 *
 * <p><b>Three of these four are the same mechanism.</b> {@link #CATCH_UP} and {@link #STRETCH} are
 * {@link #SLIP} with the repayment bound turned to infinity and to zero respectively — a
 * simplification that only became obvious once the arithmetic was written down. Only {@link #SKIP} is
 * structurally different, because it does not repay the debt at all: it forgives it, and moves
 * logical time instead.
 *
 * <blockquote>{@code SKIP} trades continuity for latency. {@code SLIP} trades latency for
 * continuity.</blockquote>
 *
 * <p>Choose by what the work drives. Audio must not skip — a dropped block is a click. A
 * pointer-following animation must not slip, because slip on an input-driven signal <em>is</em> input
 * lag, and lag is the thing users feel.
 */
public enum Settlement {

    /**
     * Hold the debt and repay it gradually, within a bound set by perception rather than by CPU.
     * The default, and the right answer for anything continuous.
     */
    SLIP,

    /**
     * Pay the debt — run flat out until wall and logical time meet. Logical time stays complete, at
     * the cost of a load spike immediately after a stall, which is a good way to cause the next one.
     * Mechanically {@link #SLIP} with unbounded repayment.
     */
    CATCH_UP,

    /**
     * Forgive the debt: jump logical time forward to the wall and let the moments in the gap collapse
     * onto the new {@code now}. Slip is unchanged; what moves is time.
     *
     * <p><b>Only meaningful for origin-relative scheduling.</b> {@link Metro} and {@link Time#sync}
     * compute their next wake from a fixed origin, so a jump genuinely skips work. {@link
     * Time#advance} computes it from the shred's own logical {@code now}, so a skipped shred simply
     * resumes and finds itself behind again — which is {@link #CATCH_UP} by another name. Rate
     * domains (M3) make this rigorous; until then it is a real caveat, and an argument for writing
     * periodic work with {@code Metro} rather than a loop of {@code advance}.
     */
    SKIP,

    /**
     * Ignore the debt: it grows freely and logical time simply runs slow. For debugging, breakpoints
     * and stepping, where any relationship to the wall is a nuisance. Mechanically {@link #SLIP} with
     * repayment disabled.
     */
    STRETCH
}
