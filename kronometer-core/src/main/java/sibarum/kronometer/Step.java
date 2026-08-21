package sibarum.kronometer;

/**
 * One step of a {@link Rate} domain.
 *
 * @param index   which step this is, counting from the domain's origin
 * @param at      the grid moment this step represents. For a fixed domain being replayed after a
 *                jump this is in the <em>past</em> relative to {@link Time#now()} — the step stands
 *                for a moment that has already gone by, which is exactly what catching up means
 * @param dt      the logical time this step accounts for. Constant for a fixed domain — the only way
 *                integrated physics and integrated smoothing are reproducible — and variable for a
 *                dynamic one
 * @param skipped how many steps were dropped immediately before this one because the domain was
 *                further behind than its {@code maxCatchUp} allowed. Non-zero is the honest way to
 *                learn that a simulation lost steps, rather than silently losing them
 */
public record Step(long index, Moment at, Dur dt, long skipped) {

    @Override
    public String toString() {
        return "step " + index + " at " + at + " (dt " + dt
                + (skipped > 0 ? ", skipped " + skipped : "") + ")";
    }
}
