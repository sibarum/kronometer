package sibarum.kronometer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The signal graph's bookkeeping: dependency collection, versioning, and invalidation.
 *
 * <p>Package-private on purpose. Nothing here is a user-facing concept — {@link Signal},
 * {@link Cell} and {@link Curve} are.
 *
 * <h2>Why memoizing on (moment, version) is enough for glitch-freedom</h2>
 *
 * Vue and friends need a scheduler to avoid showing half-updated state. This does not, and the reason
 * is the baton: every read happens inside some shred's zero-time segment, during which {@code now} is
 * constant and nothing else can run. So a derived value cached against {@code (moment, version)} is
 * either valid for the whole segment or recomputed once at the start of it. Diamonds evaluate once. No
 * intermediate state is observable because there is no interleaving in which to observe it.
 *
 * <h2>Why prediction may be optimistic</h2>
 *
 * Invalidation is retroactive from the moment of the change, and — the load-bearing part —
 * <b>effects never run ahead of {@code now}; only values are computed ahead.</b> So a prediction that
 * turns out wrong is always discarded before any effect could have acted on it. That is what licenses
 * a merely-held {@link Cell} to be predicted as constant rather than treated as unknowable: being
 * wrong costs recomputation, never correctness.
 *
 * <h2>Why evaluation state is thread-confined</h2>
 *
 * M4 kept the collection deques on the graph itself, which was fine while only the timeline evaluated.
 * Precomputation evaluates the same signals on a pool, so shared deques would be a data race in the one
 * place this design promised there could not be one. Each thread now carries its own {@link Frame}.
 */
final class Graph {

    private final Kron kron;

    /** Bumped by every invalidation. Part of every memoization key. */
    private long version;

    /** Effects that re-run when something they read changes, rather than on a rate. */
    private final List<Effect> reactive = new ArrayList<>();

    /**
     * One thread's evaluation state.
     *
     * <p>{@code uncached} marks a precompute evaluation, which must not write to any signal's shared
     * memo — the memo belongs to the timeline and to {@code now}. It gets a frame-local memo instead,
     * so a diamond still evaluates its apex once per moment even off the timeline.
     */
    private static final class Frame {
        final Deque<Set<Signal<?>>> collecting = new ArrayDeque<>();
        final Deque<Moment> evaluatingAt = new ArrayDeque<>();
        final Map<Signal<?>, Object> localMemo = new IdentityHashMap<>();
        int uncachedDepth;
    }

    private final ThreadLocal<Frame> frames = ThreadLocal.withInitial(Frame::new);

    Graph(Kron kron) {
        this.kron = kron;
    }

    long version() {
        return version;
    }

    Kron kron() {
        return kron;
    }

    /** The moment evaluation is happening for; {@code now} when nothing is being evaluated. */
    Moment evaluatingAt() {
        Moment at = frames.get().evaluatingAt.peek();
        return at == null ? kron.now() : at;
    }

    /** Whether this thread is evaluating ahead, and so must not touch shared memos. */
    boolean isUncached() {
        return frames.get().uncachedDepth > 0;
    }

    /** Register that the value being evaluated read {@code source}. */
    void observe(Signal<?> source) {
        Set<Signal<?>> deps = frames.get().collecting.peek();
        if (deps != null) {
            deps.add(source);
        }
    }

    /** Evaluate {@code body} at {@code at}, collecting what it reads. For the timeline. */
    <T> Evaluation<T> evaluate(Moment at, Supplier<T> body) {
        Frame frame = frames.get();
        Set<Signal<?>> deps = new LinkedHashSet<>();
        frame.collecting.push(deps);
        frame.evaluatingAt.push(at);
        try {
            return new Evaluation<>(body.get(), deps);
        } finally {
            frame.evaluatingAt.pop();
            frame.collecting.pop();
        }
    }

    /**
     * Evaluate {@code body} at {@code at} without writing to any shared memo. For the precompute pool.
     *
     * <p>The frame-local memo is cleared at the top of each such evaluation, so it scopes to exactly
     * one moment — which is what makes it safe to key by identity alone.
     */
    <T> T evaluateAhead(Moment at, Supplier<T> body) {
        Frame frame = frames.get();
        boolean outermost = frame.uncachedDepth == 0;
        if (outermost) {
            frame.localMemo.clear();
        }
        frame.uncachedDepth++;
        frame.evaluatingAt.push(at);
        try {
            return body.get();
        } finally {
            frame.evaluatingAt.pop();
            frame.uncachedDepth--;
            if (outermost) {
                frame.localMemo.clear();
            }
        }
    }

    /** The frame-local memo for an off-timeline evaluation. */
    @SuppressWarnings("unchecked")
    <T> T localMemo(Signal<T> signal, Supplier<T> compute) {
        Map<Signal<?>, Object> memo = frames.get().localMemo;
        if (memo.containsKey(signal)) {
            return (T) memo.get(signal);
        }
        T value = compute.get();
        memo.put(signal, value);
        return value;
    }

    record Evaluation<T>(T value, Set<Signal<?>> dependencies) { }

    /**
     * Something changed that contradicts what was predicted after {@code at}.
     *
     * <p>Bumps the version, which retracts every cached value, discards predictions later than
     * {@code at}, and wakes the reactive effects. Strictly <em>after</em> {@code at}: a value already
     * delivered for an earlier moment was correct when it was delivered, and rewriting history is
     * neither possible nor desirable.
     */
    /** A change whose origin is not a single signal — a tempo rescale. Retracts everything. */
    void invalidate(Moment at) {
        invalidate(at, null);
    }

    /**
     * Something changed at {@code at}, and {@code source} is where.
     *
     * <p>The source matters, and M7 is where finding that out became unavoidable. The version bump has
     * to be global — it is one counter, and a memo is per-moment anyway, so recomputing at the current
     * moment is cheap. But <b>discarding buffers</b> globally is not cheap, and the demo made that
     * obvious: live input arriving every frame threw away the predicted future of an animation that had
     * nothing to do with it, leaving 72 % waste on a signal that was genuinely invalidated once. Live
     * input and precomputation would have undermined each other in exactly the application that wants
     * both.
     *
     * <p>So a targeted invalidation only retracts predictions that actually <em>depend</em> on the
     * source. Over-discarding was never a correctness bug — it is safe to throw away something true —
     * which is why it survived M5's property test and needed a realistic scenario to surface.
     */
    void invalidate(Moment at, Signal<?> source) {
        version++;
        kron.discardPredictionsAfter(at, source);
        for (Effect effect : List.copyOf(reactive)) {
            effect.scheduleRerun(at);
        }
    }

    void registerReactive(Effect effect) {
        reactive.add(effect);
    }

    void unregisterReactive(Effect effect) {
        reactive.remove(effect);
    }

    /** The narrowest horizon among {@code sources} — the min, as §7.1 requires. */
    static Moment narrowest(Set<Signal<?>> sources) {
        Moment narrowest = Moment.FOREVER;
        for (Signal<?> source : sources) {
            Moment h = source.horizon();
            if (h.isBefore(narrowest)) {
                narrowest = h;
            }
        }
        return narrowest;
    }
}
