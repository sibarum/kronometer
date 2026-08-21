package sibarum.kronometer;

/**
 * A span of logical time, in nanoseconds.
 *
 * <p>Nanoseconds are the base tick. A duration may be negative — {@link Moment#since} returns one
 * when its argument is in the future — but {@link Time#advance} rejects negatives, because time in a
 * shred only ever moves forward.
 *
 * @param nanos the span, in nanoseconds
 */
public record Dur(long nanos) implements Comparable<Dur> {

    public static final Dur ZERO = new Dur(0);

    /**
     * A sentinel for "unbounded" — the slack of an unpaced clock, and later the horizon of a value
     * whose future is perfectly known.
     *
     * <p>A sentinel, not a number: arithmetic on it overflows and will throw. Compare against it,
     * do not compute with it.
     */
    public static final Dur FOREVER = new Dur(Long.MAX_VALUE);

    public static Dur ns(long nanos) {
        return new Dur(nanos);
    }

    public static Dur us(long micros) {
        return new Dur(Math.multiplyExact(micros, 1_000L));
    }

    public static Dur ms(long millis) {
        return new Dur(Math.multiplyExact(millis, 1_000_000L));
    }

    public static Dur s(long seconds) {
        return new Dur(Math.multiplyExact(seconds, 1_000_000_000L));
    }

    public static Dur min(long minutes) {
        return new Dur(Math.multiplyExact(minutes, 60_000_000_000L));
    }

    /**
     * The period of a rate, rounded to the nearest nanosecond.
     *
     * <p>Most rates are not exactly representable — 60 Hz is 16 666 666.67 ns — so a period carries up
     * to half a nanosecond of error against the ideal rate. That error never accumulates in a shred,
     * because periodic work is scheduled from an origin rather than from the last wake
     * ({@link Metro}); it is only ever the one-off difference between the declared period and the
     * ideal one.
     */
    public static Dur hz(double rate) {
        if (!(rate > 0) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("rate must be finite and positive: " + rate);
        }
        return new Dur(Math.round(1_000_000_000.0 / rate));
    }

    public Dur plus(Dur other) {
        return new Dur(Math.addExact(nanos, other.nanos));
    }

    public Dur minus(Dur other) {
        return new Dur(Math.subtractExact(nanos, other.nanos));
    }

    public Dur times(long factor) {
        return new Dur(Math.multiplyExact(nanos, factor));
    }

    public Dur times(double factor) {
        return new Dur(Math.round(nanos * factor));
    }

    public Dur dividedBy(long divisor) {
        return new Dur(nanos / divisor);
    }

    /** How many whole {@code unit}s fit in this duration. */
    public long dividedBy(Dur unit) {
        return nanos / unit.nanos;
    }

    public boolean isZero() {
        return nanos == 0;
    }

    public boolean isNegative() {
        return nanos < 0;
    }

    public double toMillis() {
        return nanos / 1_000_000.0;
    }

    public double toSeconds() {
        return nanos / 1_000_000_000.0;
    }

    @Override
    public int compareTo(Dur other) {
        return Long.compare(nanos, other.nanos);
    }

    @Override
    public String toString() {
        long n = Math.abs(nanos);
        String sign = nanos < 0 ? "-" : "";
        if (n == 0) {
            return "0s";
        }
        if (n < 1_000L) {
            return sign + n + "ns";
        }
        if (n < 1_000_000L) {
            return sign + trim(n / 1_000.0) + "us";
        }
        if (n < 1_000_000_000L) {
            return sign + trim(n / 1_000_000.0) + "ms";
        }
        return sign + trim(n / 1_000_000_000.0) + "s";
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return String.valueOf(Math.round(value * 1_000.0) / 1_000.0);
    }
}
