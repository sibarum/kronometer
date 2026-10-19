package sibarum.kronometer;

/**
 * The shape of a {@link Tempo#glide}: how its speed moves from where it is to where it is going.
 *
 * <p>Both shapes end exactly on the target {@link Ratio}, keep the speed continuous where they start, and
 * leave the tempo converting through one exact ratio once they finish. They differ only in between.
 */
public enum Glide {

    /**
     * The speed changes by the same amount every nanosecond. Its integral is exact rational arithmetic,
     * so local time during the glide is exact to the nanosecond. The right shape for motion: a slow-motion
     * ramp that should feel like a steady deceleration.
     */
    LINEAR,

    /**
     * The speed changes by the same <em>ratio</em> every nanosecond, so halfway from 1 to 4 it is 2. The
     * right shape for anything heard, since tempo and pitch are perceived on a log scale. Its integral
     * involves a logarithm, so it is computed in {@code double} during the glide, and exact again from the
     * moment it ends.
     */
    EXPONENTIAL
}
