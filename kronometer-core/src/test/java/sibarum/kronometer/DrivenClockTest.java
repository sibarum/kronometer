package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Time.now;

/**
 * The driven clock: logical time follows a tick stream supplied by a render loop.
 */
class DrivenClockTest {

    private final ConcurrentLinkedQueue<String> log = new ConcurrentLinkedQueue<>();

    @Test
    @DisplayName("INLINE returns with the batch complete, so effects run before the frame is submitted")
    void inlineCompletesBeforeReturning() {
        try (Kron kron = Kron.driven()) {
            kron.spork("frames", () -> {
                Metro metro = Metro.of(ms(16));
                while (true) {
                    metro.tick();
                    log.add("frame@" + now());
                }
            });

            kron.tick(ms(50).nanos());
            // The guarantee that matters: everything due by 50 ms has already happened, right here,
            // with no waiting and no polling.
            assertEquals(List.of("frame@@16ms", "frame@@32ms", "frame@@48ms"), List.copyOf(log));
            assertEquals(ms(50), kron.now().since(Moment.ORIGIN));

            kron.tick(ms(100).nanos());
            assertEquals(6, log.size());
            assertEquals(ms(100), kron.now().since(Moment.ORIGIN));
        }
    }

    @Test
    @DisplayName("a tick with nothing scheduled still advances logical time to it")
    void emptyFrameStillAdvancesTime() {
        try (Kron kron = Kron.driven()) {
            kron.tick(ms(33).nanos());
            assertEquals(ms(33), kron.now().since(Moment.ORIGIN));
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("HANDOFF returns without waiting for the batch")
    void handoffDoesNotWaitForTheBatch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reached = new CountDownLatch(1);

        try (Kron kron = Kron.of(Clock.driven(Driven.Mode.HANDOFF))) {
            kron.spork("blocker", () -> {
                reached.countDown();
                try {
                    release.await();          // blocking on the timeline is legal (§10)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.add("finished");
            });

            // If HANDOFF waited for the batch, this call would never return — the shred is blocked on
            // a latch only this thread can release. Reaching the next line *is* the assertion.
            kron.tick(ms(16).nanos());
            assertTrue(reached.await(10, TimeUnit.SECONDS), "the batch should be running");
            assertEquals(List.of(), List.copyOf(log), "and not yet finished");

            release.countDown();
        }
        assertEquals(List.of("finished"), List.copyOf(log));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("ticks arriving during a HANDOFF batch coalesce into the newest deadline")
    void handoffCoalescesTicks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reached = new CountDownLatch(1);

        try (Kron kron = Kron.of(Clock.driven(Driven.Mode.HANDOFF))) {
            kron.spork("blocker", () -> {
                reached.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            kron.spork("frames", () -> {
                Metro metro = Metro.of(ms(10));
                while (true) {
                    metro.tick();
                    log.add("frame@" + now());
                }
            });

            kron.tick(ms(10).nanos());
            assertTrue(reached.await(10, TimeUnit.SECONDS));

            // Three more frames' worth of ticks while the kernel is stuck. They must collapse into one
            // pending batch at 40 ms, not queue up four separate ones.
            kron.tick(ms(20).nanos());
            kron.tick(ms(30).nanos());
            kron.tick(ms(40).nanos());
            release.countDown();

            // The delivery is asynchronous by definition, so wait for it rather than for close() —
            // close() cancels whatever is still pending, and would race this to the finish.
            assertTrue(awaitSize(4), "coalesced batch should reach 40 ms, saw " + log);
            assertEquals(List.of("frame@@10ms", "frame@@20ms", "frame@@30ms", "frame@@40ms"),
                    List.copyOf(log));
            assertEquals(ms(40), kron.now().since(Moment.ORIGIN));
        }
    }

    /** Wait for asynchronous delivery. The one place in the suite that watches a real clock. */
    private boolean awaitSize(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (log.size() >= expected) {
                return true;
            }
            Thread.sleep(1);
        }
        return log.size() >= expected;
    }

    @Test
    @DisplayName("tick() is refused on a clock that is not driven")
    void tickRequiresADrivenClock() {
        try (Kron kron = Kron.virtual()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> kron.tick(0));
            assertTrue(e.getMessage().contains("driven"));
        }
    }

    @Test
    @DisplayName("post(Runnable) is allowed once the clock is not virtual")
    void undeclaredPostIsFineOffTheVirtualClock() {
        try (Kron kron = Kron.driven()) {
            kron.post(() -> log.add("posted@" + now()));
            kron.tick(ms(1).nanos());
            assertEquals(List.of("posted@@0s"), List.copyOf(log));
        }
    }
}
