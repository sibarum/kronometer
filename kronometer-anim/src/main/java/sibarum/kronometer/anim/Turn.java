package sibarum.kronometer.anim;

import sibarum.kronometer.Interp;
import sibarum.kronometer.Ratio;

/**
 * An angle measured in <b>turns</b>: one full revolution is 1, not 2π.
 *
 * <p>Turns are not a convenience. A turn wraps at 1, so reducing a phase is taking a fractional part —
 * an exact operation. Radians wrap at an irrational constant, so every wrap of a radian-based phase
 * accumulator rounds, and a long-running oscillator bleeds precision one revolution at a time. Anything
 * that has to stay in phase over hours belongs in turns.
 *
 * <p>It also makes the ratio connection direct. A {@link Ratio} read as a slope — rise over run — has an
 * angle of {@code arctan(p/q)}, and {@link #ofSlope} gives it in turns, exactly the parameterization a
 * nested ratio hierarchy wants.
 *
 * @param turns revolutions; may be any real, and is not automatically reduced
 */
public record Turn(double turns) implements Comparable<Turn> {

    public static final Turn ZERO = new Turn(0);
    public static final Turn QUARTER = new Turn(0.25);
    public static final Turn HALF = new Turn(0.5);
    public static final Turn FULL = new Turn(1);

    public static Turn of(double turns) {
        return new Turn(turns);
    }

    public static Turn ofRadians(double radians) {
        return new Turn(radians / (2 * Math.PI));
    }

    public static Turn ofDegrees(double degrees) {
        return new Turn(degrees / 360.0);
    }

    /** The angle whose tangent is {@code slope}, in turns. Unity slope is an eighth of a turn. */
    public static Turn ofSlope(Ratio slope) {
        return new Turn(Math.atan2(slope.num(), slope.den()) / (2 * Math.PI));
    }

    /** The tangent of this angle — its slope, rise over run. */
    public double slope() {
        return Math.tan(turns * 2 * Math.PI);
    }

    public double radians() {
        return turns * 2 * Math.PI;
    }

    public double degrees() {
        return turns * 360;
    }

    /**
     * Reduced to {@code [0, 1)} by taking the fractional part.
     *
     * <p>Exact, which is the whole reason for the unit: no rounding happens at the wrap.
     */
    public Turn wrapped() {
        double f = turns - Math.floor(turns);
        return f == turns ? this : new Turn(f);
    }

    public Turn plus(Turn other) {
        return new Turn(turns + other.turns);
    }

    public Turn minus(Turn other) {
        return new Turn(turns - other.turns);
    }

    public Turn times(double factor) {
        return new Turn(turns * factor);
    }

    public double sin() {
        return Math.sin(radians());
    }

    public double cos() {
        return Math.cos(radians());
    }

    /**
     * Interpolation along the <b>shortest arc</b>.
     *
     * <p>The reason this exists rather than {@code Interp.DOUBLE}: a plain lerp from 0.9 turns to 0.1
     * turns travels backwards through 0.5, the long way round, which reads as a wheel spinning the wrong
     * direction. The shortest signed delta is the wrapped difference shifted into {@code [-0.5, 0.5)}.
     *
     * <p>Ties — exactly half a turn apart — resolve forwards, deterministically. There is no right answer
     * for a half turn, so the important thing is that it is the <em>same</em> answer every time.
     */
    public static final Interp<Turn> SHORTEST = (from, to, alpha) -> {
        double delta = to.turns - from.turns;
        delta = delta - Math.floor(delta + 0.5);        // into [-0.5, 0.5)
        return new Turn(from.turns + delta * alpha);
    };

    /** Interpolation the long way round, when the winding matters more than the distance. */
    public static final Interp<Turn> DIRECT =
            (from, to, alpha) -> new Turn(from.turns + (to.turns - from.turns) * alpha);

    @Override
    public int compareTo(Turn other) {
        return Double.compare(turns, other.turns);
    }

    @Override
    public String toString() {
        return turns + "turn";
    }
}
