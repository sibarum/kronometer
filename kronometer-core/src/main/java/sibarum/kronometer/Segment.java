package sibarum.kronometer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Objects;

/**
 * One stretch of a {@link Tempo}'s speed relative to its parent, from the moment it began until another
 * segment replaces it.
 *
 * <p>A segment is a function from elapsed <em>parent</em> time to elapsed local time, both in whole
 * nanoseconds and measured from the segment's own start. It is defined for every input, not only over the
 * segment's extent: before its start it extrapolates at its starting speed, and after a glide it holds the
 * speed it ended on. That is what lets a tempo's future be converted, and predicted, without anything
 * having to happen at the end of a glide.
 *
 * <p>Two operations, which must agree to the nanosecond:
 *
 * <ul>
 *   <li>{@link #advance} rounds <b>down</b>: a local instant is reached once it has fully elapsed.</li>
 *   <li>{@link #leastReaching} is the least parent duration whose advance reaches a target. It is
 *       computed in closed form and then checked against {@link #advance} itself, so it is right by
 *       construction.</li>
 * </ul>
 *
 * <p><b>The knot rule.</b> Every segment ends on an exact {@link Ratio}. A linear glide's integral is
 * exact rational arithmetic; an exponential glide's is not, since it involves a logarithm. Either way, the
 * speed it settles on is a {@code Ratio}, and the local time it ends at is fixed once as a whole
 * nanosecond, so after the glide the conversion is exact again and exact grids line up again.
 */
sealed interface Segment permits Segment.Hold, Segment.Linear, Segment.Exponential {

    /** The exact speed this segment settles on, and the one the flattened conversion uses after it. */
    Ratio endSpeed();

    /**
     * Parent time from the segment's start until it settles: zero for a hold, the glide's extent for a
     * glide. Past this, the segment is {@code endSpeed()} held forever.
     */
    long extent();

    /** Local nanoseconds elapsed after {@code parentNanos} of parent time, rounded down. */
    long advance(long parentNanos);

    /** The least parent duration {@code p} with {@code advance(p) >= target}. */
    long leastReaching(long target);

    /**
     * The speed after {@code parentNanos}, as an exact fraction {@code [num, den]}: where a glide that
     * replaces this segment starts from, so that speed is continuous across the change.
     */
    BigInteger[] speedAt(long parentNanos);

    /** A constant speed. */
    record Hold(Ratio speed) implements Segment {

        public Hold {
            Objects.requireNonNull(speed, "speed");
        }

        @Override
        public Ratio endSpeed() {
            return speed;
        }

        @Override
        public long extent() {
            return 0;
        }

        @Override
        public long advance(long parentNanos) {
            return Tempo.floorScaled(speed, parentNanos);
        }

        @Override
        public long leastReaching(long target) {
            return Tempo.leastReaching(speed, target);
        }

        @Override
        public BigInteger[] speedAt(long parentNanos) {
            return new BigInteger[] {BigInteger.valueOf(speed.num()), BigInteger.valueOf(speed.den())};
        }

        @Override
        public String toString() {
            return "hold(" + speed + ")";
        }
    }

    /**
     * A speed that changes in a straight line, from {@code r0} to {@code r1} over {@code extent}
     * nanoseconds of parent time, and then holds {@code r1}.
     *
     * <p>The integral is a quadratic, and it is evaluated exactly. With {@code r0 = n0/d0} and
     * {@code r1 = n1/d1}:
     *
     * <pre>
     *   I(x) = r0·x + (r1 − r0)·x² / 2D  =  (α·x² + β·x) / Δ
     *   α = n1·d0 − n0·d1     β = 2D·n0·d1     Δ = 2D·d0·d1
     * </pre>
     *
     * <p>{@code r0} is held as a {@code BigInteger} fraction rather than a {@code Ratio} because a glide
     * may start from the middle of another one, where the speed is exact but its denominator carries a
     * factor of the earlier glide's extent. {@code r1} is a {@code Ratio}: the knot rule.
     */
    final class Linear implements Segment {

        private final BigInteger n0;
        private final BigInteger d0;
        private final Ratio to;
        private final long extent;

        private final BigInteger alpha;
        private final BigInteger beta;
        private final BigInteger delta;
        /** {@code floor(I(extent))}: where the glide ends, and where the hold after it starts from. */
        private final long atEnd;

        Linear(BigInteger n0, BigInteger d0, Ratio to, long extent) {
            if (n0.signum() <= 0 || d0.signum() <= 0) {
                throw new IllegalArgumentException("a glide must start at a positive speed: " + n0 + "/" + d0);
            }
            if (extent <= 0) {
                throw new IllegalArgumentException("a glide needs a positive extent: " + extent + " ns");
            }
            BigInteger g = n0.gcd(d0);
            this.n0 = n0.divide(g);
            this.d0 = d0.divide(g);
            this.to = Objects.requireNonNull(to, "to");
            this.extent = extent;

            BigInteger n1 = BigInteger.valueOf(to.num());
            BigInteger d1 = BigInteger.valueOf(to.den());
            BigInteger twoD = BigInteger.valueOf(extent).shiftLeft(1);
            this.alpha = n1.multiply(this.d0).subtract(this.n0.multiply(d1));
            this.beta = twoD.multiply(this.n0).multiply(d1);
            this.delta = twoD.multiply(this.d0).multiply(d1);
            this.atEnd = integral(extent);
        }

        /** A glide from the exact speed {@code from} to {@code to}. */
        static Linear between(Ratio from, Ratio to, long extent) {
            return new Linear(BigInteger.valueOf(from.num()), BigInteger.valueOf(from.den()), to, extent);
        }

        @Override
        public Ratio endSpeed() {
            return to;
        }

        @Override
        public long extent() {
            return extent;
        }

        @Override
        public BigInteger[] speedAt(long parentNanos) {
            if (parentNanos <= 0) {
                return new BigInteger[] {n0, d0};
            }
            if (parentNanos >= extent) {
                return new BigInteger[] {BigInteger.valueOf(to.num()), BigInteger.valueOf(to.den())};
            }
            // s(x) = I'(x) = (2α·x + β) / Δ
            BigInteger num = alpha.multiply(BigInteger.valueOf(parentNanos)).shiftLeft(1).add(beta);
            BigInteger g = num.gcd(delta);
            return new BigInteger[] {num.divide(g), delta.divide(g)};
        }

        @Override
        public long advance(long parentNanos) {
            if (parentNanos <= 0) {
                return floorDiv(n0.multiply(BigInteger.valueOf(parentNanos)), d0).longValueExact();
            }
            if (parentNanos <= extent) {
                return integral(parentNanos);
            }
            return Math.addExact(atEnd, Tempo.floorScaled(to, parentNanos - extent));
        }

        @Override
        public long leastReaching(long target) {
            long p = closedForm(target);
            while (advance(p) < target) {
                p++;
            }
            while (advance(p - 1) >= target) {
                p--;
            }
            return p;
        }

        /**
         * The candidate for {@link #leastReaching}. Within the glide, the root of
         * {@code α·x² + β·x = γ} with {@code γ = target·Δ}, written as {@code 2γ / (β + √(β² + 4αγ))} so
         * that it holds for a glide that speeds up and one that slows down alike, with the square root
         * taken as an exact integer floor. The fix-up in {@link #leastReaching} absorbs the rounding.
         */
        long closedForm(long target) {
            if (target <= 0) {
                BigInteger t = BigInteger.valueOf(target);
                return floorDiv(t.multiply(d0).add(n0).subtract(BigInteger.ONE), n0).longValueExact();
            }
            if (target > atEnd) {
                return Math.addExact(extent, Tempo.leastReaching(to, target - atEnd));
            }
            BigInteger gamma = BigInteger.valueOf(target).multiply(delta);
            BigInteger root = beta.multiply(beta).add(alpha.multiply(gamma).shiftLeft(2)).sqrt();
            BigInteger twoGamma = gamma.shiftLeft(1);
            BigInteger divisor = beta.add(root);
            return floorDiv(twoGamma.add(divisor).subtract(BigInteger.ONE), divisor).longValueExact();
        }

        /** {@code floor(I(x))}, exactly. */
        private long integral(long x) {
            BigInteger bx = BigInteger.valueOf(x);
            return floorDiv(alpha.multiply(bx).multiply(bx).add(beta.multiply(bx)), delta).longValueExact();
        }

        private static BigInteger floorDiv(BigInteger a, BigInteger b) {
            BigInteger[] qr = a.divideAndRemainder(b);
            return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        }

        @Override
        public String toString() {
            return "glide(" + n0 + "/" + d0 + " -> " + to + " over " + new Dur(extent) + ")";
        }
    }

    /**
     * A speed that changes by a constant <em>ratio</em> per unit of parent time, from {@code r0} to
     * {@code r1} over {@code extent}, and then holds {@code r1}. Tempo and pitch are heard on a log scale,
     * so this is the glide that sounds even: halfway through a glide from 1 to 4, the speed is 2.
     *
     * <pre>
     *   s(x) = r0 · ρ^(x/D)              ρ = r1 / r0
     *   I(x) = (r0·D / ln ρ) · (ρ^(x/D) − 1)
     * </pre>
     *
     * <p>The integral is transcendental, so it is computed in {@code double}, with {@link StrictMath}'s
     * {@code expm1} and {@code log1p} so that it gives the same bits on every platform and under
     * native-image. Two things keep that from mattering:
     *
     * <ul>
     *   <li><b>It is exact at its end.</b> {@code floor(I(D))} is computed once, and after the extent the
     *       segment is that whole nanosecond plus an exact {@link Ratio} hold. So nothing inexact outlives
     *       the glide.</li>
     *   <li><b>It is monotone.</b> {@code I} is evaluated to about {@code 1e-16} relative, a millionth of a
     *       nanosecond over hours, while every nanosecond of parent time adds at least the slower of the two
     *       speeds. So rounding cannot make local time step backwards for any speed this side of a
     *       millionth.</li>
     * </ul>
     *
     * <p>The inverse is {@code log1p}, checked against {@link #advance} by the same fix-up as the others.
     */
    final class Exponential implements Segment {

        private final BigInteger n0;
        private final BigInteger d0;
        private final Ratio to;
        private final long extent;

        private final double r0;
        private final double logRho;
        /** {@code r0·D / ln ρ}: the integral's scale. */
        private final double scale;
        /** {@code floor(I(extent))}: where the glide ends, and where the hold after it starts from. */
        private final long atEnd;

        Exponential(BigInteger n0, BigInteger d0, Ratio to, long extent) {
            if (n0.signum() <= 0 || d0.signum() <= 0) {
                throw new IllegalArgumentException("a glide must start at a positive speed: " + n0 + "/" + d0);
            }
            if (extent <= 0) {
                throw new IllegalArgumentException("a glide needs a positive extent: " + extent + " ns");
            }
            BigInteger g = n0.gcd(d0);
            this.n0 = n0.divide(g);
            this.d0 = d0.divide(g);
            this.to = Objects.requireNonNull(to, "to");
            this.extent = extent;

            this.r0 = toDouble(this.n0, this.d0);
            this.logRho = StrictMath.log(to.value() / r0);
            this.scale = logRho == 0.0 ? 0.0 : r0 * extent / logRho;
            this.atEnd = integral(extent);
        }

        /** A glide from the exact speed {@code from} to {@code to}. */
        static Exponential between(Ratio from, Ratio to, long extent) {
            return new Exponential(BigInteger.valueOf(from.num()), BigInteger.valueOf(from.den()), to, extent);
        }

        @Override
        public Ratio endSpeed() {
            return to;
        }

        @Override
        public long extent() {
            return extent;
        }

        /**
         * Within the glide, the speed is irrational in general, so this is the exact value of the
         * {@code double} nearest it. The next glide starts there: continuous to a part in {@code 1e16}.
         */
        @Override
        public BigInteger[] speedAt(long parentNanos) {
            if (parentNanos <= 0) {
                return new BigInteger[] {n0, d0};
            }
            if (parentNanos >= extent) {
                return new BigInteger[] {BigInteger.valueOf(to.num()), BigInteger.valueOf(to.den())};
            }
            double speed = r0 * StrictMath.exp(logRho * ((double) parentNanos / extent));
            BigDecimal exact = new BigDecimal(speed);
            BigInteger num = exact.unscaledValue();
            BigInteger den = BigInteger.TEN.pow(Math.max(exact.scale(), 0));
            if (exact.scale() < 0) {
                num = num.multiply(BigInteger.TEN.pow(-exact.scale()));
            }
            BigInteger g = num.gcd(den);
            return new BigInteger[] {num.divide(g), den.divide(g)};
        }

        @Override
        public long advance(long parentNanos) {
            if (parentNanos <= 0) {
                return (long) Math.floor(r0 * parentNanos);
            }
            if (parentNanos <= extent) {
                return integral(parentNanos);
            }
            return Math.addExact(atEnd, Tempo.floorScaled(to, parentNanos - extent));
        }

        @Override
        public long leastReaching(long target) {
            long p = closedForm(target);
            while (advance(p) < target) {
                p++;
            }
            while (advance(p - 1) >= target) {
                p--;
            }
            return p;
        }

        /** The candidate for {@link #leastReaching}: {@code I⁻¹(k) = (D / ln ρ) · log1p(k · ln ρ / (r0·D))}. */
        long closedForm(long target) {
            if (target <= 0) {
                return (long) Math.ceil(target / r0);
            }
            if (target > atEnd) {
                return Math.addExact(extent, Tempo.leastReaching(to, target - atEnd));
            }
            if (logRho == 0.0) {
                return (long) Math.ceil(target / r0);
            }
            double x = extent / logRho * StrictMath.log1p(target / scale);
            return Math.min(extent, (long) Math.ceil(x));
        }

        /** {@code floor(I(x))}. */
        private long integral(long x) {
            if (logRho == 0.0) {
                return (long) Math.floor(r0 * x);
            }
            return (long) Math.floor(scale * StrictMath.expm1(logRho * ((double) x / extent)));
        }

        private static double toDouble(BigInteger num, BigInteger den) {
            return new BigDecimal(num).divide(new BigDecimal(den), MathContext.DECIMAL64).doubleValue();
        }

        @Override
        public String toString() {
            return "exp-glide(" + n0 + "/" + d0 + " -> " + to + " over " + new Dur(extent) + ")";
        }
    }
}
