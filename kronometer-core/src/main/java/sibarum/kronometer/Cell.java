package sibarum.kronometer;

import java.util.Objects;

/**
 * A mutable source in the graph — and the place where the horizon model earns its keep.
 *
 * <p>A cell has four modes, and each one says something different about how much of its future is
 * knowable. That is the whole mechanism: nothing downstream of a cell changes when the mode does, but
 * the entire subgraph reclassifies itself between predictable and not.
 *
 * <table border="1">
 * <caption>Cell modes and their horizons</caption>
 * <tr><th>Mode</th><th>Set by</th><th>Horizon</th></tr>
 * <tr><td><b>held</b> (default)</td><td>{@link #set}</td><td>{@link Moment#FOREVER} — see below</td></tr>
 * <tr><td><b>live</b></td><td>{@link #live()}</td><td>{@code now} — declared unpredictable</td></tr>
 * <tr><td><b>driven</b></td><td>{@link #drive}</td><td>where the curve ends</td></tr>
 * <tr><td><b>following</b></td><td>{@link #follow}</td><td>whatever the source's is</td></tr>
 * </table>
 *
 * <h2>Why a held cell is predicted as constant</h2>
 *
 * §7.1 originally specified {@code now} for a cell with no scheduled writes — the conservative reading,
 * on the grounds that nobody can know whether it is about to be written. Implementing invalidation
 * showed the conservatism is unnecessary, and unnecessary conservatism here would mean nothing
 * downstream of any cell is ever precomputed.
 *
 * <p>Optimism is sound because <b>effects never run ahead of {@code now}; only values are computed
 * ahead.</b> A write invalidates every prediction after its own moment, and that happens before any
 * effect could have acted on one. So being wrong costs recomputation, never correctness — and the cost
 * is measurable, which is what §7.3's prediction-waste metering and automatic demotion are for.
 *
 * <p>{@link #live()} is the explicit opt-out, for a cell fed from outside at unpredictable times — an
 * input adapter, a network message. It is a promise about volatility, not about the value.
 */
public final class Cell<T> implements Signal<T> {

    private enum Mode { HELD, LIVE, DRIVEN, FOLLOWING }

    private final Kron kron;
    private final Graph graph;
    private final String name;
    private final Tempo tempo;

    private Mode mode = Mode.HELD;
    private T held;

    private Curve<T> curve;
    private Dur curveStartLocal;

    private Signal<T> source;

    Cell(Kron kron, String name, Tempo tempo, T initial) {
        this.kron = kron;
        this.graph = kron.graph();
        this.name = name;
        this.tempo = tempo;
        this.held = initial;
    }

    // ------------------------------------------------------------------ API

    public String name() {
        return name;
    }

    public Tempo tempo() {
        return tempo;
    }

    /**
     * Write a value now, discarding any curve or source.
     *
     * <p>An unscheduled write: it invalidates everything predicted <em>after</em> this moment, and
     * nothing at or before it.
     */
    public void set(T value) {
        kron.requireOnTimeline("Cell.set()");
        this.held = value;
        // A write does not un-declare volatility. `live()` is a promise about *how this cell behaves*,
        // and writing to it is precisely the behaviour promised — so an input adapter that calls set()
        // per event stays unpredictable, rather than becoming predictable on its first event, which is
        // backwards and was the second bug M5's property test found.
        if (mode != Mode.LIVE) {
            this.mode = Mode.HELD;
        }
        this.curve = null;
        this.source = null;
        graph.invalidate(kron.now(), this);
    }

    /**
     * Declare that this cell is written from outside at unpredictable times, so none of its future may
     * be assumed.
     *
     * <p>Collapses this cell's horizon — and therefore the horizon of everything derived from it — to
     * {@code now}. What an input adapter calls.
     */
    public Cell<T> live() {
        this.mode = Mode.LIVE;
        this.curve = null;
        this.source = null;
        graph.invalidate(kron.now(), this);
        return this;
    }

    /**
     * Schedule this cell's future: it takes its value from {@code curve}, anchored at the current
     * moment.
     *
     * <p>The curve's elapsed time is measured in this cell's tempo, so a 200 ms curve in a 1:4 tempo
     * takes 800 ms of wall time. Once the curve ends the cell holds its final value.
     */
    public Cell<T> drive(Curve<T> curve) {
        Objects.requireNonNull(curve, "curve");
        kron.requireOnTimeline("Cell.drive()");
        Moment now = kron.now();
        this.curve = curve;
        this.curveStartLocal = tempo.elapsedAt(now);
        this.mode = Mode.DRIVEN;
        this.source = null;
        graph.invalidate(now, this);
        return this;
    }

    /** Mirror another signal, inheriting its horizon exactly. */
    public Cell<T> follow(Signal<T> source) {
        Objects.requireNonNull(source, "source");
        kron.requireOnTimeline("Cell.follow()");
        this.source = source;
        this.mode = Mode.FOLLOWING;
        this.curve = null;
        graph.invalidate(kron.now(), this);
        return this;
    }

    /** Stop being driven or following; hold the current value from here on. */
    public Cell<T> release() {
        if (mode == Mode.DRIVEN || mode == Mode.FOLLOWING) {
            this.held = at(kron.now());
        }
        this.mode = Mode.HELD;
        this.curve = null;
        this.source = null;
        graph.invalidate(kron.now(), this);
        return this;
    }

    /** The signal this cell mirrors, if it is following one. */
    Signal<T> followedSource() {
        return mode == Mode.FOLLOWING ? source : null;
    }

    /** Whether this cell has been declared volatile by {@link #live()}. */
    public boolean isLive() {
        return mode == Mode.LIVE;
    }

    /** Whether this cell's future is currently determined by a curve that has not yet ended. */
    public boolean isDriven() {
        return mode == Mode.DRIVEN && curveEnd().isAfter(kron.now());
    }

    @Override
    public T get() {
        return at(graph.evaluatingAt());
    }

    @Override
    public T at(Moment at) {
        graph.observe(this);
        return switch (mode) {
            case HELD, LIVE -> held;
            case FOLLOWING -> source.at(at);
            case DRIVEN -> valueFromCurve(at);
        };
    }

    /**
     * How far this cell's value is <b>determined</b>.
     *
     * <p>A driven cell is determined as far as its tempo is, not merely until its curve ends: the curve
     * covers the next stretch and the final value holds after it. Only declared volatility bounds this,
     * the cell's own {@link #live()} or its tempo's {@link Tempo#live()}, which is the point: prediction
     * is blocked by volatility, not by an animation finishing.
     */
    @Override
    public Moment horizon() {
        return switch (mode) {
            case HELD -> Moment.FOREVER;
            // A curve is read through its tempo's map, so it is known only as far as the map is. Once the
            // curve has ended the value is its last one whatever the tempo does, since the map is
            // continuous at now and an end already passed cannot move.
            case DRIVEN -> curveEnd().isAfter(kron.now()) ? tempo.horizon() : Moment.FOREVER;
            case LIVE -> kron.now();
            case FOLLOWING -> source.horizon();
        };
    }

    /** How far this cell's value is still <b>changing</b> — where a driven curve ends. */
    @Override
    public Moment varyingUntil() {
        return switch (mode) {
            case HELD, LIVE -> kron.now();
            case DRIVEN -> curveEnd();
            case FOLLOWING -> source.varyingUntil();
        };
    }

    @Override
    public String toString() {
        return "Cell(" + name + ", " + mode + ", horizon " + renderHorizon() + ")";
    }

    // -------------------------------------------------------------- internals

    /**
     * Where the driving curve ends, in global time.
     *
     * <p>Derived through the tempo on every call rather than stored at {@link #drive}: the curve's end is
     * fixed in local time, and a {@link Tempo#rescale} moves where that lands on the wall. A stored end
     * went stale at the rescale, and prediction stored a constant tail from it while the curve was still
     * moving.
     */
    private Moment curveEnd() {
        return curve.isInfinite()
                ? Moment.FOREVER
                : tempo.globalAt(curveStartLocal.plus(curve.extent()));
    }

    private T valueFromCurve(Moment at) {
        Dur elapsed = tempo.elapsedAt(at).minus(curveStartLocal);
        if (elapsed.isNegative()) {
            elapsed = Dur.ZERO;
        }
        if (!curve.isInfinite() && elapsed.compareTo(curve.extent()) > 0) {
            elapsed = curve.extent();
        }
        return curve.at(elapsed);
    }

    private String renderHorizon() {
        Moment h = horizon();
        return h.equals(Moment.FOREVER) ? "forever" : h.toString();
    }
}
