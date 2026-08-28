package sibarum.kronometer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The runtime: one kernel thread that owns logical time and hands a baton to one shred at a time.
 *
 * <p><b>The invariant:</b> logical time advances only when no shred is runnable. Everything else
 * follows — exact timing, a total order over shreds, and freedom from races between them.
 *
 * <p>Outside the timeline you address a {@code Kron}; inside a shred you speak in {@link Time}. That
 * split is deliberate, and the compiler enforcing it is a feature.
 *
 * <pre>{@code
 * try (Kron kron = Kron.virtual()) {
 *     kron.spork(() -> {
 *         for (int i = 0; i < 4; i++) {
 *             fire();
 *             advance(ms(250));      // exactly 250 ms later. every time. no drift.
 *         }
 *     });
 *     kron.run();
 * }
 * }</pre>
 *
 * <h2>A deployment note</h2>
 *
 * M0 found that pinning the virtual-thread carrier pool to one thread makes a handoff 3× cheaper
 * (511 → 342 ns), because the baton serializes everything anyway and extra carriers buy only
 * cross-core wakeups. Kronometer deliberately does <em>not</em> set that itself: the property is
 * global, JDK 25 has no public per-thread scheduler, and a library that pinned the host application's
 * whole virtual-thread scheduler would be a bad guest. It is an application-level flag, worth setting
 * in a desktop app that owns its JVM:
 *
 * <pre>{@code -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1}</pre>
 *
 * Correctness never depends on it, and the kernel is comfortably inside budget without it.
 */
public final class Kron implements AutoCloseable {

    static final ScopedValue<Shred> CURRENT = ScopedValue.newInstance();

    private static final long UNBOUNDED = Long.MIN_VALUE;

    private record Entry(Moment moment, int priority, long seq, Shred shred, long suspensionId)
            implements Comparable<Entry> {

        /**
         * Ordering rules 1 and 4 together: by moment, then by rate-domain priority, then by sequence
         * number. Priority slots cleanly between the two because every shred outside a domain shares
         * priority 0, so rule 1 is exactly this comparator with the middle term constant.
         */
        @Override
        public int compareTo(Entry other) {
            int byMoment = moment.compareTo(other.moment);
            if (byMoment != 0) {
                return byMoment;
            }
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(seq, other.seq);
        }

        /**
         * Whether the shred has since been woken by something else — a trigger firing, or a
         * cancellation — which retracts this entry. Discarded without advancing logical time.
         */
        boolean isStale() {
            return shred.suspensionId() != suspensionId;
        }
    }

    private final Clock clock;
    private final PriorityQueue<Entry> timeline = new PriorityQueue<>();
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<Shred> alive = new LinkedHashSet<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Rate> domains =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Tempo> tempos =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Graph graph = new Graph(this);
    private final Predictor predictor = new Predictor(this);

    /** Shred to kernel: the baton coming back. */
    private final Gate kernelGate = new Gate();
    /** Caller to kernel: run a batch. Single-permit, so ticks arriving mid-batch coalesce. */
    private final Gate kernelStart = new Gate();
    /**
     * Anything to kernel: wake up, the timeline may have work now.
     *
     * <p>A {@link Gate} rather than a monitor because it tolerates being opened before anyone is
     * waiting, which is exactly the race an external post has to survive: the event may arrive in the
     * instant between the kernel deciding it is idle and actually parking.
     */
    private final Gate idleGate = new Gate();

    /**
     * Kernel to caller: batch completion, by ticket.
     *
     * <p>Not a {@link Gate}, deliberately. A gate's permit is a single boolean, and in
     * {@link Driven.Mode#HANDOFF} nobody consumes it — so a later synchronous batch (a
     * {@link #close()}, say) would take the stale permit and return before its own work had run.
     * Tickets make "my batch finished" a distinct question from "some batch finished".
     */
    private final Object batchLock = new Object();
    private long batchRequested;
    private long batchCompleted;

    private Thread kernelThread;
    private Runnable batchBefore;
    private long batchLimitNanos = UNBOUNDED;
    private volatile boolean kernelStopping;
    private volatile boolean stopRequested;
    private volatile boolean running;
    /**
     * Whether a batch has ever been requested — {@code true} for the rest of this runtime's life.
     *
     * <p>Distinct from {@link #running}, which is only true <em>during</em> a batch. Both answer "may
     * the calling thread touch kernel state directly", and only this one answers it correctly for a
     * driven clock, where the kernel is idle between ticks and yet a tick may begin at any moment.
     */
    private volatile boolean started;
    /**
     * Whoever last called {@link #tick} — the host's loop thread, learned rather than declared.
     *
     * <p>Outside a shred there is exactly one thread that may still touch kernel state safely, and it
     * is this one, between {@code INLINE} ticks: it is the only thread that starts batches, so while it
     * is here no batch is running and none can begin. Every other thread must cross the baton. Knowing
     * which is which is what turns "sometimes throws, sometimes corrupts the timeline" into one
     * consistent answer per caller.
     */
    private volatile Thread hostThread;
    private volatile long nextDeadlineNanos = Long.MAX_VALUE;
    /** The unset {@link #workListener}, held by identity so {@link #toString} can say it is unset. */
    private static final Runnable NO_WAKE = () -> { };
    /** Kernel to host: something arrived while you were asleep. See {@link #onWork}. */
    private volatile Runnable workListener = NO_WAKE;

    private long seq;
    private long ticks;
    private long nextShredId;
    private Moment now = Moment.ORIGIN;
    private Trace trace;
    private Tempo rootTempo;
    private Signal<Moment> timeSignal;
    private Failures.TimelineStalled stall;
    private boolean draining;
    private boolean closed;

    private Kron(Clock clock) {
        this.clock = clock;
    }

    /** Logical time jumps to the next scheduled moment. Deterministic by construction. */
    public static Kron virtual() {
        return new Kron(Clock.virtual());
    }

    /** Logical time is paced against the real wall clock, with the default settlement policy. */
    public static Kron realtime() {
        return new Kron(Clock.realtime());
    }

    /** Logical time is stepped from outside, once per presented frame, via {@link #tick}. */
    public static Kron driven() {
        return new Kron(Clock.driven());
    }

    public static Kron of(Clock clock) {
        return new Kron(Objects.requireNonNull(clock, "clock"));
    }

    // ------------------------------------------------------------------ API

    public Clock clock() {
        return clock;
    }

    /**
     * Whether the calling thread is currently inside a shred.
     *
     * <p>The predicate an adapter needs. A GUI handler runs on a worker thread, so a library sitting
     * between the two has to ask before deciding whether to act now or {@link #post} — and making every
     * such caller reach for a package-private detail, or catch {@link Failures.NotOnTimeline} as control
     * flow, would be worse than answering the question.
     */
    public boolean isOnTimeline() {
        return CURRENT.isBound();
    }

    /**
     * Run {@code work} on the timeline: now if the caller is already there, otherwise at the next
     * moment the kernel observes.
     *
     * <p>Four lines that every adapter was going to write, and the shape they were going to write it
     * in. A GUI event handler runs on a worker, so anything it wants to do to the graph — start a
     * tween, retarget a cell, spork a shred — has to cross the baton first, and {@link #isOnTimeline()}
     * on its own leaves the caller holding both branches.
     *
     * <p>What it guarantees is that {@code work} runs <b>inside a shred</b>, not merely somewhere the
     * graph may be mutated. That distinction is the whole value: the reason to cross the baton is
     * usually to call {@link Time#spork}, {@link Time#advance} or something built on them, and those
     * need a current shred rather than an absence of contention. Running the body on the caller's
     * thread during setup would satisfy {@link #requireOnTimeline} and still fail on the first time
     * intrinsic — so off the timeline this always posts, before {@link #run()} as much as after it.
     *
     * @throws IllegalStateException from off the timeline under the {@linkplain Clock#virtual()
     *         virtual} clock while it is running, for the reason {@link #post(Runnable)} gives: an
     *         external thread's arrival time is not a reproducible input
     */
    public void onTimeline(Runnable work) {
        Objects.requireNonNull(work, "work");
        if (CURRENT.isBound()) {
            work.run();
        } else if (mayActInline()) {
            // Setup, or the host's own loop thread between ticks: nothing is contending, so place it
            // directly. It still runs as a shred, at the first moment the kernel observes, which is
            // what the time intrinsics need.
            sporkAt(now, Detach.YES, "onTimeline", work);
        } else {
            post(work);
        }
    }

    /** The current logical moment. */
    public Moment now() {
        return now;
    }

    /**
     * The outstanding debt between logical and wall-clock time.
     *
     * <p>Read alongside {@link #slack()}: slack is how much future you have, slip is how far behind
     * you are.
     */
    public Dur slip() {
        return clock.slip();
    }

    /**
     * Wall-clock time remaining before the next scheduled moment falls due — the budget available to
     * whatever is running right now, and therefore how long you may block on the timeline.
     *
     * <p>This is the degenerate form of the lookahead budget: with no precomputed future yet (M5), the
     * next deadline is exactly when the buffer runs dry. Overrun it and the shortfall becomes
     * {@link #slip()} — a buffer cannot invent time.
     *
     * <p>A deadline counts here only once something has declared it. A bare shred declares its next
     * wake at the <em>end</em> of its segment, so while it runs the kernel genuinely does not know when
     * it next wants waking, and this reports {@link Dur#FOREVER} — the truth, if not a useful number.
     * A {@link Rate} domain declares a period up front, which is what makes its next grid line
     * knowable <em>before</em> the segment runs, and therefore what makes this the budget it is
     * supposed to be.
     */
    public Dur slack() {
        long next = nextDeadlineNanos;
        for (Rate domain : domains) {
            next = Math.min(next, domain.nextGridLineNanos());
        }
        return next == Long.MAX_VALUE ? Dur.FOREVER : clock.slackUntil(next);
    }

    /**
     * The earliest moment at which this runtime has anything to do — {@link Moment#FOREVER} if it has
     * nothing to do at all.
     *
     * <p>What a host that owns its own wait needs, and the one thing it cannot work out for itself. A
     * render-on-demand loop blocks in {@code waitEvents(timeout)} and wakes on input; the timeout is
     * this. {@link Signal#varyingUntil()} answers the same question for one signal, but a loop needs
     * the aggregate, and only the kernel holds all three parts of it:
     *
     * <ul>
     *   <li>the next entry on the timeline — a sleeping shred, a posted task;</li>
     *   <li>the next grid line of every {@link Rate} that still has handlers on it;</li>
     *   <li>whether anything an {@link Effect} reads is still <em>varying</em>, in which case the
     *       answer is {@code now}: a value mid-flight wants the next frame that can be drawn, and
     *       there is no discrete moment to name;</li>
     *   <li>whether anything is waiting in the inbox, which is also {@code now} — work from a
     *       <em>worker</em> thread is not placed until a tick looks at it, so however precisely its
     *       caller declared a moment, that moment is not yet knowable from here. Costs the host one
     *       tick, after which the answer is exact; posts from the thread that ticks are placed at once
     *       and never pay it. {@link #onWork} is how a sleeping host learns to take that tick.</li>
     * </ul>
     *
     * <h2>Advisory, and deliberately conservative</h2>
     *
     * Under a {@linkplain Clock#driven() driven} clock the host supplies the ticks, so this is not a
     * promise that the kernel will run then — it is the kernel saying <em>there is no point ticking me
     * before this</em>. It may be too early, and an answer that is too early costs one wasted frame.
     * It is never too late, which is the property a loop is entitled to rely on: anything the kernel
     * cannot see inside — a raw {@link Rate#each} handler, a {@link Sampled} read outside an effect —
     * is on a domain whose grid line is reported regardless.
     *
     * <h2>When to ask</h2>
     *
     * From the timeline, or from the host thread once {@link #tick} has returned in
     * {@link Driven.Mode#INLINE} — that return is what publishes the kernel's writes to the caller. In
     * {@link Driven.Mode#HANDOFF} a batch may still be in flight, and the answer is then a reading of
     * whenever the kernel last got to.
     *
     * @see #isQuiescent()
     */
    public Moment nextDeadline() {
        // Something arrived from outside and has not been placed on the timeline yet. It will be, at
        // the next moment the kernel observes, so the next moment is due.
        if (!inbox.isEmpty()) {
            return now;
        }
        for (Effect effect : graph.effects()) {
            if (effect.varyingUntil().isAfter(now)) {
                return now;
            }
        }
        long next = nextDeadlineNanos;
        for (Rate domain : domains) {
            next = Math.min(next, domain.nextGridLineNanos());
        }
        return next == Long.MAX_VALUE ? Moment.FOREVER : new Moment(next);
    }

    /**
     * Whether nothing is scheduled and nothing is varying, so a host may sleep indefinitely.
     *
     * <p>Exactly {@code nextDeadline().equals(Moment.FOREVER)}, and named separately because that is
     * the question a loop actually asks — {@code animating ? nextDeadline() : never} is the whole
     * condition of a render-on-demand loop, and writing the sentinel comparison at every call site
     * would be worse than answering it here.
     *
     * <p>Conservative in the same direction: a runtime that is quiescent is certainly idle, and a
     * runtime that is <em>not</em> may still have nothing visible to do. "Why is my loop never idle"
     * is answered by {@link #whyBusy()}.
     */
    public boolean isQuiescent() {
        return nextDeadline().equals(Moment.FOREVER);
    }

    /**
     * Why this runtime is not quiescent, in one line per reason — empty when it is.
     *
     * <p>The diagnostic sibling of {@link #isQuiescent()}, and the reason it exists is that a
     * never-quiescent kernel is otherwise a mystery: the loop spins, the answer is always {@code now},
     * and nothing says which of a hundred effects or domains is asking for it. Cost is proportional to
     * the number of effects and domains, so this is for a log line and a breakpoint, not for a frame.
     */
    public List<String> whyBusy() {
        List<String> reasons = new java.util.ArrayList<>();
        if (!inbox.isEmpty()) {
            reasons.add("inbox: work posted from off the timeline, not yet placed");
        }
        for (Effect effect : graph.effects()) {
            Moment varying = effect.varyingUntil();
            if (varying.isAfter(now)) {
                reasons.add(effect.name() + ": reads something varying until "
                        + (varying.equals(Moment.FOREVER) ? "forever" : varying.toString()));
            }
        }
        if (nextDeadlineNanos != Long.MAX_VALUE) {
            reasons.add("timeline: next entry at " + new Moment(nextDeadlineNanos));
        }
        for (Rate domain : domains) {
            long line = domain.nextGridLineNanos();
            if (line != Long.MAX_VALUE) {
                reasons.add(domain.name() + ": " + domain.handlerCount()
                        + " handler(s), next grid line at " + new Moment(line));
            }
        }
        return List.copyOf(reasons);
    }

    /**
     * How the kernel wakes a host that went to sleep on {@link #isQuiescent()}.
     *
     * <p>The other half of render-on-demand, and the half that is a hang rather than a cost when it is
     * missing. {@link #nextDeadline()} tells a host it may block indefinitely; from that instant the
     * only two things that can end the sleep are the host's own event source and this. So a worker
     * thread calling {@link #onTimeline} — a click starting an animation, a network reply landing, a
     * background load finishing — reaches an inbox nobody is going to look at, and the window stays
     * frozen until some unrelated keystroke happens along. The kernel can see perfectly well that it
     * has work for a sleeping host. Without this it has no way to say so.
     *
     * <pre>{@code
     * kron.onWork(GLFW::glfwPostEmptyEvent);       // wired once, at startup
     * }</pre>
     *
     * <h2>What it fires on</h2>
     *
     * Work arriving from <em>off</em> the timeline, which is the only kind a sleeping host can miss:
     * {@link #post}, {@link #onTimeline} from a worker, a cancellation, a {@link Rate#each}
     * registration that restarts a parked domain. Work the timeline schedules for itself needs no wake
     * — in {@link Driven.Mode#INLINE} {@code tick()} returns with the batch complete and the host asks
     * {@link #nextDeadline()} after that return, so it is already in the answer.
     *
     * <p>It fires on <em>every</em> such arrival, not only on the one that finds the inbox empty. The
     * transition test is the obvious optimisation and it is a race against the kernel's own drain;
     * this is the same conservative direction {@link Rate} takes with a wasted wake, and the asymmetry
     * is sharper here — a redundant wake costs one loop iteration that finds nothing to do, and a
     * missed one costs a window that never comes back.
     *
     * <h2>What the listener may do</h2>
     *
     * It runs on the <b>posting thread</b>, inline, before the post returns, and that thread is
     * whatever the caller happened to be. So it must be safe from any thread, it must not block, and it
     * must not call back into this {@code Kron} — posting from it recurses. Nudging an event loop is
     * the entire intended body, and {@code glfwPostEmptyEvent} is documented as callable from any
     * thread for exactly this purpose.
     *
     * <p>The work is placed on the inbox <em>before</em> the listener runs, so a listener that throws
     * cannot lose it — only delay it until something else ticks. The failure surfaces through the
     * kernel's usual channel rather than into the caller's post, because a wake path that has silently
     * stopped working presents as a frozen UI with nothing in the log, which is precisely the mystery
     * {@link #whyBusy()} exists to prevent.
     *
     * <h2>One listener, last wins</h2>
     *
     * There is a single slot, and a second call replaces the first without a word. That is the right
     * shape — a wake is one nudge to one loop, and a list would invite two — but it means <b>a
     * {@code Kron} must have exactly one call site for this in the whole application</b>. Two, and the
     * loser is whichever ran first: a profiler, a debug harness or a second adapter installing its own
     * leaves the real loop with nothing to wake it, and the symptom is a window that stops responding
     * for reasons that appear to have nothing to do with the thing that was added. If a second party
     * needs to observe wakes, give it the real listener to call rather than a slot of its own.
     *
     * @see #isQuiescent()
     */
    public void onWork(Runnable listener) {
        this.workListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * How long a host may block before it should tick again — the whole render-on-demand condition, as
     * one number. {@link Dur#FOREVER} means block until an event arrives.
     *
     * <pre>{@code
     * Dur budget = kron.sleepTimeout();
     * waitEvents(budget.equals(Dur.FOREVER) ? NO_TIMEOUT : budget.millis());
     * }</pre>
     *
     * <p>Prefer this to composing {@link #isQuiescent()} and {@link #nextDeadline()} by hand, because
     * <b>this is the only form that cannot deadlock</b>: it returns {@code FOREVER} only when
     * {@link #onWork} has been wired, since an indefinite block with nothing able to end it is a frozen
     * window. Unwired, the answer is {@link Dur#ZERO} — the loop keeps redrawing exactly as it did
     * before it asked, which is wasteful and visibly fine, and {@code toString()} names the missing
     * listener. Wasting a core is a bug you file; freezing is a bug you spend a day on.
     *
     * <p>Zero also comes back for the ordinary busy case — something is varying, so the next drawable
     * frame is wanted — so a caller cannot distinguish "animating" from "not wired". That is
     * deliberate: they call for the same action, and the diagnostic belongs in {@code toString()}
     * rather than in a loop that runs sixty times a second.
     *
     * @see #onWork(Runnable)
     */
    public Dur sleepTimeout() {
        Moment deadline = nextDeadline();
        if (deadline.equals(Moment.FOREVER)) {
            return workListener == NO_WAKE ? Dur.ZERO : Dur.FOREVER;
        }
        return deadline.isAfter(now) ? deadline.since(now) : Dur.ZERO;
    }

    /**
     * A fixed-rate domain: an exact grid with a constant {@code dt}, in the root tempo.
     *
     * <p>The only way integrated physics and integrated smoothing are reproducible, and the reason
     * periodic work belongs in a domain rather than in a loop of {@code advance}.
     */
    public Rate fixed(Dur period) {
        return fixedIn(tempo(), "fixed@" + period, period);
    }

    public Rate fixed(String name, Dur period) {
        return fixedIn(tempo(), name, period);
    }

    Rate fixedIn(Tempo tempo, String name, Dur period) {
        Objects.requireNonNull(period, "period");
        if (period.nanos() <= 0) {
            throw new IllegalArgumentException("period must be positive: " + period);
        }
        return register(new Rate(this, name, Rate.Kind.FIXED, period, tempo));
    }

    // ----------------------------------------------------------------- tempo

    /** The root time context: 1:1, locked to the wall. Everything else nests inside it. */
    public Tempo tempo() {
        if (rootTempo == null) {
            rootTempo = new Tempo(this, "root", null, Ratio.ONE, Moment.ORIGIN);
            tempos.add(rootTempo);
        }
        return rootTempo;
    }

    /** Every tempo on this runtime, root first. */
    public List<Tempo> tempos() {
        tempo();
        return List.copyOf(tempos);
    }

    void registerTempo(Tempo tempo) {
        tempos.add(tempo);
    }

    // ----------------------------------------------------------------- graph

    Graph graph() {
        return graph;
    }

    Predictor predictor() {
        return predictor;
    }

    /** Every prediction buffer on this runtime, for diagnostics. */
    public java.util.List<Prediction<?>> predictions() {
        java.util.List<Prediction<?>> all = new java.util.ArrayList<>();
        for (Rate domain : domains) {
            all.addAll(domain.predictions());
        }
        return java.util.List.copyOf(all);
    }

    /**
     * Retract every prediction for a moment strictly after { at}.
     *
     * <p>Called by the graph on every invalidation. Strictly after, because a sample already delivered
     * for an earlier grid line was correct when it was delivered.
     */
    void discardPredictionsAfter(Moment at, Signal<?> source) {
        for (Rate domain : domains) {
            for (Prediction<?> prediction : domain.predictions()) {
                if (source == null || dependsOn(prediction.signal(), source)) {
                    prediction.discardAfter(at);
                }
            }
        }
    }

    /**
     * Whether {@code node} reads {@code target}, directly or through any chain.
     *
     * <p>Walks the dependency sets the graph already records from reading, so nothing extra has to be
     * declared. A {@code Cell} has no dependencies of its own unless it is following another signal,
     * which is the one edge the read-registration does not capture on the cell itself.
     */
    private boolean dependsOn(Signal<?> node, Signal<?> target) {
        java.util.Set<Signal<?>> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Signal<?>> pending = new java.util.ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            Signal<?> current = pending.pop();
            if (current == target) {
                return true;
            }
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof Derived<?> derived) {
                pending.addAll(derived.dependencies());
            } else if (current instanceof Cell<?> cell && cell.followedSource() != null) {
                pending.push(cell.followedSource());
            }
        }
        return false;
    }

    /** A mutable source in the graph, in the root tempo. */
    public <T> Cell<T> cell(T initial) {
        return cell("cell", initial);
    }

    public <T> Cell<T> cell(String name, T initial) {
        return new Cell<>(this, name, tempo(), initial);
    }

    /** A mutable source whose driving curves are measured in {@code tempo}'s local time. */
    public <T> Cell<T> cell(String name, Tempo tempo, T initial) {
        return new Cell<>(this, name, Objects.requireNonNull(tempo, "tempo"), initial);
    }

    /**
     * A value derived from other signals. Dependencies are whatever the body reads; the horizon is the
     * narrowest of theirs.
     */
    public <T> Signal<T> computed(java.util.function.Supplier<T> body) {
        return computed("computed", body);
    }

    public <T> Signal<T> computed(String name, java.util.function.Supplier<T> body) {
        return new Derived<>(graph, name, Objects.requireNonNull(body, "body"));
    }

    /** Logical time itself, as a signal. Knowable forever, which is what makes curves predictable. */
    public Signal<Moment> time() {
        if (timeSignal == null) {
            timeSignal = new Signal<>() {
                @Override
                public Moment get() {
                    return at(graph.evaluatingAt());
                }

                @Override
                public Moment at(Moment at) {
                    graph.observe(this);
                    return at;
                }

                @Override
                public Moment horizon() {
                    return Moment.FOREVER;
                }

                @Override
                public String toString() {
                    return "time()";
                }
            };
        }
        return timeSignal;
    }

    /**
     * An effect that runs once per step of {@code domain} — the right form for anything continuous,
     * since a value that varies with time changes at every moment and what you want is to sample it.
     *
     * <p>Cancelling the returned effect removes <em>its</em> handler from the domain and nothing else.
     * It used to bind the shred {@link Rate#each} returns, which is the domain's single driver shared by
     * every handler, so the first {@code cancel()} on a frame clock stopped the clock for everyone —
     * invisibly, and only from the second animation onwards.
     */
    public Effect effect(Rate domain, Runnable body) {
        Objects.requireNonNull(domain, "domain");
        Effect effect = new Effect(this, "effect@" + domain.name(), body, false);
        java.util.function.Consumer<Step> handler = step -> effect.run();
        graph.registerEffect(effect);
        domain.each(handler);
        effect.bindDetach(() -> domain.remove(handler));
        return effect;
    }

    /**
     * Land {@code signal}'s value on {@code sink} once per step of {@code domain}.
     *
     * <p>Sugar over {@link #effect(Rate, Runnable)}, and it earns its place by being the single
     * most-written line in every adapter — {@code effect(frames, () -> setter.accept(signal.get()))}
     * is a lambda inside a lambda that names nothing.
     *
     * <p>The direction matters more than the brevity: the signal is the source of truth and the sink is
     * only where it lands. That is the shape that stays precomputable — if the signal descends only
     * from curves, this property's whole visible future can be evaluated ahead, off the frame thread,
     * and the sink never knows.
     *
     * <p>Cancel the returned {@link Effect} to unbind. That detaches this handler alone, leaving the
     * domain and every other binding on it running.
     */
    public <T> Effect bind(Rate domain, Signal<T> signal, Consumer<T> sink) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(sink, "sink");
        return effect(domain, () -> sink.accept(signal.get()));
    }

    /**
     * A cell whose value lands on {@code sink} once per step of {@code domain} — the usual way to start.
     *
     * <p>The two lines everybody writes first, in the order that makes the second one automatic: create
     * the source of truth, say where it shows up, and from then on animate the cell and forget the
     * sink exists.
     */
    public <T> Cell<T> bound(Rate domain, String name, T initial, Consumer<T> sink) {
        Cell<T> cell = cell(name, initial);
        bind(domain, cell, sink);
        return cell;
    }

    /**
     * An effect that runs now, and again whenever something it read is contradicted — the right form
     * for discrete change.
     */
    public Effect effect(Runnable body) {
        return effect("effect", body);
    }

    public Effect effect(String name, Runnable body) {
        Effect effect = new Effect(this, name, Objects.requireNonNull(body, "body"), true);
        graph.registerReactive(effect);
        graph.registerEffect(effect);
        if (CURRENT.isBound()) {
            effect.run();
        } else {
            post(now, effect::run);
        }
        return effect;
    }

    /**
     * A dynamic-rate domain: steps once per {@link #tick}, with a varying {@code dt}.
     *
     * <p>Requires a {@linkplain Clock#driven() driven} clock — nothing else defines when a tick
     * happens. Its step runs after everything else scheduled in the tick's window, which is the right
     * order for a render pass reading what the simulation just produced.
     */
    public Rate dynamic() {
        return dynamic("dynamic");
    }

    public Rate dynamic(String name) {
        // A dynamic domain follows the tick stream, which is wall-locked by definition, so it lives in
        // the root tempo: there is no local rate for a scale to adjust.
        return register(new Rate(this, name, Rate.Kind.DYNAMIC, null, tempo()));
    }

    private Rate register(Rate domain) {
        domains.add(domain);
        return domain;
    }

    /** The domains registered on this runtime, in declaration order. */
    public List<Rate> domains() {
        return List.copyOf(domains);
    }

    /** Observe overruns, repayments, skips and resyncs. */
    public void onOverrun(Consumer<Overrun> listener) {
        clock.onOverrun(listener);
    }

    /** Turn tracing on (if it is not already) and return the trace. Events before this are not recorded. */
    public Trace trace() {
        if (trace == null) {
            trace = new Trace();
        }
        return trace;
    }

    public Trigger trigger(String name) {
        return new Trigger(this, name);
    }

    public Shred spork(Runnable body) {
        return spork(Detach.NO, null, body);
    }

    public Shred spork(String name, Runnable body) {
        return spork(Detach.NO, name, body);
    }

    /**
     * Create a shred, to start at the current moment.
     *
     * <p>Returns to the caller immediately; the child is enqueued with a fresh sequence number, so it
     * starts later in the <em>same</em> step, after the caller's segment finishes. Segments stay
     * atomic.
     */
    public Shred spork(Detach detach, String name, Runnable body) {
        Objects.requireNonNull(body, "body");
        if (!mayActInline()) {
            throw new Failures.NotOnTimeline("spork() from off the timeline while the kernel is "
                    + "running; use kron.onTimeline(...) or post(at, ...)");
        }
        return sporkAt(now, detach, name, body);
    }

    /**
     * Run {@code task} on the timeline at {@code at}, as a one-shot detached shred.
     *
     * <p>A moment that has already passed is rejected <b>here</b>, in the caller's own stack, rather
     * than wherever the task eventually lands. From off the timeline the placement is deferred to the
     * kernel, and a deferred rejection is one the caller never sees: it would surface later as a
     * failure attributed to a batch that merely happened to be the one draining the inbox.
     *
     * @throws IllegalArgumentException if {@code at} has already passed
     */
    public void post(Moment at, Runnable task) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(task, "task");
        requireNotPast(at);
        if (mayActInline()) {
            postAt(at, task);
        } else {
            postExternal(() -> postAt(at, task));
        }
    }

    /**
     * Run {@code task} at the next moment the kernel observes.
     *
     * <p>Rejected under the virtual clock: an external thread's arrival time is not a reproducible
     * input, and pretending otherwise is how a deterministic test stops being one. Use
     * {@link #post(Moment, Runnable)} there.
     */
    public void post(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (clock.isVirtual()) {
            throw new IllegalStateException("post(Runnable) has no reproducible arrival time under "
                    + "the virtual clock; use post(Moment, Runnable)");
        }
        if (mayActInline()) {
            sporkAt(now, Detach.YES, "post", task);
        } else {
            postExternal(() -> sporkAt(now, Detach.YES, "post", task));
        }
    }

    /**
     * Run until nothing is scheduled — or, on a paced clock with shreds still alive, until
     * {@link #stop()} or {@link #close()}.
     *
     * <p>The difference matters for an application fed from outside. Under the virtual clock an empty
     * timeline with live shreds is a bug (nothing can ever happen, so {@code TimelineStalled} says so),
     * but under a paced clock it is just an application waiting for input, and the kernel parks until
     * something arrives. That is what makes a real event-driven program possible rather than only a
     * scripted one.
     */
    public void run() {
        runUntil(null);
    }

    /**
     * Ask a running {@link #run()} to return. Safe from any thread, including from a shred.
     *
     * <p>Needed because an idle-parked kernel is waiting on external input, not on the timeline, so
     * there is no moment at which to schedule its own shutdown. This wakes it and lets {@code run()}
     * unwind normally; {@link #close()} then cancels whatever is still alive.
     */
    public void stop() {
        stopRequested = true;
        idleGate.open();
    }

    /** Whether {@link #stop()} has been asked for. */
    public boolean isStopping() {
        return stopRequested;
    }

    /**
     * Run until {@code limit}, leaving anything scheduled after it on the timeline. Call again to
     * continue.
     */
    public void runUntil(Moment limit) {
        requireOpen();
        pump(null, limit, true);
        reportFailures();
    }

    /**
     * Step a {@linkplain Clock#driven() driven} clock to <em>now</em>, keeping the origin itself.
     *
     * <p>The form to reach for when adding a clock to an application, and the reason it exists is that
     * the other one has a trap in it. {@link #tick(long)} takes a moment on <b>Kronometer's</b>
     * timeline, which starts at zero — not a wall-clock reading. The obvious first guess is
     * {@code kron.tick(System.nanoTime())}, and that asks the kernel to run every scheduled moment
     * between the epoch and now: measured, seventeen million steps of one 50 Hz domain, about twenty
     * seconds of frozen render thread on the first frame, with no error and nothing in the log.
     *
     * <p>An origin is not a decision anybody wants to make, so this makes it for you: the first call
     * takes a reading and every call after it ticks to the elapsed difference. Hand it straight to a
     * frame loop.
     *
     * <pre>{@code
     * try (Kron kron = Kron.driven()) {
     *     Rate frames = kron.dynamic("frames");
     *     kron.effect(frames, () -> node.x(shown.get()));
     *     app.run(beforeFrame -> kron.tick());       // that is the whole wiring
     * }
     * }</pre>
     */
    public void tick() {
        requireOpen();
        tick(requireDriven().elapsedNanos());
    }

    /**
     * Step a {@linkplain Clock#driven() driven} clock: run the timeline up to {@code elapsedNanos}.
     *
     * <p>In {@link Driven.Mode#INLINE} this returns with the batch complete, so effects have run
     * before the frame is submitted. In {@link Driven.Mode#HANDOFF} it signals and returns.
     *
     * <p>{@code elapsedNanos} is measured from <b>{@link Moment#ORIGIN}</b>, which is where this
     * runtime's logical time starts — not from the epoch, and not from {@link System#nanoTime()}.
     * A host that would rather not keep an origin should call {@link #tick()} instead.
     *
     * <p>Ticks must not go backwards, and one that does throws rather than being quietly absorbed: it
     * used to produce a duplicate frame at the same moment, with a {@code dt} of zero, which is a
     * division waiting to happen in anybody's integrator.
     *
     * <p>How far one tick may carry logical time is bounded — see {@link Driven#maxAdvance(Dur)} —
     * so a breakpoint or a sleeping laptop costs a forgiven gap rather than a replayed one.
     *
     * @throws IllegalArgumentException if {@code elapsedNanos} is before the current moment
     */
    public void tick(long elapsedNanos) {
        requireOpen();
        Driven driven = requireDriven();
        if (elapsedNanos < now.nanos()) {
            throw new IllegalArgumentException(
                    "tick(" + new Dur(elapsedNanos) + ") is before the current moment " + now
                            + "; ticks are elapsed since Moment.ORIGIN and must not go backwards"
                            + " — use tick() to have the kernel keep the origin");
        }
        boolean inline = driven.mode() == Driven.Mode.INLINE;
        hostThread = Thread.currentThread();
        ticks++;
        pump(null, new Moment(driven.logicalFor(elapsedNanos, now.nanos())), inline);
        if (inline) {
            reportFailures();
        }
    }

    public void tick(Moment upTo) {
        tick(upTo.nanos());
    }

    /** How many times {@link #tick} has been called — the answer to "is my clock being driven at all". */
    public long ticks() {
        return ticks;
    }

    private Driven requireDriven() {
        if (!(clock instanceof Driven driven)) {
            throw new IllegalStateException("tick() requires Clock.driven(), not " + clock);
        }
        return driven;
    }

    /**
     * Cancel everything still alive and let it unwind on the timeline, so {@code finally} blocks run.
     * Failures raised during unwinding are discarded — this is the exit path, not a place to learn
     * about problems.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        draining = true;
        stopRequested = true;
        idleGate.open();                        // in case run() is parked waiting for input
        if (!alive.isEmpty()) {
            pump(() -> {
                for (Shred s : List.copyOf(alive)) {
                    s.requestCancel();
                }
            }, null, true);
        }
        stopKernelThread();
        predictor.close();
        failures.clear();
        stall = null;
    }

    // --------------------------------------------------------------- kernel

    /**
     * Hand a batch to the persistent kernel thread.
     *
     * <p>The kernel loop lives on a virtual thread for the whole life of the {@code Kron}, not one per
     * run: M0 measured a 10× penalty for a platform kernel thread, and creating a fresh one per
     * driven tick would be waste on top of that. {@code wait} is what separates {@code INLINE} from
     * {@code HANDOFF}.
     */
    private void pump(Runnable before, Moment limit, boolean wait) {
        ensureKernelThread();
        // From here on nobody outside may touch kernel state directly, and that is permanent: under a
        // driven clock the kernel is idle between ticks, which looks exactly like setup and is not —
        // the next tick may begin at any moment, and a worker acting inline would race it.
        started = true;
        long ticket;
        synchronized (batchLock) {
            batchBefore = before;
            batchLimitNanos = limit == null ? UNBOUNDED : limit.nanos();
            ticket = ++batchRequested;
        }
        kernelStart.open();
        if (!wait) {
            return;
        }
        synchronized (batchLock) {
            while (batchCompleted < ticket) {
                try {
                    batchLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting for the timeline", e);
                }
            }
        }
    }

    private void ensureKernelThread() {
        if (kernelThread == null) {
            kernelThread = Thread.ofVirtual().name("kron-kernel").unstarted(this::kernelLoop);
            kernelThread.start();
        }
    }

    private void kernelLoop() {
        while (true) {
            kernelStart.await();
            if (kernelStopping) {
                return;
            }
            long serving;
            Runnable before;
            long limit;
            synchronized (batchLock) {
                serving = batchRequested;
                before = batchBefore;
                batchBefore = null;
                limit = batchLimitNanos;
            }
            running = true;
            try {
                if (before != null) {
                    before.run();
                }
                loop(limit == UNBOUNDED ? null : new Moment(limit));
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                running = false;
                synchronized (batchLock) {
                    batchCompleted = serving;
                    batchLock.notifyAll();
                }
            }
        }
    }

    private void stopKernelThread() {
        if (kernelThread == null) {
            return;
        }
        kernelStopping = true;
        kernelStart.open();
        try {
            kernelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        kernelThread = null;
    }

    private void loop(Moment limit) {
        boolean dynamicsStepped = false;
        while (true) {
            drainInbox();
            Entry next = peekLive();

            // Nothing scheduled, but the timeline is not over: an externally-fed application spends
            // most of its life here, waiting for input that has not arrived yet. Parking is only ever
            // right for an *unbounded* run on a *paced* clock — a window has an end, and a virtual clock
            // has no wall to wait against, so for it an empty timeline with live shreds is still the
            // stall it always was.
            if (next == null && limit == null && !clock.isVirtual()
                    && !draining && !stopRequested && !alive.isEmpty()) {
                nextDeadlineNanos = Long.MAX_VALUE;
                idleGate.await();
                if (stopRequested) {
                    return;
                }
                continue;
            }

            if (next == null || (limit != null && next.moment().isAfter(limit))) {
                nextDeadlineNanos = next == null ? Long.MAX_VALUE : next.moment().nanos();
                // A bounded run is a window over the timeline, so an empty window is a legitimate
                // outcome: advance to the limit and stop. Only an *unbounded* run can conclude that
                // nothing scheduled means nothing can ever happen — which is the stall (§11).
                if (limit != null) {
                    if (limit.isAfter(now)) {
                        now = limit;
                    }
                    // A dynamic domain steps once per tick, at the tick's own moment and after
                    // everything else in its window — the right order for a render pass reading what
                    // the simulation just produced.
                    if (!dynamicsStepped) {
                        dynamicsStepped = true;
                        if (stepDynamicDomains()) {
                            continue;
                        }
                    }
                } else {
                    detectStall();
                }
                return;
            }
            timeline.poll();
            Moment target = next.moment();
            // The budget for the segment about to run is measured against the *following* deadline,
            // not the one being served — that one is due right now, by definition.
            Entry following = peekLive();
            nextDeadlineNanos = following == null ? Long.MAX_VALUE : following.moment().nanos();
            if (!target.isBefore(now)) {
                long entered;
                try {
                    entered = clock.awaitUntil(target.nanos());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // The clock may report a *later* moment than asked for: a SKIP forgiving a gap, or a
                // hard resync writing off a debt. Logical time never runs backwards.
                if (entered > now.nanos()) {
                    now = new Moment(entered);
                }
            }
            // Otherwise logical time has already jumped past this entry, and it runs coalesced at the
            // new `now` rather than replaying a moment that is gone.
            handOff(next.shred());
        }
    }

    /**
     * The head of the timeline, skipping retracted entries.
     *
     * <p>Skipping deliberately does not touch {@code now}: a deadline that a trigger already
     * superseded must not drag logical time forward to a moment nothing will happen at.
     */
    private Entry peekLive() {
        Entry head;
        while ((head = timeline.peek()) != null && head.isStale()) {
            timeline.poll();
        }
        return head;
    }

    private void handOff(Shred shred) {
        traceEvent(shred, Trace.Kind.RESUME, "");
        shred.gate().open();
        kernelGate.await();
    }

    /** @return whether any dynamic domain had a shred waiting to be stepped */
    private boolean stepDynamicDomains() {
        boolean any = false;
        for (Rate domain : domains) {
            if (domain.kind() == Rate.Kind.DYNAMIC && domain.tick().waiting() > 0) {
                domain.tick().wakeAll();
                any = true;
            }
        }
        return any;
    }

    /**
     * Place work that arrived from off the timeline, and wake both things that might be asleep on it.
     *
     * <p>Every path from outside the timeline into the kernel goes through here, and that is the whole
     * point of it existing: there are two wakes to remember — the kernel parked on {@link #idleGate}
     * under a paced clock, and the <em>host</em> parked on its own event queue under a driven one — and
     * both were previously open-coded at each call site. Forgetting either produces a hang rather than
     * a slowdown, and the second one was in fact missing everywhere, because nothing had ever needed
     * it until a host started sleeping on {@link #isQuiescent()}.
     *
     * <p>Order matters: the work is queued before either wake, so no wake can arrive at a kernel that
     * cannot yet see what it was woken for, and no listener failure can lose the work.
     */
    private void postExternal(Runnable task) {
        inbox.add(task);
        idleGate.open();                        // the kernel may be parked waiting for exactly this
        try {
            workListener.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private void drainInbox() {
        Runnable task;
        while ((task = inbox.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                failures.add(t);
            }
        }
    }

    private void detectStall() {
        if (draining || alive.isEmpty() || !failures.isEmpty()) {
            return;
        }
        stall = new Failures.TimelineStalled(alive.stream().map(Shred::toString).toList());
    }

    private void reportFailures() {
        if (!failures.isEmpty()) {
            List<Throwable> causes = List.copyOf(failures);
            failures.clear();
            stall = null;
            throw new Failures.ShredFailed(causes);
        }
        if (stall != null) {
            Failures.TimelineStalled s = stall;
            stall = null;
            throw s;
        }
    }

    private Shred sporkAt(Moment at, Detach detach, String name, Runnable body) {
        return sporkAt(at, detach, name, 0, body);
    }

    private Shred sporkAt(Moment at, Detach detach, String name, int priority, Runnable body) {
        Shred parent = CURRENT.isBound() ? CURRENT.get() : null;
        Shred shred = new Shred(
                this, nextShredId++, name, body, parent, detach == Detach.YES, priority);
        if (parent != null) {
            parent.addChild(shred);
        }
        alive.add(shred);
        traceEvent(shred, Trace.Kind.SPORK, parent == null ? "" : "by #" + parent.id());
        shred.start();
        enqueue(shred, at, shred.suspensionId());
        return shred;
    }

    private void postAt(Moment at, Runnable task) {
        requireNotPast(at);
        sporkAt(at, Detach.YES, "post", task);
    }

    /**
     * Reject a moment that logical time has already gone past.
     *
     * <p>Checked twice on the deferred path, and deliberately: once at the API boundary so the caller
     * gets the error, and again at placement because {@code now} may have moved in between — under a
     * paced clock the interval between posting and draining is real time.
     */
    private void requireNotPast(Moment at) {
        if (at.isBefore(now)) {
            throw new IllegalArgumentException("moment " + at + " has already passed (now " + now + ")");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("kron is closed");
        }
    }

    // ------------------------------------------------------- package internals

    /** Spork the shred that drives a rate domain, at that domain's priority. */
    Shred sporkDomain(Rate domain, Runnable body) {
        return sporkAt(now, Detach.NO, domain.name(), domain.priority(), body);
    }

    Gate kernelGate() {
        return kernelGate;
    }

    void enqueue(Shred shred, Moment at, long suspensionId) {
        timeline.add(new Entry(at, shred.priority(), seq++, shred, suspensionId));
        if (!running && at.nanos() < nextDeadlineNanos) {
            // Placed between batches — during setup, or by a host thread posting between ticks. The
            // kernel is not looping, so nothing else will notice this entry until it starts again, and
            // a host asking nextDeadline() in the meantime would otherwise be told to sleep on it.
            // While the kernel *is* running, loop() owns this field and derives it more precisely.
            nextDeadlineNanos = at.nanos();
        }
    }

    void traceEvent(Shred shred, Trace.Kind kind, String detail) {
        if (trace != null) {
            trace.record(now, shred.id(), kind, detail);
        }
    }

    void shredEnded(Shred shred) {
        alive.remove(shred);
        traceEvent(shred, Trace.Kind.END, "");
        shred.fireDoneIfAwaited();
    }

    void shredFailed(Shred shred, Throwable failure) {
        traceEvent(shred, Trace.Kind.END, "failed: " + failure.getClass().getSimpleName());
        failures.add(failure);
    }

    void requestCancel(Shred shred) {
        if (mayActInline()) {
            shred.requestCancel();
        } else {
            postExternal(shred::requestCancel);
        }
    }

    /**
     * Restart a domain whose driver parked because it had no handlers left.
     *
     * <p>Same shape as {@link #requestCancel}, and for the same reason: the wake has to happen on the
     * kernel thread, and a registration can arrive from anywhere. Going through the inbox rather than
     * {@link #post(Runnable)} keeps it clock-agnostic — a virtual run registers handlers too.
     */
    void wakeDomain(Rate domain) {
        if (mayActInline()) {
            domain.resumeIfIdle();
        } else {
            postExternal(domain::resumeIfIdle);
        }
    }

    /**
     * The on-timeline check, for adapter modules that must enforce it too.
     *
     * <p>Public because a bridge in another module needs exactly the same guard, and the alternative was
     * for it to reimplement the check against a weaker signal.
     */
    public void requireOnTimelineForBridge(String operation) {
        requireOnTimeline(operation);
    }

    void requireOnTimeline(String operation) {
        if (!mayActInline()) {
            throw new Failures.NotOnTimeline(operation
                    + " must happen on the timeline, from inside a shred"
                    + " — wrap it in kron.onTimeline(...)" + (isHandoff()
                            ? ", which HANDOFF requires even on the thread that ticks"
                            : ""));
        }
    }

    /**
     * Whether the calling thread may touch kernel state directly, rather than crossing the baton.
     *
     * <p>One predicate for every caller, because there used to be three and they disagreed. The old
     * one asked whether a batch was <em>currently</em> running, which under a driven clock is false
     * most of the time — so the same call from the same GUI handler was refused when it happened to
     * land inside a tick and silently raced the timeline when it landed between two. Load-dependent,
     * intermittent, and presenting either as a spurious {@code NotOnTimeline} or as a corrupted
     * priority queue. Three cases are safe and they are all structural rather than temporal:
     *
     * <ol>
     *   <li><b>Inside a shred.</b> The baton is held; that is what the baton is for.</li>
     *   <li><b>Before the kernel has ever started.</b> Setup, with nothing to contend with.</li>
     *   <li><b>The host's own loop thread, between {@code INLINE} ticks.</b> It is the only thread
     *       that starts batches, so while it is executing here none is running and none can begin.
     *       {@code HANDOFF} is excluded because a batch may still be in flight — which is exactly the
     *       race the mode trades away for latency, and the reason it cannot have this concession.</li>
     * </ol>
     *
     * <p>Anything else is a worker thread and is refused <em>every</em> time, which is the property
     * that matters: a rule that holds only under load is not a rule, it is a coin flip you discover in
     * production.
     */
    private boolean mayActInline() {
        if (CURRENT.isBound() || !started) {
            return true;
        }
        return !running && Thread.currentThread() == hostThread && !isHandoff();
    }

    private boolean isHandoff() {
        return clock instanceof Driven driven && driven.mode() == Driven.Mode.HANDOFF;
    }

    /**
     * Everything you want in the log line when an animation is not moving.
     *
     * <p>The first question is always whether the clock is being driven at all, and until this existed
     * there was no way to ask it: a driven runtime that nobody ticks is indistinguishable from one with
     * nothing to do. Ticks, {@code now} and the domains' handler counts answer it between them, and
     * {@link #whyBusy()} answers the opposite question.
     */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("Kron(").append(clock)
                .append(", now ").append(now);
        if (clock instanceof Driven) {
            out.append(", ").append(ticks).append(" ticks");
        }
        out.append(", ").append(alive.size()).append(" shreds")
                .append(", ").append(graph.effects().size()).append(" effects");
        for (Rate domain : domains) {
            out.append(", ").append(domain.name()).append('[')
                    .append(domain.handlerCount()).append(domain.isIdle() ? " idle" : "").append(']');
        }
        out.append(isQuiescent() ? ", quiescent" : ", busy");
        // The one thing a frozen render-on-demand loop needs told and cannot deduce: it is asleep by
        // its own choice and nothing is able to wake it. Driven only — a paced kernel wakes itself.
        if (clock instanceof Driven && workListener == NO_WAKE) {
            out.append(", no wake listener");
        }
        return out.append(')').toString();
    }
}
