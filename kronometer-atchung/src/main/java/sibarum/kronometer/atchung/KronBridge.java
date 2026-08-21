package sibarum.kronometer.atchung;

import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Pump;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Joins an Atchung bus to a Kronometer timeline.
 *
 * <p>Two systems with incompatible threading models, which is the whole problem. The bus publishes on
 * whatever thread published — that is what makes it fast — while the timeline is single-threaded by
 * construction, because that is what makes it ordered. Something has to marshal, and the bus already
 * has the right tool for it.
 *
 * <h2>The pump is the seam</h2>
 *
 * Atchung's {@link Pump} exists precisely so a consumer can choose the thread events arrive on: queue
 * them from any publisher, deliver them on the thread that calls {@code drain()}. So the bridge
 * subscribes pumped, and the <b>kernel drains the pump on the timeline</b>. Every event then arrives at
 * a definite moment, in a definite order, with the baton held — which is the only way an event can
 * enter the graph at all.
 *
 * <pre>{@code
 * KronBridge bridge = new KronBridge(kron, bus);
 * Cell<Point> pointer = bridge.cell(POINTER, Point.ORIGIN);   // live input, horizon == now
 * bridge.drainOn(frames);                                     // once per frame, on the timeline
 * }</pre>
 *
 * <h2>Where the events land, and why that is not a choice</h2>
 *
 * A pumped event is delivered at the moment of the drain, so input latency is bounded by the draining
 * domain's period — one frame, for a GUI. Lower latency would mean delivering on the publisher's thread,
 * which would mean mutating the graph from off the timeline, which is the one thing the design does not
 * permit. The frame of latency is the price of the ordering guarantee, and it is the same price every
 * retained-mode GUI pays.
 *
 * <p>{@linkplain Kron#virtual() Virtual} runs are the exception, deliberately: an external thread's
 * arrival time is not a reproducible input, so a deterministic test scripts arrivals with
 * {@link #inject} instead of publishing them. That restriction is not this class's invention — it is
 * {@code post(Runnable)} refusing to pretend, inherited from §5.3.
 */
public final class KronBridge implements AutoCloseable {

    private final Kron kron;
    private final Atchung bus;
    private final Pump pump;
    private final List<Subscription> subscriptions = new ArrayList<>();

    private long delivered;
    private long drains;

    public KronBridge(Kron kron, Atchung bus) {
        this.kron = Objects.requireNonNull(kron, "kron");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.pump = bus.pump();
    }

    public Kron kron() {
        return kron;
    }

    public Atchung bus() {
        return bus;
    }

    // -------------------------------------------------------- bus -> timeline

    /**
     * A cell fed by {@code topic}, declared {@linkplain Cell#live() volatile}.
     *
     * <p>This is the sentence the whole design has been building towards: live input enters the graph
     * with {@code horizon == now}, so everything derived from it is automatically excluded from
     * precomputation, and nothing downstream had to be told. Swap the same cell to a curve later and the
     * subgraph becomes predictable again, still without anything downstream changing.
     *
     * @param capacity     mailbox bound per drain
     * @param backpressure what happens when the mailbox is full. {@link Backpressure#COALESCE_LATEST} is
     *                     usually right for state-like input such as a pointer position: only the newest
     *                     matters, and a queue of stale positions is worse than none
     */
    public <T> Cell<T> cell(Topic<T> topic, T initial, int capacity, Backpressure backpressure) {
        Objects.requireNonNull(topic, "topic");
        Cell<T> cell = kron.cell(topic.name(), initial);
        cell.live();
        subscriptions.add(pump.subscribe(topic, event -> {
            delivered++;
            cell.set(event);
        }, capacity, backpressure));
        return cell;
    }

    /** A cell fed by {@code topic}, coalescing to the newest value — the right default for state. */
    public <T> Cell<T> cell(Topic<T> topic, T initial) {
        return cell(topic, initial, 1, Backpressure.COALESCE_LATEST);
    }

    /**
     * A trigger fired whenever {@code topic} publishes, so a shred can {@code await} it.
     *
     * <p>The woken shred resumes at the moment of the drain, which makes a bus event a yield point:
     * {@code await(bridge.trigger(CLICK))} reads as "wait for a click" and is a scheduling operation
     * rather than a callback.
     */
    public <T> Trigger trigger(Topic<T> topic, int capacity, Backpressure backpressure) {
        Objects.requireNonNull(topic, "topic");
        Trigger trigger = kron.trigger(topic.name());
        subscriptions.add(pump.subscribe(topic, event -> {
            delivered++;
            trigger.broadcast();
        }, capacity, backpressure));
        return trigger;
    }

    public <T> Trigger trigger(Topic<T> topic) {
        return trigger(topic, 64, Backpressure.DROP_OLDEST);
    }

    /**
     * Deliver whatever the bus has queued, on the timeline, now.
     *
     * <p>Must be called from a shred — delivery writes cells and fires triggers, and both belong to the
     * timeline.
     *
     * @return how many events were delivered
     */
    public int drain() {
        kron.requireOnTimelineForBridge("drain()");
        drains++;
        return pump.drain();
    }

    /** Drain once per step of {@code domain} — the normal way to wire input into a running app. */
    public void drainOn(Rate domain) {
        Objects.requireNonNull(domain, "domain");
        kron.effect(domain, this::drain);
    }

    /** Whether the bus is holding events that no drain has delivered yet. */
    public boolean hasPending() {
        return pump.hasPending();
    }

    // -------------------------------------------------------- timeline -> bus

    /**
     * Publish on the bus from the timeline.
     *
     * <p>Deliberately thin. Publishing is already non-blocking and thread-safe, so there is nothing to
     * marshal on the way out — the asymmetry is real, and pretending otherwise would only add a queue
     * nobody needs. The one thing this adds is the check that you are on the timeline, so an event's
     * position in the total order is defined.
     */
    public <T> void publish(Topic<T> topic, T event) {
        kron.requireOnTimelineForBridge("publish()");
        bus.publish(topic, event);
    }

    // ------------------------------------------------------------- scripting

    /**
     * Publish {@code event} as if it had arrived at {@code at} — for deterministic tests.
     *
     * <p>The virtual clock rejects undeclared external arrivals, and this is how you satisfy it: give
     * the moment, and the scenario becomes reproducible. A ten-minute input script then runs in
     * microseconds and produces the same trace every time, which is what makes the Tactroller harness
     * (§15.4) worth building at all.
     */
    public <T> void inject(Moment at, Topic<T> topic, T event) {
        Objects.requireNonNull(at, "at");
        kron.post(at, () -> {
            bus.publish(topic, event);
            pump.drain();
        });
    }

    // ----------------------------------------------------------- diagnostics

    /** How many events have crossed from the bus onto the timeline. */
    public long delivered() {
        return delivered;
    }

    /** How many times the pump has been drained. */
    public long drains() {
        return drains;
    }

    @Override
    public void close() {
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
    }

    @Override
    public String toString() {
        return "KronBridge(" + subscriptions.size() + " topics, " + delivered + " delivered)";
    }
}
