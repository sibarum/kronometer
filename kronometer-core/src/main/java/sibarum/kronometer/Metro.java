package sibarum.kronometer;

import java.util.Objects;

/**
 * A periodic clock for a shred, drift-free by construction.
 *
 * <pre>{@code
 * Metro m = Metro.hz(60);
 * while (running) {
 *     m.tick();
 *     step();
 * }
 * }</pre>
 *
 * <p>{@link #tick()} advances to {@code origin + n·period} for the next unused {@code n}, never to
 * "now plus a period". That is the whole point: an offset from the last wake accumulates every
 * rounding error it ever makes, while a multiple of the period from a fixed origin accumulates none.
 * A million ticks land exactly a million periods after the origin.
 *
 * <p>Created on the timeline, with the current moment as its origin.
 */
public final class Metro {

    private final Moment origin;
    private final Dur period;
    private long index;

    private Metro(Moment origin, Dur period) {
        this.origin = origin;
        this.period = period;
    }

    /** A metro of the given period, starting now. */
    public static Metro of(Dur period) {
        Objects.requireNonNull(period, "period");
        if (period.nanos() <= 0) {
            throw new IllegalArgumentException("period must be positive: " + period);
        }
        return new Metro(Time.now(), period);
    }

    /**
     * A metro at the given rate, starting now.
     *
     * <p>The period is {@link Dur#hz} — rounded to the nearest nanosecond — and every tick is an exact
     * multiple of it. Any error is the one-off difference between that period and the ideal rate, not
     * something that grows.
     */
    public static Metro hz(double rate) {
        return of(Dur.hz(rate));
    }

    public Moment origin() {
        return origin;
    }

    public Dur period() {
        return period;
    }

    /** How many periods have been consumed so far. */
    public long ticks() {
        return index;
    }

    /**
     * Advance to the next period boundary.
     *
     * @return how many boundaries were skipped because logical time was already past them. Zero under
     *         the virtual clock, where time only moves when a shred asks it to; under the realtime
     *         clock a non-zero return is the honest way to learn that a simulation lost steps rather
     *         than silently dropping them
     */
    public long tick() {
        Moment now = Time.now();
        long skipped = 0;
        Moment target = origin.plus(period.times(++index));
        while (!target.isAfter(now)) {
            target = origin.plus(period.times(++index));
            skipped++;
        }
        Time.until(target);
        return skipped;
    }
}
