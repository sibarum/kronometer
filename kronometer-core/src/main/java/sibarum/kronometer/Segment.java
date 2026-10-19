package sibarum.kronometer;

import java.math.BigInteger;
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
 * <p><b>The knot rule.</b> Every segment ends on an exact {@link Ratio}. A glide is inexact in no sense
 * that matters (its integral is exact rational arithmetic), but the speed it settles on is a
 * {@code Ratio}, so the tempo's flattened conversion takes over after it and exact grids line up again.
 */
sealed interface Segment permits Segment.Hold, Segment.Linear {

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

        /**
         * The exact speed after {@code parentNanos}, as a fraction {@code [num, den]}: where a glide that
         * replaces this one starts from, so that speed is continuous across the change.
         */
        BigInteger[] speedAt(long parentNanos) {
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
}
