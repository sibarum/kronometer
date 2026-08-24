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
    private Runnable detach;
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

    /**
     * Stop this effect. Idempotent, and scoped to <em>this</em> effect: cancelling one effect on a rate
     * domain leaves every other handler on that domain running.
     *
     * <p>That scoping is the whole reason {@link #detach} exists rather than a bound {@link Shred}. A
     * rate domain runs all its handlers on one shred — {@code Rate.each} says so, and returns the same
     * one to every caller — so an effect that cancelled its shred would stop the domain itself, and with
     * it every other effect, every bound signal, and every future effect anybody registers there. The
     * failure is silent and it is delayed: the first animation on a domain works perfectly and cancels
     * cleanly on arrival, and every animation after it is dead. Detaching the handler is the operation
     * that was actually meant.
     */
    public void cancel() {
        cancelled = true;
        graph.unregisterReactive(this);
        if (detach != null) {
            detach.run();
            detach = null;
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

    /** How to unregister this effect from wherever it was scheduled, run once by {@link #cancel}. */
    void bindDetach(Runnable detach) {
        this.detach = detach;
    }
}
