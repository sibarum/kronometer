package sibarum.kronometer;

import java.util.function.Supplier;

/**
 * A value produced by one rate domain, readable smoothly from another.
 *
 * <p>The problem this solves is the one everybody hand-rolls: a graphics frame lands between two
 * physics steps, and rendering the older one stutters while rendering the newer one is time travel.
 * The answer is to interpolate, and because the domain knows its own grid and its own {@link Interp},
 * it can do that for you:
 *
 * <pre>{@code
 * Sampled<Double> shown = physics.sample(body::x, Interp.DOUBLE);
 * frames.each(step -> node.x(shown.at(step.at())));   // smooth at any refresh rate
 * }</pre>
 *
 * <h2>The one-step lag, stated plainly</h2>
 *
 * Interpolation can only look backwards. At render moment {@code t} the step after the latest one has
 * not happened yet, so blending towards it would mean inventing it. This therefore renders the value
 * as it was at {@code t - period}: it interpolates between the two most recent committed states as
 * {@code t} sweeps from one to the next.
 *
 * <p>That is a full step of latency, and it is the accepted price of smoothness — the same trade the
 * fixed-timestep-with-interpolation pattern has always made. A domain that would rather have the
 * latency than the smoothness should read the producing value directly.
 */
public final class Sampled<T> {

    private final Supplier<T> source;
    private final Interp<T> interp;

    private T previous;
    private T current;
    private Moment currentAt;
    private Dur span = Dur.ZERO;

    Sampled(Supplier<T> source, Interp<T> interp) {
        this.source = source;
        this.interp = interp;
    }

    /** Called by the producing domain at the end of each of its steps. */
    void commit(Moment at, Dur period) {
        T value = source.get();
        if (currentAt == null) {
            previous = value;
        } else {
            previous = current;
        }
        current = value;
        currentAt = at;
        span = period;
    }

    /** Whether anything has been committed yet. */
    public boolean isReady() {
        return currentAt != null;
    }

    /**
     * The value as of one step before {@code at}, interpolated.
     *
     * @throws IllegalStateException if the producing domain has not run a step yet
     */
    public T at(Moment at) {
        if (currentAt == null) {
            throw new IllegalStateException("nothing committed yet: the producing domain has not stepped");
        }
        if (span.isZero()) {
            return current;
        }
        float alpha = (float) (at.since(currentAt).nanos() / (double) span.nanos());
        alpha = Math.clamp(alpha, 0f, 1f);
        return interp.between(previous, current, alpha);
    }

    /** The most recently committed value, with no interpolation and no lag. */
    public T latest() {
        if (currentAt == null) {
            throw new IllegalStateException("nothing committed yet");
        }
        return current;
    }
}
