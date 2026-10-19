package sibarum.kronometer;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

    // The map from global time to local time has at most two pieces, and both are fixed when a segment
    // begins anywhere in this tempo's ancestry, so converting a future moment never depends on whether
    // anything has happened yet:
    //
    //   composed   before composedUntil: the parent's map, then this tempo's segment. Used while a glide
    //              is still running here or in any ancestor.
    //   flattened  from tailStart on: tailLocal + floor(effective × (g − tailStart)), one exact Ratio for
    //              the whole chain. The only piece when nothing in the chain is gliding, as before glides.
    //
    // composedUntil is the latest knot in the chain, so a glide ends without anything having to happen:
    // the flattened piece simply takes over at the knot.

    /** The current stretch of speed relative to the parent. */
    private Segment segment;
    /** Parent-local nanoseconds at which {@link #segment} starts. */
    private long segmentStart;
    /** Local nanoseconds added to the segment's advance: what keeps local time continuous across changes. */
    private long segmentOffset;

    /** Scale relative to the root once every glide in the chain has settled: the product of end speeds. */
    private Ratio effective;
    /** The end of the composed piece, or {@code null} when nothing in the chain is gliding. */
    private Moment composedUntil;
    /** Local time at the last nanosecond of the composed piece: fixed with the plan, so the inverse need not redo it. */
    private long composedLast;
    /** Where the flattened piece is anchored, in global time. */
    private Moment tailStart;
    /** Local elapsed time at {@link #tailStart}. */
    private Dur tailLocal;
    /** Where this tempo's own glide settles, in global time; {@code null} while it holds. */
    private Moment ownKnot;

    /** Declared volatile by {@link #live}: its speed changes at unpredictable times. */
    private boolean live;
    /** The signal this tempo's speed follows, or {@code null}. See {@link #follow}. */
    private Signal<Ratio> followed;
    /** The reactive effect that turns each change of {@link #followed} into a glide. */
    private Effect follower;
    /** The speed {@link #follower} last glided to, so a re-run that changes nothing does nothing. */
    private Ratio followTarget;
    /** {@link #time()}, made once so that reading it is a dependency on one signal, not a new one each time. */
    private Signal<Dur> timeSignal;

    Tempo(Kron kron, String name, Tempo parent, Ratio scale, Moment globalOrigin) {
        this.kron = kron;
        this.name = name;
        this.parent = parent;
        rebuild(globalOrigin, 0, new Segment.Hold(scale));
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

    /**
     * This tempo's rate relative to its parent. During a {@link #glide}, the rate it is gliding to: the
     * rate in between is exact but not, in general, a {@code Ratio} of {@code long}s.
     */
    public Ratio scale() {
        return switch (segment) {
            case Segment.Hold hold -> hold.speed();
            case Segment.Linear glide -> glide.endSpeed();
            case Segment.Exponential glide -> glide.endSpeed();
        };
    }

    /**
     * This tempo's rate relative to the root — the product of every scale on the way up, each one the
     * rate its tempo holds or is gliding to.
     */
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

    /**
     * This tempo's local clock, as a signal: {@link #elapsedAt} of whatever moment it is asked about.
     *
     * <p>What a consumer builds a phase on when it runs on one clock while something else runs on
     * another: a tone in a tempo that glides has the phase {@code f · time()}, and a pitch-bend is a glide
     * on that tempo, with the integral already done here.
     *
     * <p><b>It memoizes nothing, and that is required rather than incidental.</b> A reader that
     * interpolates across a block reads it at both ends of the block within one evaluation; a
     * {@code kron.computed} is memoized per evaluation by identity alone, so both reads would return the
     * first and the block would advance by zero. This is a plain signal, so asking twice at two moments
     * gives two answers. Reading it does register a dependency, so a computed over it inherits its
     * horizon, which is {@link #horizon()}: a phase in a live tempo is not computed ahead.
     */
    public Signal<Dur> time() {
        if (timeSignal == null) {
            timeSignal = new Signal<>() {
                @Override
                public Dur get() {
                    return at(kron.graph().evaluatingAt());
                }

                @Override
                public Dur at(Moment at) {
                    kron.graph().observe(this);
                    return elapsedAt(at);
                }

                @Override
                public Moment horizon() {
                    return Tempo.this.horizon();
                }

                @Override
                public String toString() {
                    return "time(" + name + ")";
                }
            };
        }
        return timeSignal;
    }

    /**
     * How much local time has elapsed in this tempo as of {@code global}, rounded down to the nanosecond:
     * a local instant counts as reached only once it has fully elapsed. See {@link #floorScaled}.
     */
    public Dur elapsedAt(Moment global) {
        if (composedUntil != null && global.isBefore(composedUntil)) {
            return new Dur(composed(global));
        }
        return tailLocal.plus(new Dur(floorScaled(effective, global.since(tailStart).nanos())));
    }

    /**
     * The global moment at which this tempo's local clock reads {@code localElapsed}: the <b>least</b>
     * moment {@code g} with {@code elapsedAt(g) >= localElapsed}.
     *
     * <p>Defined that way rather than as the rounded inverse, because the two directions must agree to
     * the nanosecond. A {@link Rate} places grid line {@code n} with this method and finds the line at or
     * before a moment with {@link #elapsedAt}; if the rounded inverse lands one nanosecond early, the line
     * reads as line {@code n − 1} and a step runs twice. With this definition both of these hold for every
     * input:
     *
     * <ul>
     *   <li>{@code elapsedAt(globalAt(L)) >= L}</li>
     *   <li>{@code globalAt(elapsedAt(g)) <= g}</li>
     * </ul>
     *
     * <p>Where the scale divides exactly, this is the exact moment, as before. Elsewhere it is the exact
     * moment rounded up to the next nanosecond, where the rounded inverse used to round to nearest.
     */
    public Moment globalAt(Dur localElapsed) {
        long target = localElapsed.nanos();
        if (composedUntil != null && composedLast >= target) {
            // Reached before the knot, so the least moment is the composed piece's own least moment.
            long parentTarget = Math.addExact(segmentStart, segment.leastReaching(target - segmentOffset));
            return parent == null ? new Moment(parentTarget) : parent.globalAt(new Dur(parentTarget));
        }
        Moment least = tailStart.plus(new Dur(leastReaching(effective, target - tailLocal.nanos())));
        return composedUntil != null && least.isBefore(composedUntil) ? composedUntil : least;
    }

    /**
     * A local duration, expressed in global time, at the scale this tempo holds or is gliding to. Exact
     * for a constant chain; during a glide, see {@link #globalAt} for moments.
     */
    public Dur toGlobal(Dur local) {
        return effective.reciprocal().scale(local);
    }

    /** A global duration, expressed in this tempo's local time, at the scale it holds or is gliding to. */
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
        stopFollowing();
        if (segment instanceof Segment.Hold hold && hold.speed().equals(newScale)) {
            return;
        }
        begin("rescale()", new Segment.Hold(newScale));
    }

    /**
     * Change this tempo's rate gradually, in a straight line: from whatever it is now to {@code to}, over
     * {@code over} of the <em>parent's</em> time, and then hold {@code to}. {@link Glide#LINEAR}.
     */
    public void glide(Ratio to, Dur over) {
        glide(to, over, Glide.LINEAR);
    }

    /**
     * Change this tempo's rate gradually: from whatever it is now to {@code to}, over {@code over} of the
     * <em>parent's</em> time, in the given {@code shape}, and then hold {@code to}.
     *
     * <p>Speed is continuous, so local time is continuous with a continuous rate: a glide that replaces
     * one in progress starts from the speed the old one had reached. Local time is the integral of that
     * speed, rounded down to the nanosecond, and once the glide ends the tempo converts through one exact
     * {@link Ratio} again, so grids that were commensurate line up again.
     *
     * <p>Like {@link #rescale}, this retracts everything predicted beyond now. The glide itself is pure, so
     * the subtree is predictable through all of it.
     */
    public void glide(Ratio to, Dur over, Glide shape) {
        stopFollowing();
        glideTo(to, over, shape);
    }

    /**
     * Declare that this tempo's speed is changed from outside at unpredictable times, so none of its
     * future may be assumed: a scrub control calling {@link #rescale} or {@link #glide} per event.
     *
     * <p>Collapses {@link #horizon()} to {@code now} for this tempo and every tempo nested in it, so
     * nothing whose value depends on their time is computed ahead. Like {@link Cell#live}, it is a promise
     * about behaviour: rescaling or gliding does not undo it. {@link #release} does.
     */
    public Tempo live() {
        kron.requireOnTimeline("Tempo.live()");
        stopFollowing();
        this.live = true;
        kron.graph().invalidate(kron.now());
        return this;
    }

    /**
     * Take this tempo's speed from {@code source}: each time it changes, glide to the new value over
     * {@code slew} of parent time, so a stepped input still gives a continuous speed.
     *
     * <p>A change is noticed when something invalidates the graph, which is what an input adapter's
     * {@link Cell#set} does; a source that varies continuously along a curve is not sampled between
     * invalidations. Use {@link #glide} for a speed that is known in advance.
     *
     * <p>{@link #horizon()} becomes the source's: forever for a source that is merely held, since a write
     * to it retracts what was predicted, and {@code now} for a {@linkplain Cell#live live} one. An explicit
     * {@link #rescale}, {@link #glide}, {@link #live} or {@link #release} stops following.
     */
    public Tempo follow(Signal<Ratio> source, Dur slew) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(slew, "slew");
        if (slew.nanos() <= 0) {
            throw new IllegalArgumentException("a follow needs a positive slew: " + slew);
        }
        kron.requireOnTimeline("Tempo.follow()");
        stopFollowing();
        this.live = false;
        this.followed = source;
        this.followTarget = null;
        this.follower = kron.effect("follow:" + name, () -> {
            Ratio target = source.get();
            if (!target.equals(followTarget)) {
                followTarget = target;
                glideTo(target, slew, Glide.LINEAR);
            }
        });
        return this;
    }

    /** Stop following and stop being live: the speed holds or glides as it is, and is predictable again. */
    public Tempo release() {
        kron.requireOnTimeline("Tempo.release()");
        boolean wasVolatile = live || followed != null;
        stopFollowing();
        this.live = false;
        if (wasVolatile) {
            kron.graph().invalidate(kron.now());
        }
        return this;
    }

    private void stopFollowing() {
        if (follower != null) {
            follower.cancel();
            follower = null;
        }
        followed = null;
        followTarget = null;
    }

    private void glideTo(Ratio to, Dur over, Glide shape) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(over, "over");
        Objects.requireNonNull(shape, "shape");
        if (over.nanos() <= 0) {
            throw new IllegalArgumentException("a glide needs a positive duration: " + over);
        }
        kron.requireOnTimeline("glide()");
        if (segment instanceof Segment.Hold hold && hold.speed().equals(to)) {
            return;                                     // already there: a glide would be a hold
        }
        BigInteger[] from = segment.speedAt(parentLocal(kron.now()) - segmentStart);
        begin("glide()", switch (shape) {
            case LINEAR -> new Segment.Linear(from[0], from[1], to, over.nanos());
            case EXPONENTIAL -> new Segment.Exponential(from[0], from[1], to, over.nanos());
        });
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
     * How far ahead this tempo's local-to-global map is <b>determined</b>: the minimum over this tempo and
     * every ancestor.
     *
     * <ul>
     *   <li>Holding or gliding: {@link Moment#FOREVER}. A glide is pure, and a later rescale or glide
     *       retracts what was predicted before any effect can act on it, so this is optimistic in the same
     *       sound way a held {@link Cell} is.</li>
     *   <li>{@linkplain #follow Following} a signal: that signal's horizon.</li>
     *   <li>{@linkplain #live Live}: {@code now}. You cannot render ahead through a time warp somebody is
     *       dragging.</li>
     * </ul>
     */
    public Moment horizon() {
        Moment own = live ? kron.now() : followed != null ? followed.horizon() : Moment.FOREVER;
        if (parent == null) {
            return own;
        }
        Moment inherited = parent.horizon();
        return inherited.isBefore(own) ? inherited : own;
    }

    /**
     * How far this tempo's speed is still <b>changing</b>: the maximum over this tempo and every ancestor
     * of where a glide settles and how long a followed signal varies, and {@code now} when nothing does.
     */
    public Moment varyingUntil() {
        Moment now = kron.now();
        Moment own = ownKnot != null && ownKnot.isAfter(now) ? ownKnot : now;
        if (followed != null && followed.varyingUntil().isAfter(own)) {
            own = followed.varyingUntil();
        }
        if (parent == null) {
            return own;
        }
        Moment inherited = parent.varyingUntil();
        return inherited.isAfter(own) ? inherited : own;
    }

    /** Whether this tempo, or one it is nested in, has been declared {@linkplain #live live}. */
    public boolean isLive() {
        return live || (parent != null && parent.isLive());
    }

    @Override
    public String toString() {
        return "Tempo(" + name + ", " + scale() + " local, " + effective + " global)";
    }

    // -------------------------------------------------------------- internals

    /**
     * End the current segment at now and start {@code next}.
     *
     * <p>Local time for the whole subtree is read at now under the old maps first, and every tempo in it
     * is then re-anchored to read the same value under the new ones, so nothing jumps. Then everything
     * predicted beyond now is retracted, because the map it was computed through no longer holds.
     */
    private void begin(String operation, Segment next) {
        kron.requireOnTimeline(operation);
        Moment at = kron.now();
        Map<Tempo, Long> before = new IdentityHashMap<>();
        capture(at, before);
        rebuildSubtree(at, before, next);
        kron.graph().invalidate(at);
        kron.retimeDomains();
    }

    /** Local time at {@code at} for this tempo and its whole subtree, under the maps that hold until now. */
    private void capture(Moment at, Map<Tempo, Long> before) {
        before.put(this, elapsedAt(at).nanos());
        for (Tempo child : children) {
            child.capture(at, before);
        }
    }

    /** Rebuild top-down, so each tempo sees its parent's new map. Only this tempo gets a new segment. */
    private void rebuildSubtree(Moment at, Map<Tempo, Long> before, Segment next) {
        rebuild(at, before.get(this), next);
        for (Tempo child : children) {
            child.rebuildSubtree(at, before, null);
        }
    }

    /**
     * Re-anchor this tempo at {@code at}, where its local time must read {@code value}, under its parent's
     * current map.
     *
     * <p>With {@code next}, that segment starts here. Without it, the current segment is kept and only its
     * offset moves, so the parent's change cannot make local time jump; a glide that has already ended is
     * settled into a hold of its end speed first. Then the two pieces are recomputed from the chain: the
     * composed piece runs to the latest knot still ahead, and the flattened piece takes over from there,
     * or from {@code at} when nothing is gliding.
     */
    private void rebuild(Moment at, long value, Segment next) {
        long parentNow = parentLocal(at);
        if (next != null) {
            this.segment = next;
            this.segmentStart = parentNow;
            this.segmentOffset = value;
        } else if (segment.extent() > 0 && parentNow - segmentStart >= segment.extent()) {
            this.segment = new Segment.Hold(segment.endSpeed());
            this.segmentStart = parentNow;
            this.segmentOffset = value;
        } else {
            this.segmentOffset = value - segment.advance(parentNow - segmentStart);
        }
        this.effective = parent == null ? segment.endSpeed() : parent.effective.times(segment.endSpeed());

        Moment knot = null;
        if (segment.extent() > 0) {
            long end = Math.addExact(segmentStart, segment.extent());
            knot = parent == null ? new Moment(end) : parent.globalAt(new Dur(end));
        }
        this.ownKnot = knot;
        if (parent != null && parent.composedUntil != null
                && (knot == null || parent.composedUntil.isAfter(knot))) {
            knot = parent.composedUntil;
        }
        if (knot != null && knot.isAfter(at)) {
            this.composedUntil = knot;
            this.tailStart = knot;
            this.tailLocal = new Dur(composed(knot));
            this.composedLast = composed(knot.minus(Dur.ns(1)));
        } else {
            this.composedUntil = null;
            this.tailStart = at;
            this.tailLocal = new Dur(value);
        }
    }

    /** The parent's local nanoseconds at {@code at}; global nanoseconds for the root. */
    private long parentLocal(Moment at) {
        return parent == null ? at.nanos() : parent.elapsedAt(at).nanos();
    }

    /** The composed piece: the parent's map, then this tempo's segment. */
    private long composed(Moment at) {
        return Math.addExact(segmentOffset, segment.advance(parentLocal(at) - segmentStart));
    }

    /**
     * {@code floor(d · num / den)}: one affine segment's forward map, rounded <b>down</b>.
     *
     * <p>Down, not to nearest, because the inverse is "the least moment that reaches it". Under rounding to
     * nearest, a 1:4 tempo reaches local 20 ms two nanoseconds before global 80 ms, because 79 999 998 ns
     * already rounds up to it, and an exact grid would land early. Rounding down means a local instant is
     * reached only once it has fully elapsed, so wherever the scale divides exactly the inverse is the exact
     * moment, and only the inexact cases round, by less than one nanosecond.
     */
    static long floorScaled(Ratio scale, long d) {
        if (scale.isOne()) {
            return d;
        }
        try {
            return Math.floorDiv(Math.multiplyExact(d, scale.num()), scale.den());
        } catch (ArithmeticException overflow) {
            return floorDiv(BigInteger.valueOf(d).multiply(BigInteger.valueOf(scale.num())),
                    BigInteger.valueOf(scale.den())).longValueExact();
        }
    }

    /**
     * The least {@code d} with {@code floorScaled(scale, d) >= target}: the inverse of one affine segment,
     * in the sense {@link #globalAt} needs.
     *
     * <p>{@code floor(d·num/den) >= k} holds exactly when {@code d·num/den >= k}, so the candidate is
     * {@code ceil(k·den/num)} in closed form ({@link #closedFormReaching}). The fix-up loops then check it
     * against the forward map itself, so the answer is right by construction even if the closed form is
     * not; they run zero times when it is, which the property test asserts by comparing the two. A glide's
     * inverse will be inexact (a square root, a logarithm), and the same fix-up is what will make that one
     * right.
     */
    static long leastReaching(Ratio scale, long target) {
        long d = closedFormReaching(scale, target);
        while (floorScaled(scale, d) < target) {
            d++;
        }
        while (floorScaled(scale, d - 1) >= target) {
            d--;
        }
        return d;
    }

    /** The closed-form candidate for {@link #leastReaching}, before any fix-up. */
    static long closedFormReaching(Ratio scale, long target) {
        if (scale.isOne()) {
            return target;
        }
        try {
            return Math.ceilDiv(Math.multiplyExact(target, scale.den()), scale.num());
        } catch (ArithmeticException overflow) {
            BigInteger numerator = BigInteger.valueOf(target).multiply(BigInteger.valueOf(scale.den()));
            BigInteger divisor = BigInteger.valueOf(scale.num());
            return floorDiv(numerator.add(divisor).subtract(BigInteger.ONE), divisor).longValueExact();
        }
    }

    private static BigInteger floorDiv(BigInteger a, BigInteger b) {
        BigInteger[] qr = a.divideAndRemainder(b);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

}
