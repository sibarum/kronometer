package sibarum.kronometer;

import java.util.Objects;
import java.util.function.Function;

/**
 * A pure function of elapsed time, and therefore predictable by construction.
 *
 * <p>A curve is unanchored: it knows its shape and its extent, not when it starts. {@link Cell#drive}
 * anchors one at the current moment, and the elapsed time it is asked about is <b>local to the tempo it
 * was anchored in</b> — so driving a 200 ms curve inside a 1:4 tempo stretches it to 800 ms of wall
 * time, and the animation slows with everything else in that region rather than needing to be told.
 *
 * <p>Being pure is load-bearing rather than stylistic: it is the entire justification for evaluating a
 * curve ahead of {@code now}, in parallel, off the baton (M5).
 */
public interface Curve<T> {

    /** How long this curve lasts in local time. {@link Dur#FOREVER} if it never ends. */
    Dur extent();

    /**
     * The value at {@code elapsed} local time from the curve's start. Implementations may assume
     * {@code elapsed} is clamped to {@code [0, extent]} — {@link Cell} clamps before calling.
     */
    T at(Dur elapsed);

    /** Whether this curve runs forever. */
    default boolean isInfinite() {
        return extent().equals(Dur.FOREVER);
    }

    /** A curve that holds one value forever. */
    static <T> Curve<T> constant(T value) {
        return new Curve<>() {
            @Override
            public Dur extent() {
                return Dur.FOREVER;
            }

            @Override
            public T at(Dur elapsed) {
                return value;
            }

            @Override
            public String toString() {
                return "constant(" + value + ")";
            }
        };
    }

    /**
     * A straight interpolation from {@code from} to {@code to} over {@code extent}.
     *
     * <p>Deliberately linear: shaping belongs to {@code Ease}, which is M6. A ramp plus an ease is a
     * tween, and keeping them separate is what lets an ease be swapped without touching the curve.
     */
    static <T> Curve<T> ramp(T from, T to, Dur extent, Interp<T> interp) {
        Objects.requireNonNull(interp, "interp");
        requirePositive(extent);
        return new Curve<>() {
            @Override
            public Dur extent() {
                return extent;
            }

            @Override
            public T at(Dur elapsed) {
                float alpha = (float) (elapsed.nanos() / (double) extent.nanos());
                return interp.between(from, to, Math.clamp(alpha, 0f, 1f));
            }

            @Override
            public String toString() {
                return "ramp(" + from + " -> " + to + " over " + extent + ")";
            }
        };
    }

    static Curve<Double> ramp(double from, double to, Dur extent) {
        return ramp(from, to, extent, Interp.DOUBLE);
    }

    /** An arbitrary shape over a finite extent. */
    static <T> Curve<T> of(Dur extent, Function<Dur, T> shape) {
        Objects.requireNonNull(shape, "shape");
        requirePositive(extent);
        return new Curve<>() {
            @Override
            public Dur extent() {
                return extent;
            }

            @Override
            public T at(Dur elapsed) {
                return shape.apply(elapsed);
            }
        };
    }

    /** An arbitrary shape with no end — a sine, an oscillator, a clock. */
    static <T> Curve<T> forever(Function<Dur, T> shape) {
        Objects.requireNonNull(shape, "shape");
        return new Curve<>() {
            @Override
            public Dur extent() {
                return Dur.FOREVER;
            }

            @Override
            public T at(Dur elapsed) {
                return shape.apply(elapsed);
            }
        };
    }

    /**
     * A sine of the given period and amplitude, in <b>turns</b> — phase 0 to 1 over one period, not 0
     * to 2π.
     *
     * <p>Turns because a turn wraps at 1 rather than at an irrational constant, so phase can be reduced
     * exactly by taking a fractional part. Radian phase accumulators lose precision at every wrap; this
     * one does not.
     */
    static Curve<Double> sine(Dur period, double amplitude) {
        requirePositive(period);
        return forever(elapsed -> {
            double turns = (elapsed.nanos() % period.nanos()) / (double) period.nanos();
            return amplitude * Math.sin(turns * 2 * Math.PI);
        });
    }

    private static void requirePositive(Dur extent) {
        if (extent.nanos() <= 0) {
            throw new IllegalArgumentException("a curve needs a positive extent: " + extent);
        }
    }
}
