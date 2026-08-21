package sibarum.kronometer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A side-effecting reader of the graph. The one kind of node that runs at {@code now} and never ahead.
 *
 * <p>Two ways to schedule one, because "changed" means two different things in a graph where time is a
 * dependency:
 *
 * <ul>
 *   <li><b>On a rate</b> — {@code kron.effect(frames, body)} runs once per step of that domain. This is
 *       the right form for anything continuous: a value that varies with time changes at every moment,
 *       so what you actually want is to sample it on a grid.</li>
 *   <li><b>On invalidation</b> — {@code kron.effect(body)} runs now, and again whenever something it
 *       read is contradicted. The right form for discrete change.</li>
 * </ul>
 *
 * <p>An effect is where the predictable world hands off to the effectful one, which is why it is the
 * only graph node bound by the baton.
 */
public final class Effect {

    private final Kron kron;
    private final Graph graph;
    private final String name;
    private final Runnable body;
    private final boolean reactive;

    private Set<Signal<?>> dependencies = new LinkedHashSet<>();
    private Shred shred;
    private boolean rerunPending;
    private boolean cancelled;
    private long runs;

    Effect(Kron kron, String name, Runnable body, boolean reactive) {
        this.kron = kron;
        this.graph = kron.graph();
        this.name = name;
        this.body = body;
        this.reactive = reactive;
    }

    public String name() {
        return name;
    }

    /** How many times this effect has run. */
    public long runs() {
        return runs;
    }

    /** What it read the last time it ran. */
    public Set<Signal<?>> dependencies() {
        return Set.copyOf(dependencies);
    }

    public void cancel() {
        cancelled = true;
        graph.unregisterReactive(this);
        if (shred != null) {
            shred.cancel();
        }
    }

    @Override
    public String toString() {
        return "Effect(" + name + ", " + runs + " runs)";
    }

    // -------------------------------------------------------------- internals

    /** Run the body, recording what it reads so invalidation knows whether it cares. */
    void run() {
        if (cancelled) {
            return;
        }
        Graph.Evaluation<Void> evaluation = graph.evaluate(kron.now(), () -> {
            body.run();
            return null;
        });
        dependencies = evaluation.dependencies();
        runs++;
    }

    /**
     * Queue a re-run at {@code at}, deduplicated.
     *
     * <p>Deduplication matters: an effect that writes a cell would otherwise invalidate itself into an
     * unbounded cascade within one moment. One pending re-run per effect means a cascade converges.
     */
    void scheduleRerun(Moment at) {
        if (cancelled || !reactive || rerunPending) {
            return;
        }
        rerunPending = true;
        kron.post(at, () -> {
            rerunPending = false;
            run();
        });
    }

    void bindShred(Shred shred) {
        this.shred = shred;
    }
}
