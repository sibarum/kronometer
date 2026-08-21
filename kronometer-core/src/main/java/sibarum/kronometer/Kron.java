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
    private volatile long nextDeadlineNanos = Long.MAX_VALUE;

    private long seq;
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
     */
    public Effect effect(Rate domain, Runnable body) {
        Objects.requireNonNull(domain, "domain");
        Effect effect = new Effect(this, "effect@" + domain.name(), body, false);
        effect.bindShred(domain.each(step -> effect.run()));
        return effect;
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
        if (running && !CURRENT.isBound()) {
            throw new Failures.NotOnTimeline(
                    "spork() from off the timeline while the kernel is running; use post(at, ...)");
        }
        return sporkAt(now, detach, name, body);
    }

    /** Run {@code task} on the timeline at {@code at}, as a one-shot detached shred. */
    public void post(Moment at, Runnable task) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(task, "task");
        if (CURRENT.isBound() || !running) {
            postAt(at, task);
        } else {
            inbox.add(() -> postAt(at, task));
            idleGate.open();                    // the kernel may be parked waiting for exactly this
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
        if (CURRENT.isBound() || !running) {
            sporkAt(now, Detach.YES, "post", task);
        } else {
            inbox.add(() -> sporkAt(now, Detach.YES, "post", task));
            idleGate.open();
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
     * Step a {@linkplain Clock#driven() driven} clock: run the timeline up to {@code wallNanos}.
     *
     * <p>In {@link Driven.Mode#INLINE} this returns with the batch complete, so effects have run
     * before the frame is submitted. In {@link Driven.Mode#HANDOFF} it signals and returns.
     */
    public void tick(long wallNanos) {
        requireOpen();
        if (!(clock instanceof Driven driven)) {
            throw new IllegalStateException("tick() requires Clock.driven(), not " + clock);
        }
        boolean inline = driven.mode() == Driven.Mode.INLINE;
        pump(null, new Moment(wallNanos), inline);
        if (inline) {
            reportFailures();
        }
    }

    public void tick(Moment upTo) {
        tick(upTo.nanos());
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
        if (at.isBefore(now)) {
            throw new IllegalArgumentException("moment " + at + " has already passed (now " + now + ")");
        }
        sporkAt(at, Detach.YES, "post", task);
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
        if (CURRENT.isBound() || !running) {
            shred.requestCancel();
        } else {
            inbox.add(shred::requestCancel);
            idleGate.open();
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
        if (running && !CURRENT.isBound()) {
            throw new Failures.NotOnTimeline(
                    operation + " must happen on the timeline, from inside a shred");
        }
    }
}
