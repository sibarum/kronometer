package sibarum.kronometer;

/**
 * Blends two values of a type. Pure, which is what makes anything built from it predictable.
 *
 * <p>Core carries the interface; the library of implementations — quaternion {@code SLERP},
 * shortest-arc {@code ANGLE}, perceptual colour spaces — belongs to {@code kronometer-anim} (M6),
 * because those are choices about a domain rather than about time.
 *
 * <h2>If you are writing an {@code Interp} for a colour</h2>
 *
 * Kronometer deliberately ships no colour interpolation: a colour space needs your colour type, and
 * choosing one is a decision you own. The <em>rule</em> that comes with the decision is not, and it
 * belongs here, where someone writing {@code Interp<MyColour>} will have it open:
 *
 * <p><b>Interpolate perceptually, never in raw sRGB.</b> Blending sRGB components linearly walks
 * through a desaturated grey somewhere in the middle of most pairs, and the eye reads that middle as a
 * different hue rather than as a transition between two. Convert to a perceptually uniform space —
 * Oklab is the usual answer — blend there, and convert back. It is about twenty lines, and it is the
 * difference between a fade that looks deliberate and one that looks broken halfway.
 *
 * <p>The same shape of question applies to anything whose numeric representation is not perceptually
 * linear: decibels rather than amplitude for a gain, shortest arc rather than raw components for an
 * orientation.
 */
@FunctionalInterface
public interface Interp<T> {

    /**
     * @param alpha 0 returns {@code from}, 1 returns {@code to}. Callers clamp; implementations need
     *              not handle values outside the range meaningfully.
     */
    T between(T from, T to, float alpha);

    Interp<Double> DOUBLE = (from, to, alpha) -> from + (to - from) * alpha;

    Interp<Float> FLOAT = (from, to, alpha) -> from + (to - from) * alpha;

    /** Hold the earlier value until the very end — for things that cannot be meaningfully blended. */
    static <T> Interp<T> step() {
        return (from, to, alpha) -> alpha < 1f ? from : to;
    }
}
