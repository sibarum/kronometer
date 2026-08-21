package sibarum.kronometer;


import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * An independent sampling grid over the timeline.
 *
 * <p>An animation framework with one frame rate is a toy. Physics wants an exact 50 Hz grid because
 * integrated simulation is only reproducible at a constant {@code dt}; graphics wants whatever the
 * display gives it; audio wants a rate neither of them would recognise. All three run over one
 * logical timeline.
 *
 * <pre>{@code
 * Rate physics = kron.fixed(hz(50)).maxCatchUp(5).priority(0);
 * Rate frames  = kron.dynamic().priority(1);
 *
 * physics.each(step -> world.step(step.dt()));
 * frames.each(step -> render(shown.at(step.at())));
 * }</pre>
 *
 * <h2>What a domain owns, and what it does not</h2>
 *
 * The design originally had settlement policy (§5.1) living here — audio slipping while graphics
 * skipped. It cannot, and building it showed why: <b>slip is a property of the timeline, and there is
 * only one timeline.</b> Independent per-domain slip would mean independent logical clocks, at which
 * point cross-domain interpolation stops being well defined and A/V desync becomes possible rather
 * than impossible.
 *
 * <p>What a domain genuinely owns is its <b>catch-up policy</b>: when logical time has jumped past
 * some of its grid lines, how many does it replay and how many does it drop. That is the per-domain
 * form of the same choice — {@link #maxCatchUp(int) maxCatchUp(0)} is what {@link Settlement#SKIP}
 * means for one domain, and an unbounded value is what {@link Settlement#CATCH_UP} means — and it is
 * expressible without giving each domain its own idea of what time it is.
 *
 * <p>For the same reason there is no {@code couple()}. The design called for one, to stop audio and
 * graphics drifting apart; with a single clock they cannot drift apart, so coupling is not a feature
 * to add but a problem this architecture does not have.
 */
public final class Rate {

    private static final int DEFAULT_MAX_CATCH_UP = 8;
    /** Degrade quickly… */
    private static final int DEGRADE_AFTER_STEPS = 3;
    /** …and restore slowly. That asymmetry is the hysteresis. */
    private static final int RESTORE_AFTER_STEPS = 120;

    /** How a domain decides when to step. */
    public enum Kind {
        /** An exact grid: constant {@code dt}, guaranteed step count. */
        FIXED,
        /**
         * Steps when its driver ticks, with a varying {@code dt}. Requires a
         * {@linkplain Clock#driven() driven} clock — nothing else defines when a tick happens.
         */
        DYNAMIC
    }

    private final Kron kron;
    private final String name;
    private final Kind kind;
    private final Trigger tick;

    private Dur period;
    private int priority;
    private int maxCatchUp = DEFAULT_MAX_CATCH_UP;
    private Dur lookahead = Dur.ZERO;
    private List<Dur> ladder = List.of();
    private Consumer<Rate> onRateChange = r -> { };

    private final Tempo tempo;
    private Dur originLocal;
    private long index;
    private long completed;
    private long replayRemaining;
    private Moment lastStep;
    private int ladderIndex;
    private int overCount;
    private int underCount;
    private boolean started;
    private Shred driver;
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<Step>> handlers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Sampled<?>> samplers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Prediction<?>> predictions =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    Rate(Kron kron, String name, Kind kind, Dur period, Tempo tempo) {
        this.kron = kron;
        this.name = name;
        this.kind = kind;
        this.period = period;
        this.tempo = tempo;
        this.tick = new Trigger(kron, name + ".tick");
    }

    // ---------------------------------------------------------- configuration

    /**
     * Tie-break for shreds of different domains waking at the same moment (ordering rule 4). Lower
     * runs first, so physics before graphics is {@code priority(0)} and {@code priority(1)}.
     */
    public Rate priority(int priority) {
        requireUnstarted();
        this.priority = priority;
        return this;
    }

    /**
     * How many missed grid lines to replay after logical time jumps past them. The rest are dropped
     * and reported in {@link Step#skipped()}.
     *
     * <p>Also the spiral-of-death clamp: without a bound, a domain that fell far behind would try to
     * run every step it owes, which is the surest way to fall further behind.
     */
    public Rate maxCatchUp(int steps) {
        requireUnstarted();
        if (steps < 0) {
            throw new IllegalArgumentException("maxCatchUp cannot be negative: " + steps);
        }
        this.maxCatchUp = steps;
        return this;
    }

    /**
     * How much rendered future this domain wants to keep ahead of {@code now}.
     *
     * <p>Declared here, used by {@link Kron#slack()} today and by the precompute pool in M5. Audio
     * wants 100 ms, graphics two frames, physics none — setting them independently is the point.
     */
    public Rate lookahead(Dur lookahead) {
        requireUnstarted();
        this.lookahead = Objects.requireNonNull(lookahead, "lookahead");
        return this;
    }

    /**
     * A ladder of periods, fastest first, to step down through while slip persists and back up as it
     * drains.
     *
     * <p>Slip that never drains is not a scheduling problem, it is a capacity problem, and the honest
     * response is to ask for less rather than fall further behind. Hysteretic — three consecutive
     * over-budget steps to degrade, a hundred and twenty clean ones to restore — so it cannot
     * oscillate at the threshold.
     *
     * <p>Halving is the good case: a commensurate grid keeps cross-domain interpolation exact. A
     * ladder that is not commensurate introduces a phase discontinuity at each change, because the
     * grid is rebased on the current line.
     */
    public Rate degrade(Dur... periods) {
        requireUnstarted();
        requireFixed("degrade");
        this.ladder = List.copyOf(Arrays.asList(periods));
        if (!ladder.isEmpty()) {
            this.period = ladder.get(0);
            this.ladderIndex = 0;
        }
        return this;
    }

    /** Observe rate changes from {@link #degrade}. */
    public Rate onRateChange(Consumer<Rate> listener) {
        this.onRateChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ------------------------------------------------------------------- API

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** The current period. Changes if {@link #degrade} steps the rate down. */
    public Dur period() {
        return period;
    }

    public int priority() {
        return priority;
    }

    public Dur lookahead() {
        return lookahead;
    }

    /**
     * How many steps this domain has completed.
     *
     * <p>Not the grid index it is heading for: {@code index} is incremented before the yield, so
     * while the domain is parked waiting for its next line it is already one ahead. Reporting that as
     * "steps run" would overcount by exactly one, forever.
     */
    public long steps() {
        return completed;
    }

    /**
     * Publish a value from this domain for other domains to read smoothly. Committed at the end of
     * every step; see {@link Sampled} for the one-step lag that interpolation implies.
     */
    public <T> Sampled<T> sample(Supplier<T> source, Interp<T> interp) {
        Sampled<T> sampled = new Sampled<>(
                Objects.requireNonNull(source, "source"), Objects.requireNonNull(interp, "interp"));
        samplers.add(sampled);
        return sampled;
    }

    /**
     * Compute {@code signal}'s future on this domain's grid, ahead of {@code now}.
     *
     * <p>Requires a fixed grid, and the reason is not an implementation shortcut: a dynamic domain's
     * sample points are whatever the display decides, so there is no grid to fill in advance. A dynamic
     * consumer that wants predicted values reads them from a fixed domain through
     * {@link #sample}, which is the machinery M3 already built for exactly this shape of problem.
     */
    public <T> Prediction<T> predict(Signal<T> signal) {
        return predict(signal, Predict.EAGER);
    }

    public <T> Prediction<T> predict(Signal<T> signal, Predict policy) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(policy, "policy");
        requireFixed("predict");
        Prediction<T> prediction = new Prediction<>(signal, this, policy);
        predictions.add(prediction);
        return prediction;
    }

    /** The prediction buffers on this domain, for diagnostics. */
    public List<Prediction<?>> predictions() {
        return List.copyOf(predictions);
    }

    /**
     * Run {@code handler} once per step, forever, on this domain's shred.
     *
     * <p>Callable more than once: handlers run in <b>registration order</b> within a single step, on one
     * shred. That is not a convenience — it is the only thing that makes input composable. A bridge that
     * delivers bus events must run before the effects that read them, and registering first is how you
     * say so. One handler per domain would have forced a domain per handler, and then the ordering
     * question would come back as a priority argument for something that is really just a sequence.
     *
     * @return the shred driving this domain — the same one for every handler
     */
    public Shred each(Consumer<Step> handler) {
        Objects.requireNonNull(handler, "handler");
        handlers.add(handler);
        if (started) {
            return driver;
        }
        started = true;
        driver = kron.sporkDomain(this, () -> {
            originLocal = tempo.elapsed();
            lastStep = Time.now();
            kron.predictor().fill(this, predictions);       // so the first step is a hit, not a miss
            while (true) {
                Step step = kind == Kind.FIXED ? nextFixed() : nextDynamic();
                // Feed this moment's buffered values into the memo *before* the handler runs, so an
                // effect reading get() is served an index lookup and cannot tell prediction happened.
                for (Prediction<?> prediction : predictions) {
                    prediction.primeInto(step.index(), Time.now(), kron.graph().version());
                }
                for (Consumer<Step> h : handlers) {
                    h.accept(step);
                }
                completed++;
                for (Sampled<?> sampled : samplers) {
                    sampled.commit(step.at(), step.dt());
                }
                considerRateChange();
                kron.predictor().fill(this, predictions);   // top the window back up
            }
        });
        return driver;
    }

    // -------------------------------------------------------------- internals

    Trigger tick() {
        return tick;
    }

    /** The next moment this domain intends to be woken, for {@link Kron#slack()}. */
    long nextGridLineNanos() {
        if (kind != Kind.FIXED || originLocal == null) {
            return Long.MAX_VALUE;              // a dynamic domain cannot know; nor can an unstarted one
        }
        return gridLine(index + 1).nanos();
    }

    /**
     * The n-th grid line, in global time.
     *
     * <p>The period is declared in this domain's <b>tempo</b>, so this is where a nested time scale
     * actually bites: a 20 ms period inside a 1:4 tempo lands 80 ms apart on the wall. Computed from the
     * origin rather than from the last line, so the tempo conversion rounds once per line and never
     * accumulates.
     */
    private Moment gridLine(long n) {
        return tempo.globalAt(originLocal.plus(period.times(n)));
    }

    /** The n-th grid line. Precomputation keys buffers by index, so it needs this by name. */
    Moment gridLineAt(long n) {
        return gridLine(n);
    }

    /** The highest grid index at or before {@code at}. */
    long gridIndexAtOrBefore(Moment at) {
        if (originLocal == null) {
            return -1;
        }
        return Math.floorDiv(tempo.elapsedAt(at).minus(originLocal).nanos(), period.nanos());
    }

    /** The lowest grid index strictly after {@code at} — where a fill window starts. */
    long gridIndexAfter(Moment at) {
        return gridIndexAtOrBefore(at) + 1;
    }

    /**
     * The next fixed step.
     *
     * <p>Under normal operation this simply waits for the next grid line. The interesting branch is
     * when logical time has already jumped past one or more lines — a {@link Settlement#SKIP} or a
     * hard resync — and the domain has to decide how much of what it owes to actually run.
     */
    private Step nextFixed() {
        if (replayRemaining > 0) {
            replayRemaining--;
            index++;
            Time.advance(Dur.ZERO);             // yield without letting logical time pass
            return new Step(index, gridLine(index), period, 0);
        }

        long linesPassed = Math.floorDiv(
                tempo.elapsed().minus(originLocal).nanos(), period.nanos());
        long behind = linesPassed - index;
        long dropped = 0;

        if (behind > 0) {
            long replay = Math.min(behind, maxCatchUp);
            dropped = behind - replay;
            index += dropped;
            if (replay > 0) {
                replayRemaining = replay - 1;
                index++;
                Time.advance(Dur.ZERO);
                return new Step(index, gridLine(index), period, dropped);
            }
        }

        index++;
        Moment target = gridLine(index);
        Time.until(target);
        return new Step(index, target, period, dropped);
    }

    /** The next driven step: wait for the kernel to reach a tick boundary. */
    private Step nextDynamic() {
        Time.await(tick);
        Moment at = Time.now();
        Dur dt = at.since(lastStep);
        lastStep = at;
        return new Step(++index, at, dt, 0);
    }

    private void considerRateChange() {
        if (ladder.isEmpty()) {
            return;
        }
        Dur slip = kron.slip();
        if (slip.compareTo(period) > 0) {
            underCount = 0;
            if (++overCount >= DEGRADE_AFTER_STEPS && ladderIndex < ladder.size() - 1) {
                shiftTo(ladderIndex + 1);
            }
        } else if (slip.isZero()) {
            overCount = 0;
            if (++underCount >= RESTORE_AFTER_STEPS && ladderIndex > 0) {
                shiftTo(ladderIndex - 1);
            }
        } else {
            overCount = 0;
            underCount = 0;
        }
    }

    private void shiftTo(int newIndex) {
        originLocal = originLocal.plus(period.times(index));   // rebase on the line we stand on
        index = 0;
        ladderIndex = newIndex;
        period = ladder.get(newIndex);
        overCount = 0;
        underCount = 0;
        onRateChange.accept(this);
    }

    private void requireUnstarted() {
        if (started) {
            throw new IllegalStateException(
                    "configure " + name + " before calling each(); it is already running");
        }
    }

    private void requireFixed(String what) {
        if (kind != Kind.FIXED) {
            throw new IllegalStateException(what + " needs a fixed rate, not " + kind);
        }
    }

    @Override
    public String toString() {
        return "Rate(" + name + ", " + kind
                + (kind == Kind.FIXED ? ", " + period : "") + ", priority " + priority + ")";
    }
}
