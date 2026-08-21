package sibarum.kronometer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local time context: a region of the program with its own rate of time.
 *
 * <p>Everything nests, everything is local, and everything can be scaled — including time. But
 * <b>real time is never bent</b>. The root tempo is locked to the wall, and what a scale changes is the
 * <em>actual rate</em> at which a region's declared work meets it.
 *
 * <pre>{@code
 * Tempo world   = kron.tempo();                  // root: 1:1, wall-locked
 * Tempo bullet  = world.child(Ratio.of(1, 4));   // quarter speed
 * Rate  physics = bullet.fixed(hz(50));          // declared 50 Hz; actual 12.5 Hz against the wall
 * }</pre>
 *
 * <p>The canonical case falls out without special-casing: a dynamic domain is tick-driven and therefore
 * wall-locked, so the picture keeps rendering at 60 fps, while a fixed domain inside the slow tempo
 * steps four times less often and each step still advances 20 ms of local simulation time. That is slow
 * motion, as arithmetic.
 *
 * <h2>Moments are global; durations are local</h2>
 *
 * The one decision that keeps this tractable. A frame-relative {@link Moment} would be a bare
 * nanosecond count with no way to tell which frame it belonged to, so moments from different frames
 * would silently compare wrongly. Instead {@link Time#now()} is always global and a tempo converts
 * <em>declared durations and grids</em>. {@code advance(ms(250))} inside a 1:4 tempo advances global
 * time by one second, and that is the only place the scale is applied.
 *
 * <h2>Slip stays at the root</h2>
 *
 * A nested tempo cannot slip independently, for the same reason a {@link Rate} cannot (§6.0): that
 * would be a second clock. Scaling reparameterizes one timeline; it does not add another.
 */
public final class Tempo {

    private final Kron kron;
    private final String name;
    private final Tempo parent;
    private final List<Tempo> children = new CopyOnWriteArrayList<>();

    /** Scale relative to the parent: local time per parent time. */
    private Ratio scale;
    /** Scale relative to the root, cached: {@code parent.effective × scale}. */
    private Ratio effective;
    /** The global moment at which this tempo's local clock read {@link #localAtOrigin}. */
    private Moment globalOrigin;
    /** Local elapsed time at {@link #globalOrigin}. */
    private Dur localAtOrigin;

    Tempo(Kron kron, String name, Tempo parent, Ratio scale, Moment globalOrigin) {
        this.kron = kron;
        this.name = name;
        this.parent = parent;
        this.scale = scale;
        this.effective = parent == null ? scale : parent.effective.times(scale);
        this.globalOrigin = globalOrigin;
        this.localAtOrigin = Dur.ZERO;
    }

    // ------------------------------------------------------------------ API

    public String name() {
        return name;
    }

    public Tempo parent() {
        return parent;
    }

    public List<Tempo> children() {
        return List.copyOf(children);
    }

    public boolean isRoot() {
        return parent == null;
    }

    /** This tempo's rate relative to its parent. */
    public Ratio scale() {
        return scale;
    }

    /** This tempo's rate relative to the root — the product of every scale on the way up. */
    public Ratio effectiveScale() {
        return effective;
    }

    /** A nested tempo, running at {@code scale} relative to this one, starting now. */
    public Tempo child(Ratio scale) {
        return child("tempo(" + scale + ")", scale);
    }

    public Tempo child(String name, Ratio scale) {
        Objects.requireNonNull(scale, "scale");
        Tempo t = new Tempo(kron, name, this, scale, kron.now());
        children.add(t);
        kron.registerTempo(t);
        return t;
    }

    /** How much local time has elapsed in this tempo as of the current moment. */
    public Dur elapsed() {
        return elapsedAt(kron.now());
    }

    /** How much local time has elapsed in this tempo as of {@code global}. */
    public Dur elapsedAt(Moment global) {
        return localAtOrigin.plus(effective.scale(global.since(globalOrigin)));
    }

    /** The global moment at which this tempo's local clock reads {@code localElapsed}. */
    public Moment globalAt(Dur localElapsed) {
        Dur fromOrigin = localElapsed.minus(localAtOrigin);
        return globalOrigin.plus(effective.reciprocal().scale(fromOrigin));
    }

    /** A local duration, expressed in global time. */
    public Dur toGlobal(Dur local) {
        return effective.reciprocal().scale(local);
    }

    /** A global duration, expressed in this tempo's local time. */
    public Dur toLocal(Dur global) {
        return effective.scale(global);
    }

    /**
     * Change this tempo's rate from now on.
     *
     * <p>Local time is continuous across the change — the subtree is rebased on the current moment, so
     * nothing jumps — but everything <em>predicted</em> beyond now in this subtree is retracted, exactly
     * as an unscheduled {@link Cell} write is. That is the whole reason a tempo is a graph node.
     */
    public void rescale(Ratio newScale) {
        Objects.requireNonNull(newScale, "newScale");
        if (newScale.equals(scale)) {
            return;
        }
        kron.requireOnTimeline("rescale()");
        Moment at = kron.now();
        rebaseSubtree(at);
        this.scale = newScale;
        recomputeEffective();
        kron.graph().invalidate(at);
    }

    /**
     * A fixed-rate domain whose period is measured in <em>this tempo's</em> local time.
     *
     * <p>So a 50 Hz domain in a 1:4 tempo declares 50 local Hz and delivers 12.5 Hz against the wall.
     */
    public Rate fixed(Dur period) {
        return kron.fixedIn(this, "fixed@" + period + "/" + name, period);
    }

    public Rate fixed(String name, Dur period) {
        return kron.fixedIn(this, name, period);
    }

    /**
     * How far ahead this tempo's local-to-global mapping is knowable.
     *
     * <p>{@link Dur#FOREVER} for a constant scale, since the map is then affine and exact forever. A
     * discrete rescale retracts predictions when it happens rather than being foreseen, so it does not
     * shorten the horizon in advance; an <em>animated</em> scale would, and that arrives in M5 along
     * with the integration it requires.
     */
    public Moment horizon() {
        return Moment.FOREVER;
    }

    @Override
    public String toString() {
        return "Tempo(" + name + ", " + scale + " local, " + effective + " global)";
    }

    // -------------------------------------------------------------- internals

    private void rebaseSubtree(Moment at) {
        Dur local = elapsedAt(at);
        for (Tempo child : children) {
            child.rebaseSubtree(at);
        }
        this.localAtOrigin = local;
        this.globalOrigin = at;
    }

    private void recomputeEffective() {
        this.effective = parent == null ? scale : parent.effective.times(scale);
        for (Tempo child : children) {
            child.recomputeEffective();
        }
    }
}
