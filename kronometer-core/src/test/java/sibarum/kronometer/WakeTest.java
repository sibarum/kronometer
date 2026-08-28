package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The other half of render-on-demand: a host asleep on {@link Kron#isQuiescent()} has to be woken when
 * work arrives from a thread that is not it.
 *
 * <p>{@link QuiescenceTest} covers the half that says <em>you may sleep</em>. This covers the half that
 * ends the sleep, and every test here posts from a real second thread — a wake observed on the timeline
 * would prove nothing, because a host on the timeline is by definition awake.
 */
class WakeTest {

    /** Run {@code work} somewhere that is genuinely not the timeline and not the host, and wait for it. */
    private static void offTimeline(Runnable work) {
        Thread worker = Thread.ofPlatform().name("worker").unstarted(work);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the worker", e);
        }
    }

    @Test
    @DisplayName("work posted from a worker wakes a host that was told it could sleep")
    void postFromAWorkerWakesTheHost() {
        try (Kron kron = Kron.driven()) {
            AtomicInteger woken = new AtomicInteger();
            List<String> ran = new ArrayList<>();
            kron.onWork(woken::incrementAndGet);

            kron.tick(ms(16).nanos());
            assertTrue(kron.isQuiescent(), "nothing scheduled: the host is entitled to sleep here");
            assertEquals(0, woken.get(), "and nothing has happened to end that sleep");

            offTimeline(() -> kron.onTimeline(() -> ran.add("worker")));

            assertEquals(1, woken.get(), "the kernel has work and the host is asleep on it");
            assertFalse(kron.isQuiescent());

            kron.tick(ms(32).nanos());
            assertEquals(List.of("worker"), ran);
        }
    }

    @Test
    @DisplayName("a cell set from a worker wakes the host, which is the whole animation-from-a-click case")
    void drivingACellFromAWorkerWakesTheHost() {
        try (Kron kron = Kron.driven()) {
            AtomicInteger woken = new AtomicInteger();
            List<Double> shown = new ArrayList<>();
            Cell<Double> lift = kron.cell("lift", 0.0);
            Rate frames = kron.dynamic("frames");
            kron.bind(frames, lift, shown::add);
            kron.onWork(woken::incrementAndGet);

            kron.tick(ms(0).nanos());
            assertTrue(kron.isQuiescent());

            offTimeline(() -> kron.onTimeline(() -> lift.drive(Curve.ramp(0.0, 1.0, ms(100)))));

            assertEquals(1, woken.get());
            kron.tick(ms(16).nanos());
            assertFalse(kron.isQuiescent(), "a curve is in flight now");
            assertEquals(2, shown.size());
        }
    }

    @Test
    @DisplayName("work the timeline schedules for itself needs no wake")
    void timelineWorkDoesNotWake() {
        try (Kron kron = Kron.driven()) {
            AtomicInteger woken = new AtomicInteger();
            kron.onWork(woken::incrementAndGet);

            kron.spork("sleeper", () -> Time.advance(ms(50)));
            kron.tick(ms(16).nanos());
            kron.tick(ms(50).nanos());

            // INLINE returns with the batch complete, so nextDeadline() already accounts for everything
            // the batch scheduled. A wake here would be a wake for a host that is demonstrably awake.
            assertEquals(0, woken.get());
        }
    }

    @Test
    @DisplayName("every arrival wakes, not only the one that finds the inbox empty")
    void everyArrivalWakes() {
        try (Kron kron = Kron.driven()) {
            AtomicInteger woken = new AtomicInteger();
            kron.onWork(woken::incrementAndGet);
            kron.tick(ms(16).nanos());

            offTimeline(() -> {
                kron.onTimeline(() -> { });
                kron.onTimeline(() -> { });
                kron.onTimeline(() -> { });
            });

            // A transition test is the obvious optimisation and it races the kernel's own drain. A
            // redundant wake costs one loop iteration that finds nothing to do; a missed one costs a
            // window that never comes back.
            assertEquals(3, woken.get());
        }
    }

    @Test
    @DisplayName("a listener that throws is reported as a failure, and the work still lands")
    void aThrowingListenerStillPosts() {
        try (Kron kron = Kron.driven()) {
            List<String> ran = new ArrayList<>();
            kron.onWork(() -> {
                throw new IllegalStateException("the host window is gone");
            });

            kron.tick(ms(16).nanos());
            offTimeline(() -> kron.onTimeline(() -> ran.add("worker")));

            // The post is placed before the listener runs, so a broken wake cannot lose work — it can
            // only delay it until something else happens to tick.
            assertThrows(Failures.ShredFailed.class, () -> kron.tick(ms(32).nanos()));
            assertEquals(List.of("worker"), ran);
        }
    }

    @Test
    @DisplayName("without a listener the kernel is silent, which is the default a paced run wants")
    void noListenerIsFine() {
        try (Kron kron = Kron.driven()) {
            List<String> ran = new ArrayList<>();
            kron.tick(ms(16).nanos());
            offTimeline(() -> kron.onTimeline(() -> ran.add("worker")));
            kron.tick(ms(32).nanos());
            assertEquals(List.of("worker"), ran);
        }
    }

    @Test
    @DisplayName("a driven runtime with no listener says so, because a frozen loop cannot deduce it")
    void toStringNamesTheMissingListener() {
        try (Kron kron = Kron.driven()) {
            kron.tick(ms(16).nanos());
            assertTrue(kron.toString().contains("no wake listener"), kron.toString());

            kron.onWork(() -> { });
            assertFalse(kron.toString().contains("no wake listener"), kron.toString());
        }
    }

    // ------------------------------------------------------- the guardrails

    @Test
    @DisplayName("sleepTimeout() refuses to say FOREVER until a wake is wired, so it cannot deadlock")
    void sleepTimeoutWillNotHandOutAnIndefiniteBlock() {
        try (Kron kron = Kron.driven()) {
            kron.tick(ms(16).nanos());
            assertTrue(kron.isQuiescent(), "there is genuinely nothing to do");

            // ... and yet the host must not be told to block forever, because nothing could end it.
            // Zero means "loop round again", which is only wasteful.
            assertEquals(Dur.ZERO, kron.sleepTimeout());

            kron.onWork(() -> { });
            assertEquals(Dur.FOREVER, kron.sleepTimeout());
        }
    }

    @Test
    @DisplayName("sleepTimeout() is the remaining wall time when there is a deadline")
    void sleepTimeoutIsTheRemainingTime() {
        try (Kron kron = Kron.driven()) {
            kron.onWork(() -> { });
            kron.spork("late", () -> Time.advance(ms(100)));
            kron.tick(ms(16).nanos());

            assertEquals(ms(84), kron.sleepTimeout());
        }
    }

    @Test
    @DisplayName("a worker touching the graph is refused every time, not only when it lands in a tick")
    void aWorkerIsRefusedConsistently() {
        try (Kron kron = Kron.driven()) {
            Cell<Double> lift = kron.cell("lift", 0.0);
            kron.tick(ms(16).nanos());

            // The old predicate asked whether a batch was running *right now*, so this same call was
            // refused from inside a tick and silently raced the timeline between two. Off the timeline
            // is off the timeline, whatever the kernel happens to be doing this instant.
            List<Throwable> refused = new ArrayList<>();
            offTimeline(() -> {
                try {
                    lift.set(1.0);
                } catch (Throwable t) {
                    refused.add(t);
                }
            });

            assertEquals(1, refused.size(), "a worker must never be allowed to act inline");
            assertTrue(refused.get(0) instanceof Failures.NotOnTimeline);
            assertTrue(refused.get(0).getMessage().contains("onTimeline"),
                    "and the message has to name the fix: " + refused.get(0).getMessage());
            assertEquals(0.0, lift.get(), "nothing was written");
        }
    }

    @Test
    @DisplayName("the thread that ticks may still act between ticks — the documented host wiring")
    void theHostThreadMayActBetweenTicks() {
        try (Kron kron = Kron.driven()) {
            Cell<Double> lift = kron.cell("lift", 0.0);
            Rate frames = kron.dynamic("frames");
            List<Double> shown = new ArrayList<>();
            kron.bind(frames, lift, shown::add);

            kron.tick(ms(0).nanos());
            lift.set(1.0);                      // bridge.drain() and friends do exactly this
            kron.tick(ms(16).nanos());

            assertEquals(List.of(0.0, 1.0), shown);
        }
    }
}
