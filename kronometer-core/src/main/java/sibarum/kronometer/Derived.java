package sibarum.kronometer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A value computed from other signals, memoized per moment and per graph version.
 *
 * <p>Its dependencies are whatever it read the last time it ran, and its horizon is the narrowest of
 * theirs — which is how the classification in §7.1 propagates without anyone declaring it. A diamond
 * evaluates once; a deep chain folds to the minimum.
 */
final class Derived<T> implements Signal<T> {

    private final Graph graph;
    private final String name;
    private final Supplier<T> body;

    private Set<Signal<?>> dependencies = new LinkedHashSet<>();
    private Moment cachedAt;
    private long cachedVersion = -1;
    private T cachedValue;

    Derived(Graph graph, String name, Supplier<T> body) {
        this.graph = graph;
        this.name = name;
        this.body = body;
    }

    @Override
    public T get() {
        return at(graph.evaluatingAt());
    }

    @Override
    public T at(Moment at) {
        graph.observe(this);
        if (graph.isUncached()) {
            // Evaluating ahead, on a pool thread. The shared memo belongs to the timeline and to
            // `now`; writing it from here would be the data race M4 warned about. A frame-local memo
            // keeps the diamond property without touching anything shared.
            return graph.localMemo(this, () -> graph.evaluateAhead(at, body));
        }
        if (cachedAt != null && cachedAt.equals(at) && cachedVersion == graph.version()) {
            return cachedValue;
        }
        Graph.Evaluation<T> evaluation = graph.evaluate(at, body);
        dependencies = evaluation.dependencies();
        cachedValue = evaluation.value();
        cachedAt = at;
        cachedVersion = graph.version();
        return cachedValue;
    }

    /**
     * Seed the memo with a value computed ahead of time.
     *
     * <p>This is how precomputation stays invisible: the buffered value enters by the same door
     * evaluation would have used, so an effect reading {@code get()} cannot tell the difference. It also
     * means the property test in {@code PrecomputeTest} is testing something real — if a body is impure,
     * the primed value and the evaluated one diverge and the test says so.
     */
    void prime(Moment at, long version, T value) {
        this.cachedAt = at;
        this.cachedVersion = version;
        this.cachedValue = value;
    }

    @Override
    public Moment horizon() {
        ensureEvaluated();
        return Graph.narrowest(dependencies);
    }

    /**
     * Varies for as long as <em>any</em> input does — the maximum, where the horizon is the minimum.
     *
     * <p>The asymmetry is not an oversight. A value is determined only as far as its least-known input,
     * but it keeps changing as long as its longest-running input does.
     */
    @Override
    public Moment varyingUntil() {
        ensureEvaluated();
        Moment longest = Moment.ORIGIN;
        for (Signal<?> source : dependencies) {
            Moment varying = source.varyingUntil();
            if (varying.isAfter(longest)) {
                longest = varying;
            }
        }
        return longest;
    }

    private void ensureEvaluated() {
        if (cachedAt == null) {
            get();          // dependencies are only known once it has run
        }
    }

    /** What this value read the last time it ran. */
    Set<Signal<?>> dependencies() {
        return Set.copyOf(dependencies);
    }

    /** How many times this has actually evaluated, for the no-double-evaluation tests. */
    @Override
    public String toString() {
        return "computed(" + name + ")";
    }
}
