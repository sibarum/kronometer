package sibarum.kronometer;

import java.util.Objects;

/**
 * One stretch of a {@link Tempo}'s speed relative to its parent, from the moment it began until another
 * segment replaces it.
 *
 * <p>A tempo's speed is a sequence of these, and only the current one is kept: a segment that has ended
 * has already been folded into the tempo's origin by the rebase that ended it, so nothing earlier is ever
 * converted through again. That rebase is what keeps local time continuous across every change of speed.
 *
 * <p><b>The knot rule.</b> Every segment ends on an exact {@link Ratio}. A glide may be inexact in its
 * interior, but the speed it settles on is not, so the arithmetic that follows it is exact again. Today
 * the only segment is {@link Hold}, which is exact throughout; the glides of the animated-tempo plan
 * (docs/animated-tempo.md) are the reason this is a sealed type rather than a field.
 */
sealed interface Segment permits Segment.Hold {

    /** The exact speed this segment settles on, and the one the next segment starts from. */
    Ratio endSpeed();

    /**
     * Whether local time is an affine function of parent time across this segment. While every segment
     * from here to the root is affine, a tempo converts through one flattened {@link Ratio}, exactly.
     */
    boolean isAffine();

    /** A constant speed: the whole of what a {@code Tempo} could do before segments existed. */
    record Hold(Ratio speed) implements Segment {

        public Hold {
            Objects.requireNonNull(speed, "speed");
        }

        @Override
        public Ratio endSpeed() {
            return speed;
        }

        @Override
        public boolean isAffine() {
            return true;
        }

        @Override
        public String toString() {
            return "hold(" + speed + ")";
        }
    }
}
