package sibarum.kronometer;

/**
 * An exact positive rational, always in lowest terms.
 *
 * <p>Tempo scales are ratios rather than doubles, and that is not a stylistic preference. The eventual
 * purpose is nesting musical ratios — 3:2 inside 4:3 inside 7:4 — and a nested chain of floating-point
 * scales accumulates error. Accumulated error in a time grid is drift, and drift between two rhythmic
 * layers is the difference between a polyrhythm <em>locking</em> and a polyrhythm <em>smearing</em>.
 * With exact rationals, sibling grid lines coincide at their common multiple exactly. It is the same
 * discipline as {@link Metro}, one level up.
 *
 * <p>Read as a slope: {@code Ratio.of(3, 2)} is rise 3 over run 2, and the angle it stands for is
 * {@code arctan(3/2)} measured in <em>turns</em>. Turns matter — a turn wraps at 1 rather than at an
 * irrational 2π, so a phase built from these can be held and wrapped exactly, which is where
 * radian-based phase accumulators leak precision.
 *
 * @param num the numerator (rise); always positive
 * @param den the denominator (run); always positive
 */
public record Ratio(long num, long den) implements Comparable<Ratio> {

    public static final Ratio ONE = new Ratio(1, 1);
    public static final Ratio HALF = new Ratio(1, 2);
    public static final Ratio DOUBLE = new Ratio(2, 1);

    public Ratio {
        if (num <= 0 || den <= 0) {
            throw new IllegalArgumentException(
                    "a tempo ratio must be positive — logical time does not run backwards: " + num + "/" + den);
        }
        long g = gcd(num, den);
        num /= g;
        den /= g;
    }

    public static Ratio of(long num, long den) {
        return new Ratio(num, den);
    }

    /** Exact rational multiplication, reduced before multiplying to keep the products small. */
    public Ratio times(Ratio other) {
        long g1 = gcd(num, other.den);
        long g2 = gcd(other.num, den);
        return new Ratio(
                Math.multiplyExact(num / g1, other.num / g2),
                Math.multiplyExact(den / g2, other.den / g1));
    }

    public Ratio dividedBy(Ratio other) {
        return times(other.reciprocal());
    }

    public Ratio reciprocal() {
        return new Ratio(den, num);
    }

    public boolean isOne() {
        return num == den;
    }

    /** For display and for the rare place a real number is genuinely wanted. Never for grid arithmetic. */
    public double value() {
        return num / (double) den;
    }

    /**
     * Scale a nanosecond count by this ratio, rounded to nearest.
     *
     * <p>Reduces against the denominator before multiplying, which both avoids avoidable overflow and
     * makes the result <em>exact</em> whenever the denominator divides the value — which, for grids
     * built from commensurate ratios, is most of the time. Where it does not divide, the error is at
     * most half a nanosecond on this one conversion; because callers convert from an origin rather than
     * from the previous result, it never accumulates.
     */
    public long scale(long nanos) {
        if (nanos == 0 || isOne()) {
            return nanos;
        }
        long sign = nanos < 0 ? -1 : 1;
        long magnitude = Math.abs(nanos);
        long g = gcd(magnitude, den);
        long scaled = Math.multiplyExact(magnitude / g, num);
        long divisor = den / g;
        long quotient = scaled / divisor;
        long remainder = scaled % divisor;
        if (remainder * 2 >= divisor) {
            quotient++;
        }
        return sign * quotient;
    }

    public Dur scale(Dur d) {
        return new Dur(scale(d.nanos()));
    }

    @Override
    public int compareTo(Ratio other) {
        return Long.compare(
                Math.multiplyExact(num, other.den), Math.multiplyExact(other.num, den));
    }

    @Override
    public String toString() {
        return num + ":" + den;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
