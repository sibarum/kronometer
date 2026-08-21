package sibarum.kronometer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 */
final class Graph {

    private final Kron kron;

    /** Bumped by every invalidation. Part of every memoization key. */
    private long version;

    /** The dependency sets being collected, innermost last. Non-empty only during evaluation. */
    private final Deque<Set<Signal<?>>> collecting = new ArrayDeque<>();

    /** The moment being evaluated for, which is not always {@code now} — precomputation asks about later. */
    private final Deque<Moment> evaluatingAt = new ArrayDeque<>();

    /** Effects that re-run when something they read changes, rather than on a rate. */
    private final List<Effect> reactive = new ArrayList<>();

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
        Moment at = evaluatingAt.peek();
        return at == null ? kron.now() : at;
    }

    /** Register that the value being evaluated read {@code source}. */
    void observe(Signal<?> source) {
        Set<Signal<?>> deps = collecting.peek();
        if (deps != null) {
            deps.add(source);
        }
    }

    /** Evaluate {@code body} at {@code at}, collecting what it reads. */
    <T> Evaluation<T> evaluate(Moment at, java.util.function.Supplier<T> body) {
        Set<Signal<?>> deps = new LinkedHashSet<>();
        collecting.push(deps);
        evaluatingAt.push(at);
        try {
            return new Evaluation<>(body.get(), deps);
        } finally {
            evaluatingAt.pop();
            collecting.pop();
        }
    }

    record Evaluation<T>(T value, Set<Signal<?>> dependencies) { }

    /**
     * Something changed that contradicts what was predicted after {@code at}.
     *
     * <p>Bumps the version, which retracts every cached value, and wakes the reactive effects. Strictly
     * <em>after</em> {@code at}: a value already delivered for an earlier moment was correct when it was
     * delivered, and rewriting history is neither possible nor desirable.
     */
    void invalidate(Moment at) {
        version++;
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
