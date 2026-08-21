package sibarum.kronometer;

import java.util.ArrayList;
import java.util.List;

/**
 * A unit of strongly-timed execution: ordinary Java that survives across time.
 *
 * <p>A shred runs, says "let 250 milliseconds pass", and picks up where it left off with its stack
 * intact. Between two such statements it executes in <em>zero logical time</em>, however long the CPU
 * actually took, and it holds the baton for that whole segment — so a segment is atomic with respect
 * to every other shred.
 *
 * <p>A shred is a virtual thread. Ten thousand parked shreds cost a few megabytes, not ten thousand
 * OS threads. What they do cost is a baton handoff each time they wake — ~577 ns under native-image —
 * so <em>anything pure should be a signal, not a shred</em>.
 *
 * <p>This class is the handle. The time intrinsics live in {@link Time}.
 */
public final class Shred {

    enum State {
        /** Created, never yet given the baton. */
        NEW,
        /** On the timeline, waiting for its moment. */
        SCHEDULED,
        /** Holding the baton. */
        RUNNING,
        /** Waiting on a trigger, possibly with a timeout. */
        WAITING,
        /** Body returned, threw, or was cancelled. */
        ENDED
    }

    private final Kron kron;
    private final long id;
    private final String name;
    private final Runnable body;
    private final Shred parent;
    private final boolean detached;
    private final int priority;
    private final List<Shred> children = new ArrayList<>();
    private final Gate gate = new Gate();

    private Thread thread;
    private State state = State.NEW;
    private long suspensionId;
    private boolean cancelRequested;
    private Trigger waitingOn;
    private boolean wokenByTrigger;
    private Trigger done;

    Shred(Kron kron, long id, String name, Runnable body, Shred parent, boolean detached,
          int priority) {
        this.priority = priority;
        this.kron = kron;
        this.id = id;
        this.name = name == null ? "shred" : name;
        this.body = body;
        this.parent = parent;
        this.detached = detached;
    }

    // ------------------------------------------------------------------ API

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean isAlive() {
        return state != State.ENDED;
    }

    public Kron kron() {
        return kron;
    }

    /**
     * A trigger fired at the moment this shred ends, however it ends.
     *
     * <p>{@code await(child.done())} is how one shred waits for another without leaving the timeline.
     */
    public Trigger done() {
        if (done == null) {
            done = new Trigger(kron, name + "#" + id + ".done");
        }
        return done;
    }

    /**
     * Ask this shred to stop.
     *
     * <p>It is removed from the timeline and, if parked, resumed just long enough to throw
     * {@link Failures.ShredCancelled} at its yield point — so {@code finally} blocks and
     * try-with-resources run normally, on the timeline, at the current moment. A cancelled shred
     * cannot advance time again, so cleanup is inherently bounded.
     *
     * <p>Cancelling a shred cancels its children, unless they were sporked {@link Detach#YES}.
     * <b>Children unwind before their parent</b> — the cancellation walks the tree depth-first and
     * enqueues each shred as it goes, so the deeper the shred, the lower its sequence number and the
     * earlier it runs. That is the same order as nested try-with-resources, and it is the order a
     * parent needs if its cleanup is to see its children already finished.
     *
     * <p>Safe to call from off the timeline: the request is delivered at the next moment the kernel
     * observes.
     */
    public void cancel() {
        kron.requestCancel(this);
    }

    /**
     * Wait for this shred to end, from <em>off</em> the timeline.
     *
     * <p>Not usable from inside a shred — blocking there would hold the baton and stop logical time
     * for everyone. Use {@code await(shred.done())} instead, which is the same wait expressed as a
     * yield point.
     */
    public void join() throws InterruptedException {
        if (Kron.CURRENT.isBound()) {
            throw new Failures.NotOnTimeline(
                    "join() would hold the baton and stop logical time; use await(shred.done())");
        }
        Thread t = thread;
        if (t != null) {
            t.join();
        }
    }

    @Override
    public String toString() {
        return name + "#" + id + "(" + state + ")";
    }

    // -------------------------------------------------------------- kernel

    State state() {
        return state;
    }

    /** Rate-domain priority: the tie-break between domains waking at the same moment (rule 4). */
    int priority() {
        return priority;
    }

    Gate gate() {
        return gate;
    }

    long suspensionId() {
        return suspensionId;
    }

    boolean isDetached() {
        return detached;
    }

    Shred parent() {
        return parent;
    }

    List<Shred> children() {
        return children;
    }

    void addChild(Shred child) {
        children.add(child);
    }

    boolean isWaitingOn(Trigger t) {
        return state == State.WAITING && waitingOn == t;
    }

    /** Start the carrier thread. It parks on the gate until the kernel hands it the baton. */
    void start() {
        thread = Thread.ofVirtual().name("shred-" + id).unstarted(this::run);
        thread.start();
    }

    private void run() {
        gate.await();
        state = State.RUNNING;
        try {
            checkCancelled();
            ScopedValue.where(Kron.CURRENT, this).run(body);
        } catch (Failures.ShredCancelled e) {
            // Expected: cancellation unwound the shred and its finally blocks have run.
        } catch (Throwable t) {
            kron.shredFailed(this, t);
        } finally {
            state = State.ENDED;
            kron.shredEnded(this);
            kron.kernelGate().open();
        }
    }

    /** Yield the baton, to be given it back at {@code wake}. */
    void suspendUntil(Moment wake) {
        checkCancelled();
        long token = ++suspensionId;
        state = State.SCHEDULED;
        kron.enqueue(this, wake, token);
        kron.traceEvent(this, Trace.Kind.ADVANCE, wake.toString());
        release();
    }

    /**
     * Yield the baton to wait on a trigger, optionally with a deadline.
     *
     * @return whether the trigger fired, rather than the deadline passing
     */
    boolean suspendOnTrigger(Trigger trigger, Moment deadline) {
        checkCancelled();
        long token = ++suspensionId;
        state = State.WAITING;
        waitingOn = trigger;
        wokenByTrigger = false;
        trigger.enrol(this);
        if (deadline != null) {
            kron.enqueue(this, deadline, token);
        }
        kron.traceEvent(this, Trace.Kind.AWAIT, trigger.name());
        release();
        return wokenByTrigger;
    }

    /**
     * Bring this shred back at the current moment because a trigger fired.
     *
     * <p>Bumping the suspension token is what retracts any deadline entry still on the timeline: the
     * old entry no longer matches and the kernel discards it without advancing time.
     */
    void wakeFromTrigger() {
        wokenByTrigger = true;
        long token = ++suspensionId;
        kron.enqueue(this, kron.now(), token);
        kron.traceEvent(this, Trace.Kind.WOKEN, "");
    }

    void fireDoneIfAwaited() {
        if (done != null) {
            done.wakeAll();
        }
    }

    /** Called on the timeline. Marks the shred, its pending wake, and its children. */
    void requestCancel() {
        if (state == State.ENDED || cancelRequested) {
            return;
        }
        cancelRequested = true;
        kron.traceEvent(this, Trace.Kind.CANCEL, "");
        for (Shred child : List.copyOf(children)) {
            if (!child.detached) {
                child.requestCancel();
            }
        }
        if (state == State.SCHEDULED || state == State.WAITING || state == State.NEW) {
            long token = ++suspensionId;
            kron.enqueue(this, kron.now(), token);
        }
    }

    private void release() {
        kron.kernelGate().open();
        gate.await();
        state = State.RUNNING;
        waitingOn = null;
        checkCancelled();
    }

    private void checkCancelled() {
        if (cancelRequested) {
            throw new Failures.ShredCancelled(this + " cancelled");
        }
    }
}
