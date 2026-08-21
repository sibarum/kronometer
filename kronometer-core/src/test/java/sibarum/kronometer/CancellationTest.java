package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.s;
import static sibarum.kronometer.Time.advance;
import static sibarum.kronometer.Time.await;
import static sibarum.kronometer.Time.now;
import static sibarum.kronometer.Time.spork;

/**
 * Cancellation has to be a first-class, on-timeline event rather than a thread interrupt, because
 * cleanup that runs at an unknown moment is not cleanup a strongly-timed program can reason about.
 */
class CancellationTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("finally runs on the timeline, at the moment of the cancellation")
    void finallyRunsOnTheTimelineAtTheCancellationMoment() {
        AtomicReference<Moment> cleanupAt = new AtomicReference<>();
        try (Kron kron = Kron.virtual()) {
            Shred victim = kron.spork("victim", () -> {
                try {
                    advance(s(10));
                    log.add("unreachable");
                } finally {
                    cleanupAt.set(now());
                    log.add("cleanup");
                }
            });
            kron.spork("killer", () -> {
                advance(ms(100));
                victim.cancel();
            });
            kron.run();
        }
        assertEquals(List.of("cleanup"), log);
        assertEquals(Moment.ORIGIN.plus(ms(100)), cleanupAt.get());
    }

    @Test
    @DisplayName("a cancelled shred cannot advance time again, so cleanup is bounded")
    void cancelledShredCannotAdvanceAgain() {
        AtomicBoolean refused = new AtomicBoolean();
        try (Kron kron = Kron.virtual()) {
            Shred victim = kron.spork("victim", () -> {
                try {
                    advance(s(10));
                } finally {
                    try {
                        advance(ms(1));            // a cleanup that tries to linger
                        refused.set(false);
                    } catch (Failures.ShredCancelled expected) {
                        refused.set(true);
                    }
                }
            });
            kron.spork("killer", () -> {
                advance(ms(50));
                victim.cancel();
            });
            kron.run();
        }
        assertTrue(refused.get(), "advance() inside a cancelled shred must refuse");
    }

    @Test
    @DisplayName("cancelling a parent cancels its children, and children unwind first")
    void cancellationReachesChildren() {
        try (Kron kron = Kron.virtual()) {
            Shred parent = kron.spork("parent", () -> {
                spork("child", () -> {
                    try {
                        advance(s(100));
                    } finally {
                        log.add("child-cleanup@" + now());
                    }
                });
                try {
                    advance(s(100));
                } finally {
                    log.add("parent-cleanup@" + now());
                }
            });
            kron.spork("killer", () -> {
                advance(ms(10));
                parent.cancel();
            });
            kron.run();
        }
        // Depth-first, like nested try-with-resources: a parent's cleanup can rely on its children
        // having already finished theirs.
        assertEquals(List.of("child-cleanup@@10ms", "parent-cleanup@@10ms"), log);
    }

    @Test
    @DisplayName("a detached child survives its parent's cancellation")
    void detachedChildSurvives() {
        try (Kron kron = Kron.virtual()) {
            Shred parent = kron.spork("parent", () -> {
                spork(Detach.YES, "independent", () -> {
                    advance(s(1));
                    log.add("independent-finished@" + now());
                });
                advance(s(100));
            });
            kron.spork("killer", () -> {
                advance(ms(10));
                parent.cancel();
            });
            kron.run();
        }
        assertEquals(List.of("independent-finished@@1s"), log);
    }

    @Test
    @DisplayName("cancelling a shred that is waiting on a trigger unwinds it too")
    void cancellingATriggerWaiterUnwindsIt() {
        try (Kron kron = Kron.virtual()) {
            Trigger never = kron.trigger("never");
            Shred waiter = kron.spork("waiter", () -> {
                try {
                    await(never);
                    log.add("unreachable");
                } finally {
                    log.add("waiter-cleanup@" + now());
                }
            });
            kron.spork("killer", () -> {
                advance(ms(30));
                waiter.cancel();
            });
            kron.run();
        }
        assertEquals(List.of("waiter-cleanup@@30ms"), log);
    }

    @Test
    @DisplayName("close() cancels whatever is still alive and lets it unwind")
    void closeUnwindsSurvivors() {
        try (Kron kron = Kron.virtual()) {
            kron.spork("forever", () -> {
                try {
                    while (true) {
                        advance(s(1));
                    }
                } finally {
                    log.add("forever-cleanup");
                }
            });
            kron.runUntil(Moment.ORIGIN.plus(s(3)));
            assertEquals(List.of(), log, "still running at the limit");
        }
        assertEquals(List.of("forever-cleanup"), log);
    }

    @Test
    @DisplayName("shreds still alive with nothing scheduled is a named error, not a silent exit")
    void deadlockIsReported() {
        try (Kron kron = Kron.virtual()) {
            Trigger never = kron.trigger("never");
            kron.spork("stuck", () -> await(never));
            Failures.TimelineStalled stalled = org.junit.jupiter.api.Assertions.assertThrows(
                    Failures.TimelineStalled.class, kron::run);
            assertEquals(1, stalled.waiting().size());
            assertTrue(stalled.waiting().get(0).contains("stuck"));
            assertFalse(stalled.getMessage().isBlank());
        }
    }
}
