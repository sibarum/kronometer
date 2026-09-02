package sibarum.kronometer;

import java.util.LinkedHashSet;
import java.util.Set;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;

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
     * The last moment at which anything this effect read is still changing — {@link Moment#ORIGIN} if
     * nothing is, which is the identity for a maximum.
     *
     * <p>The per-effect half of {@link Kron#isQuiescent()}, and it has to answer for an effect that has
     * <em>never run</em> as well. Such an effect has no recorded dependencies, so summing them would
     * report nothing varying — and a host that went to sleep on that answer would strand the effect
     * before its first run, which is exactly when it would have registered the dependencies that keep
     * it awake. So a never-run effect is busy until it has run once: it owes a step and it must get it.
     */
    Moment varyingUntil() {
        if (runs == 0) {
            return Moment.FOREVER;
        }
        Moment latest = Moment.ORIGIN;
        for (Signal<?> source : dependencies) {
            Moment varying = source.varyingUntil();
            if (varying.isAfter(latest)) {
                latest = varying;
            }
        }
        return latest;
    }

    /** Whether {@link #cancel} has been called. */
    boolean isCancelled() {
        return cancelled;
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
        graph.forgetEffect(this);
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
        // Named by the effect, so the rollup names the animation that is costing the frame rather than
        // reporting one undifferentiated total for "effects". An effect's name is a constant string it
        // already holds, so using it as the span name allocates nothing.
        try (Zone z = Probe.zone(Lane.ANIM, name)) {
            Graph.Evaluation<Void> evaluation = graph.evaluate(kron.now(), () -> {
                body.run();
                return null;
            });
            dependencies = evaluation.dependencies();
            runs++;
        }
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
