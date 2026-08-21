package sibarum.kronometer;

/**
 * A point on the logical timeline, in nanoseconds since the kernel's origin.
 *
 * <p>Logical time is not wall-clock time. Under {@link Clock#virtual()} a {@code Moment} has no
 * relationship to the wall at all, and under the realtime clock the relationship carries an offset —
 * see the slip model in {@code docs/architecture.md} §5.1.
 *
 * @param nanos nanoseconds since {@link #ORIGIN}
 */
public record Moment(long nanos) implements Comparable<Moment> {

    /** Where every {@link Kron} starts. */
    public static final Moment ORIGIN = new Moment(0);

    /**
     * A sentinel for "knowable indefinitely" — the horizon of a value whose whole future is determined.
     *
     * <p>A sentinel, not a moment: arithmetic on it overflows and will throw. Compare against it, do not
     * compute with it.
     */
    public static final Moment FOREVER = new Moment(Long.MAX_VALUE);

    public Moment plus(Dur d) {
        return new Moment(Math.addExact(nanos, d.nanos()));
    }

    public Moment minus(Dur d) {
        return new Moment(Math.subtractExact(nanos, d.nanos()));
    }

    /** How long this moment is after {@code earlier}; negative if it is before. */
    public Dur since(Moment earlier) {
        return new Dur(Math.subtractExact(nanos, earlier.nanos));
    }

    public boolean isBefore(Moment other) {
        return nanos < other.nanos;
    }

    public boolean isAfter(Moment other) {
        return nanos > other.nanos;
    }

    @Override
    public int compareTo(Moment other) {
        return Long.compare(nanos, other.nanos);
    }

    @Override
    public String toString() {
        return "@" + new Dur(nanos);
    }
}
