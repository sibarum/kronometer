package sibarum.kronometer.anim;

import sibarum.kronometer.Interp;

import java.util.Arrays;

/**
 * A Cayley–Dickson number: real, complex, quaternion, octonion, sedenion — one type, doubling by level.
 *
 * <p>Built generically rather than as a hard-coded quaternion because the design asked for it (§15.7):
 * a rotation is the level-2 case of something more general, and the intended musical use drives phase
 * objects from a nested ratio hierarchy, one Cayley–Dickson level per level of nesting. Writing
 * quaternions and then generalizing later would have meant writing them twice.
 *
 * <h2>What is lost at each level, and why it matters here</h2>
 *
 * <table border="1">
 * <caption>The Cayley–Dickson tower</caption>
 * <tr><th>Level</th><th>Dimension</th><th>Name</th><th>Loses</th></tr>
 * <tr><td>0</td><td>1</td><td>real</td><td>—</td></tr>
 * <tr><td>1</td><td>2</td><td>complex</td><td>ordering</td></tr>
 * <tr><td>2</td><td>4</td><td>quaternion</td><td>commutativity</td></tr>
 * <tr><td>3</td><td>8</td><td>octonion</td><td>associativity</td></tr>
 * <tr><td>4</td><td>16</td><td>sedenion</td><td>division algebra</td></tr>
 * </table>
 *
 * <p>Losing associativity at level 3 is the reason {@code Tempo} keeps its tree instead of collapsing to
 * an effective scale (architecture §6.3). A product that depends on how it is bracketed cannot be
 * flattened, so the nesting is structure, not notation. There is a test asserting the non-associativity
 * directly, because it is load-bearing rather than trivia.
 *
 * @param components the coefficients; the length must be a power of two
 */
public record Hyper(double[] components) {

    public Hyper {
        int n = components.length;
        if (n == 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "a Cayley–Dickson number has a power-of-two dimension, not " + n);
        }
        components = components.clone();            // records do not copy arrays for you
        for (int i = 0; i < n; i++) {
            if (components[i] == 0.0) {
                // Collapse -0.0 to 0.0. Arrays.equals compares bits, so without this a rotation
                // computed one way is unequal to the numerically identical rotation computed another:
                // negating k gives [-0.0, -0.0, -0.0, -1.0] while multiplying j by i gives
                // [0.0, 0.0, 0.0, -1.0]. Equal numbers should be equal values.
                components[i] = 0.0;
            }
        }
    }

    /**
     * The coefficients, copied.
     *
     * <p>Overridden because the generated accessor would hand out the record's own array, and a value
     * type whose insides can be edited from outside is not one.
     */
    @Override
    public double[] components() {
        return components.clone();
    }

    public static Hyper of(double... components) {
        return new Hyper(components);
    }

    public static Hyper real(double re) {
        return new Hyper(new double[] {re});
    }

    public static Hyper complex(double re, double im) {
        return new Hyper(new double[] {re, im});
    }

    /** A quaternion, {@code w} first. Level 2 of the tower. */
    public static Hyper quaternion(double w, double x, double y, double z) {
        return new Hyper(new double[] {w, x, y, z});
    }

    /** A rotation of {@code angle} about a unit axis, as a quaternion. */
    public static Hyper rotation(Turn angle, double x, double y, double z) {
        double half = angle.radians() / 2;
        double s = Math.sin(half);
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0) {
            throw new IllegalArgumentException("a rotation axis cannot be zero-length");
        }
        return quaternion(Math.cos(half), s * x / length, s * y / length, s * z / length);
    }

    public int dimension() {
        return components.length;
    }

    /** The Cayley–Dickson level: 0 real, 1 complex, 2 quaternion, 3 octonion. */
    public int level() {
        return Integer.numberOfTrailingZeros(components.length);
    }

    public double get(int i) {
        return components[i];
    }

    /** The scalar part. */
    public double re() {
        return components[0];
    }

    public Hyper plus(Hyper other) {
        double[] out = alignedCopy(other);
        double[] b = other.padTo(out.length);
        for (int i = 0; i < out.length; i++) {
            out[i] += b[i];
        }
        return new Hyper(out);
    }

    public Hyper minus(Hyper other) {
        double[] out = alignedCopy(other);
        double[] b = other.padTo(out.length);
        for (int i = 0; i < out.length; i++) {
            out[i] -= b[i];
        }
        return new Hyper(out);
    }

    public Hyper scaled(double factor) {
        double[] out = components.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] *= factor;
        }
        return new Hyper(out);
    }

    /**
     * The Cayley–Dickson product, recursively: {@code (a,b)(c,d) = (ac − d̄b, da + bc̄)}.
     *
     * <p>Non-commutative from level 2 and non-associative from level 3, both as a consequence of this
     * one line rather than as special cases.
     */
    public Hyper times(Hyper other) {
        int n = Math.max(dimension(), other.dimension());
        return new Hyper(multiply(padTo(n), other.padTo(n)));
    }

    /** Negate everything but the scalar part. */
    public Hyper conjugate() {
        double[] out = components.clone();
        for (int i = 1; i < out.length; i++) {
            out[i] = -out[i];
        }
        return new Hyper(out);
    }

    public double norm() {
        double sum = 0;
        for (double c : components) {
            sum += c * c;
        }
        return Math.sqrt(sum);
    }

    public Hyper normalized() {
        double n = norm();
        if (n == 0) {
            throw new IllegalStateException("cannot normalize a zero-norm number");
        }
        return scaled(1 / n);
    }

    public double dot(Hyper other) {
        int n = Math.max(dimension(), other.dimension());
        double[] a = padTo(n);
        double[] b = other.padTo(n);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Spherical interpolation — the great-circle path between two unit numbers, at <b>constant angular
     * velocity</b>.
     *
     * <p>Dimension-generic, so quaternion slerp is the level-2 case rather than a separate
     * implementation. Two details that a naive version gets wrong:
     *
     * <ul>
     *   <li><b>The double cover.</b> {@code q} and {@code −q} are the same rotation, so a negative dot
     *       product means the shorter path is to the antipode. Without the flip a 10° rotation can be
     *       interpolated the 350° way round.</li>
     *   <li><b>The near-parallel case.</b> {@code sin(θ)} goes to zero as the inputs converge, so past
     *       0.9995 it falls back to a normalized straight line, which is indistinguishable at that angle
     *       and does not divide by nearly nothing.</li>
     * </ul>
     */
    public static final Interp<Hyper> SLERP = (from, to, alpha) -> {
        int n = Math.max(from.dimension(), to.dimension());
        Hyper a = new Hyper(from.padTo(n)).normalized();
        Hyper b = new Hyper(to.padTo(n)).normalized();

        double dot = a.dot(b);
        if (dot < 0) {
            b = b.scaled(-1);
            dot = -dot;
        }
        dot = Math.clamp(dot, -1.0, 1.0);

        if (dot > 0.9995) {
            return a.plus(b.minus(a).scaled(alpha)).normalized();
        }
        double theta = Math.acos(dot);
        double sin = Math.sin(theta);
        double wa = Math.sin((1 - alpha) * theta) / sin;
        double wb = Math.sin(alpha * theta) / sin;
        return a.scaled(wa).plus(b.scaled(wb));
    };

    /** Component-wise interpolation. Wrong for rotations; correct for anything that is not one. */
    public static final Interp<Hyper> LINEAR = (from, to, alpha) -> {
        int n = Math.max(from.dimension(), to.dimension());
        double[] a = from.padTo(n);
        double[] b = to.padTo(n);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = a[i] + (b[i] - a[i]) * alpha;
        }
        return new Hyper(out);
    };

    @Override
    public boolean equals(Object o) {
        return o instanceof Hyper other && Arrays.equals(components, other.components);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(components);
    }

    @Override
    public String toString() {
        return "Hyper" + level() + Arrays.toString(components);
    }

    // -------------------------------------------------------------- internals

    private double[] alignedCopy(Hyper other) {
        return padTo(Math.max(dimension(), other.dimension()));
    }

    /** Embed in a higher dimension by zero-filling — every level contains the ones below it. */
    private double[] padTo(int n) {
        if (n == components.length) {
            return components.clone();
        }
        double[] out = new double[n];
        System.arraycopy(components, 0, out, 0, components.length);
        return out;
    }

    private static double[] multiply(double[] a, double[] b) {
        int n = a.length;
        if (n == 1) {
            return new double[] {a[0] * b[0]};
        }
        int h = n / 2;
        double[] a1 = Arrays.copyOfRange(a, 0, h);
        double[] a2 = Arrays.copyOfRange(a, h, n);
        double[] b1 = Arrays.copyOfRange(b, 0, h);
        double[] b2 = Arrays.copyOfRange(b, h, n);

        // (a1, a2)(b1, b2) = (a1·b1 − conj(b2)·a2,  b2·a1 + a2·conj(b1))
        double[] first = subtract(multiply(a1, b1), multiply(conjugate(b2), a2));
        double[] second = add(multiply(b2, a1), multiply(a2, conjugate(b1)));

        double[] out = new double[n];
        System.arraycopy(first, 0, out, 0, h);
        System.arraycopy(second, 0, out, h, h);
        return out;
    }

    private static double[] conjugate(double[] v) {
        double[] out = v.clone();
        for (int i = 1; i < out.length; i++) {
            out[i] = -out[i];
        }
        return out;
    }

    private static double[] add(double[] x, double[] y) {
        double[] out = x.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] += y[i];
        }
        return out;
    }

    private static double[] subtract(double[] x, double[] y) {
        double[] out = x.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] -= y[i];
        }
        return out;
    }
}
