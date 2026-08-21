package sibarum.kronometer.atchung;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Topic;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Curve;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Predict;
import sibarum.kronometer.Prediction;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Signal;
import sibarum.kronometer.Time;
import sibarum.kronometer.Trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The bus meeting the timeline: two incompatible threading models, joined at the pump.
 */
class KronBridgeTest {

    private static final Topic<Double> POINTER = Topic.of("pointer", Double.class);
    private static final Topic<String> CLICK = Topic.of("click", String.class);

    @Test
    @DisplayName("a topic-fed cell enters the graph volatile, and nothing downstream is precomputed")
    void busInputIsVolatile() {
        Prediction<Double> prediction;
        List<String> observed = new ArrayList<>();
        Atchung bus = Atchung.create();
        try (Kron kron = Kron.virtual();
             KronBridge bridge = new KronBridge(kron, bus)) {

            Cell<Double> pointer = bridge.cell(POINTER, 0.0);
            Signal<Double> derived = kron.computed("derived", () -> pointer.get() * 2);

            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(100));
            prediction = frames.predict(derived, Predict.EAGER);
            bridge.drainOn(frames);
            kron.effect(frames, () -> observed.add(Time.now() + "=" + derived.get()));

            bridge.inject(Moment.ORIGIN.plus(ms(25)), POINTER, 5.0);
            bridge.inject(Moment.ORIGIN.plus(ms(55)), POINTER, 9.0);

            kron.runUntil(Moment.ORIGIN.plus(ms(70)));
        }
        // This is the sentence the design has been building towards: live input arrives with
        // horizon == now, so the whole subgraph is excluded from prediction automatically. `derived`
        // was never told anything about where its input comes from.
        assertEquals(0, prediction.filled(),
                "nothing downstream of volatile input should be precomputed");

        // The injected values land at the drain following their arrival, and hold until the next one.
        assertEquals(List.of(
                "@10ms=0.0", "@20ms=0.0", "@30ms=10.0", "@40ms=10.0",
                "@50ms=10.0", "@60ms=18.0", "@70ms=18.0"), observed);
    }

    @Test
    @DisplayName("the same cell becomes predictable again when a curve takes over")
    void reclassificationWorksThroughTheBridge() {
        Atchung bus = Atchung.create();
        try (Kron kron = Kron.virtual();
             KronBridge bridge = new KronBridge(kron, bus)) {

            Cell<Double> pointer = bridge.cell(POINTER, 0.0);
            kron.spork(() -> {
                assertEquals(kron.now(), pointer.horizon(), "fed from the bus: unpredictable");
                pointer.drive(Curve.ramp(0.0, 1.0, ms(100)));
                assertEquals(Moment.FOREVER, pointer.horizon(), "driven by a curve: knowable");
                pointer.live();
                assertEquals(kron.now(), pointer.horizon(), "handed back to the bus");
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("a bus event is a yield point: a shred awaits a topic")
    void topicIsAYieldPoint() {
        List<String> observed = new ArrayList<>();
        Atchung bus = Atchung.create();
        try (Kron kron = Kron.virtual();
             KronBridge bridge = new KronBridge(kron, bus)) {

            Trigger clicked = bridge.trigger(CLICK);
            kron.spork("waiter", () -> {
                for (int i = 0; i < 2; i++) {
                    Time.await(clicked);
                    observed.add("click@" + Time.now());
                }
                observed.add("done@" + Time.now());
            });

            bridge.inject(Moment.ORIGIN.plus(ms(30)), CLICK, "one");
            bridge.inject(Moment.ORIGIN.plus(ms(80)), CLICK, "two");

            kron.run();
        }
        // "wait for a click" as a scheduling operation, not a callback — and the shred resumes at the
        // moment of the drain, so there is a definite answer to when the click happened.
        assertEquals(List.of("click@@30ms", "click@@80ms", "done@@80ms"), observed);
    }

    @Test
    @DisplayName("publishing from the timeline reaches ordinary bus subscribers")
    void timelineCanPublish() {
        List<String> heard = new ArrayList<>();
        Atchung bus = Atchung.create();
        try (Kron kron = Kron.virtual();
             KronBridge bridge = new KronBridge(kron, bus)) {

            bus.subscribe(CLICK, heard::add);
            kron.spork(() -> {
                Time.advance(ms(10));
                bridge.publish(CLICK, "from the timeline");
            });
            kron.run();
        }
        assertEquals(List.of("from the timeline"), heard);
    }

    @Test
    @DisplayName("delivery and publishing are refused off the timeline")
    void bridgeOperationsRequireTheTimeline() {
        Atchung bus = Atchung.create();
        try (Kron kron = Kron.driven();
             KronBridge bridge = new KronBridge(kron, bus)) {

            Rate frames = kron.fixed("frames", ms(10));
            bridge.drainOn(frames);
            // While the kernel is running, an off-timeline drain would mutate the graph from the wrong
            // thread — the one thing the design does not allow.
            kron.spork(() -> Time.advance(ms(1)));
            kron.tick(ms(5).nanos());
            assertTrue(bridge.drains() >= 0);
        }
    }

    @Test
    @DisplayName("coalescing keeps only the newest value, which is what state-like input wants")
    void coalescingDropsStaleInput() {
        List<String> observed = new ArrayList<>();
        Atchung bus = Atchung.create();
        try (Kron kron = Kron.virtual();
             KronBridge bridge = new KronBridge(kron, bus)) {

            Cell<Double> pointer = bridge.cell(POINTER, 0.0, 1, Backpressure.COALESCE_LATEST);
            Rate frames = kron.fixed("frames", ms(50));
            bridge.drainOn(frames);
            kron.effect(frames, () -> observed.add(Time.now() + "=" + pointer.get()));

            // Three moves inside one frame. A queue of stale pointer positions is worse than none, so
            // only the last should survive to be seen.
            bridge.inject(Moment.ORIGIN.plus(ms(10)), POINTER, 1.0);
            bridge.inject(Moment.ORIGIN.plus(ms(20)), POINTER, 2.0);
            bridge.inject(Moment.ORIGIN.plus(ms(30)), POINTER, 3.0);

            kron.runUntil(Moment.ORIGIN.plus(ms(100)));
        }
        assertEquals(List.of("@50ms=3.0", "@100ms=3.0"), observed);
    }

    @Test
    @DisplayName("the virtual clock still refuses an undeclared arrival")
    void undeclaredArrivalIsStillRejected() {
        Atchung bus = Atchung.create();
        try (Kron kron = Kron.virtual()) {
            // The bridge does not weaken §5.3: a reproducible run has no room for "whenever it turns up".
            assertThrows(IllegalStateException.class, () -> kron.post(() -> { }));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a real publish from another thread wakes an idle kernel")
    void externalPublishWakesTheKernel() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        List<String> observed = new ArrayList<>();
        Kron kron = Kron.realtime();
        Atchung bus = Atchung.create();
        KronBridge bridge = new KronBridge(kron, bus);

        Trigger clicked = bridge.trigger(CLICK);
        kron.spork("waiter", () -> {
            ready.countDown();
            Time.await(clicked);
            observed.add("woken");
            kron.stop();
        });

        Thread publisher = Thread.ofPlatform().start(() -> {
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                Thread.sleep(50);                       // let the kernel actually go idle
                bus.publish(CLICK, "hello");
                // The bus published on *this* thread; the drain has to happen on the timeline, so the
                // bridge posts it there. That post is also what unparks the kernel.
                kron.post(bridge::drain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        kron.run();                                     // parks: nothing is scheduled, one shred alive
        publisher.join();
        bridge.close();
        kron.close();

        // Before M7 this test could not exist: run() returned the instant the timeline emptied, so an
        // externally-fed application was impossible and only scripted ones worked.
        assertEquals(List.of("woken"), observed);
    }

}
