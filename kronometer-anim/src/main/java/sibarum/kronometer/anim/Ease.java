package sibarum.kronometer.anim;

/**
 * Shapes normalized progress. Pure, so anything built from one stays predictable.
 *
 * <p>An ease is separate from the {@link sibarum.kronometer.Curve} it shapes on purpose: a ramp says
 * <em>what</em> is being interpolated, an ease says <em>how the clock is bent</em> on the way, and
 * keeping them apart is what lets either be swapped without touching the other.
 *
 * <h2>Endpoints are exact</h2>
 *
 * Every ease here returns exactly {@code 0} at {@code 0} and exactly {@code 1} at {@code 1}. That is not
 * automatic — the textbook exponential ease evaluates to 2⁻¹⁰ rather than zero at the start, and a
 * cubic Bézier solved numerically lands near its endpoints rather than on them. An animation that stops
 * at 0.999 of its target is a bug that shows up as a shadow that never quite settles, so the endpoints
 * are special-cased and there is a test that walks every constant in this class.
 */
@FunctionalInterface
public interface Ease {

    /**
     * @param t normalized progress, clamped by the caller to {@code [0, 1]}
     * @return shaped progress, exactly 0 at 0 and exactly 1 at 1
     */
    float at(float t);

    Ease LINEAR = t -> t;

    Ease IN_QUAD = t -> t * t;
    Ease OUT_QUAD = t -> 1 - (1 - t) * (1 - t);
    Ease IN_OUT_QUAD = t -> t < 0.5f ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t);

    Ease IN_CUBIC = t -> t * t * t;
    Ease OUT_CUBIC = t -> {
        float u = 1 - t;
        return 1 - u * u * u;
    };
    Ease IN_OUT_CUBIC = t -> {
        if (t < 0.5f) {
            return 4 * t * t * t;
        }
        float u = 1 - t;
        return 1 - 4 * u * u * u;
    };

    Ease IN_QUART = t -> t * t * t * t;
    Ease OUT_QUART = t -> {
        float u = 1 - t;
        return 1 - u * u * u * u;
    };

    Ease IN_SINE = t -> t == 1 ? 1 : 1 - (float) Math.cos(t * Math.PI / 2);
    Ease OUT_SINE = t -> t == 1 ? 1 : (float) Math.sin(t * Math.PI / 2);
    Ease IN_OUT_SINE = t -> t == 1 ? 1 : (float) (-(Math.cos(Math.PI * t) - 1) / 2);

    /** Exponential, with both endpoints pinned — 2⁻¹⁰ is not zero and 0.999 is not one. */
    Ease IN_EXPO = t -> t == 0 ? 0 : t == 1 ? 1 : (float) Math.pow(2, 10 * t - 10);
    Ease OUT_EXPO = t -> t == 0 ? 0 : t == 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
    Ease IN_OUT_EXPO = t -> {
        if (t == 0) {
            return 0;
        }
        if (t == 1) {
            return 1;
        }
        return t < 0.5f
                ? (float) Math.pow(2, 20 * t - 10) / 2
                : (2 - (float) Math.pow(2, -20 * t + 10)) / 2;
    };

    /** Overshoots and comes back. Useful; also the easiest way to notice an unclamped sink. */
    Ease OUT_BACK = t -> {
        if (t == 0) {
            return 0;
        }
        if (t == 1) {
            return 1;
        }
        float c = 1.70158f;
        float u = t - 1;
        return 1 + (c + 1) * u * u * u + c * u * u;
    };

    Ease IN_BACK = t -> {
        if (t == 0) {
            return 0;
        }
        if (t == 1) {
            return 1;
        }
        float c = 1.70158f;
        return (c + 1) * t * t * t - c * t * t;
    };

    /** Runs this ease backwards: {@code reversed().at(t) == 1 - at(1 - t)}. */
    default Ease reversed() {
        Ease self = this;
        return t -> t == 0 ? 0 : t == 1 ? 1 : 1 - self.at(1 - t);
    }

    /** An arbitrary shape, with its endpoints pinned for you. */
    static Ease of(Ease shape) {
        return t -> t <= 0 ? 0 : t >= 1 ? 1 : shape.at(t);
    }

    /**
     * A CSS-style cubic Bézier through {@code (0,0)}, {@code (x1,y1)}, {@code (x2,y2)}, {@code (1,1)}.
     *
     * <p>Solved for {@code t} given {@code x} by a few Newton steps with a bisection fallback, because
     * Newton alone diverges for the flat-tangent control points people actually use.
     */
    static Ease bezier(float x1, float y1, float x2, float y2) {
        return t -> {
            if (t <= 0) {
                return 0;
            }
            if (t >= 1) {
                return 1;
            }
            double u = solveBezierX(t, x1, x2);
            return (float) cubic(u, y1, y2);
        };
    }

    /** {@code n} discrete jumps — for stepped, non-continuous motion. */
    static Ease steps(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("steps must be positive: " + n);
        }
        return t -> t >= 1 ? 1 : (float) Math.floor(t * n) / n;
    }

    private static double cubic(double t, double a, double b) {
        double u = 1 - t;
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t;
    }

    private static double cubicSlope(double t, double a, double b) {
        double u = 1 - t;
        return 3 * u * u * a + 6 * u * t * (b - a) + 3 * t * t * (1 - b);
    }

    private static double solveBezierX(double x, double x1, double x2) {
        double t = x;
        for (int i = 0; i < 8; i++) {
            double error = cubic(t, x1, x2) - x;
            if (Math.abs(error) < 1e-7) {
                return t;
            }
            double slope = cubicSlope(t, x1, x2);
            if (Math.abs(slope) < 1e-7) {
                break;
            }
            t -= error / slope;
        }
        double low = 0;
        double high = 1;
        t = x;
        for (int i = 0; i < 32; i++) {
            double value = cubic(t, x1, x2);
            if (Math.abs(value - x) < 1e-7) {
                return t;
            }
            if (value < x) {
                low = t;
            } else {
                high = t;
            }
            t = (low + high) / 2;
        }
        return t;
    }
}
