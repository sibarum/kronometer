package sibarum.kronometer;

/**
 * Blends two values of a type. Pure, which is what makes anything built from it predictable.
 *
 * <p>Core carries the interface; the library of implementations — quaternion {@code SLERP},
 * shortest-arc {@code ANGLE}, perceptual colour spaces — belongs to {@code kronometer-anim} (M6),
 * because those are choices about a domain rather than about time.
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
