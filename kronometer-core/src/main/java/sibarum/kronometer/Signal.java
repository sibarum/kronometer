package sibarum.kronometer;

import java.util.function.Function;

/**
 * A value that varies with time, and knows how far ahead it is knowable.
 *
 * <p>Vue's model, with time as a dependency. {@link Cell} is a source, {@link Curve} is a pure function
 * of time, {@code kron.computed(...)} derives one from others, and dependencies are tracked by
 * <em>reading</em> — calling {@link #get()} inside a computed or an effect registers the edge, with no
 * wiring, no annotations and no reflection.
 *
 * <h2>The horizon</h2>
 *
 * {@link #horizon()} is the number the whole design turns on: the last moment at which this value is
 * already determined. It lets the graph classify itself, so nobody has to declare which world a value
 * lives in:
 *
 * <pre>{@code
 * lift.live();                                       // horizon = now — unpredictable
 * lift.drive(Curve.ramp(0f, 1f, ms(200)));           // horizon = now + 200 ms — knowable
 * }</pre>
 *
 * Nothing downstream changes. The subgraph reclassified because its input did.
 */
public interface Signal<T> {

    /** The value at the current moment. */
    T get();

    /**
     * The value at {@code at}, which must be no earlier than now and no later than {@link #horizon()}.
     *
     * <p>This is the method precomputation will call (M5); it exists now so that prediction is an
     * optimization over an already-correct API rather than a parallel one.
     *
     * @throws IllegalArgumentException if {@code at} is in the past or beyond the horizon
     */
    T at(Moment at);

    /**
     * The last moment at which this value is already <b>determined</b> — {@link Moment#FOREVER} if its
     * whole future is. What prediction may rely on.
     */
    Moment horizon();

    /**
     * The last moment at which this value is still <b>changing</b>. Constant from here on.
     *
     * <p>Distinct from {@link #horizon()}, and M4 found out the hard way that one number cannot do both
     * jobs. A cell driven by a 200 ms curve is *determined* forever — the curve covers the next 200 ms
     * and it holds its final value after that — but it only *varies* for 200 ms. Prediction needs the
     * first number to know how far ahead it may compute; it needs the second to know when to stop
     * sampling and store a single constant instead of a thousand identical ones.
     *
     * <p>Defaults to {@link #horizon()}, which is correct for anything that varies for as long as it is
     * known — time itself, an endless oscillator.
     */
    default Moment varyingUntil() {
        return horizon();
    }

    /**
     * Whether any of this value's future is knowable as of {@code now} — that is, whether there is a
     * gap between now and the horizon for prediction to fill.
     */
    default boolean isPredictableAt(Moment now) {
        return horizon().isAfter(now);
    }

    /** A derived signal. Predictable exactly as far as this one is. */
    default <R> Signal<R> map(Function<T, R> fn) {
        Signal<T> self = this;
        return new Signal<>() {
            @Override
            public R get() {
                return fn.apply(self.get());
            }

            @Override
            public R at(Moment at) {
                return fn.apply(self.at(at));
            }

            @Override
            public Moment horizon() {
                return self.horizon();
            }

            @Override
            public Moment varyingUntil() {
                return self.varyingUntil();
            }
        };
    }

    /** A value that never changes, and is therefore knowable forever. */
    static <T> Signal<T> constant(T value) {
        return new Signal<>() {
            @Override
            public T get() {
                return value;
            }

            @Override
            public T at(Moment at) {
                return value;
            }

            @Override
            public Moment horizon() {
                return Moment.FOREVER;
            }

            @Override
            public Moment varyingUntil() {
                return Moment.ORIGIN;       // never varies at all
            }

            @Override
            public String toString() {
                return "constant(" + value + ")";
            }
        };
    }
}
