package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.hz;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.s;
import static sibarum.kronometer.Time.advance;
import static sibarum.kronometer.Time.await;
import static sibarum.kronometer.Time.now;

class TriggerTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("a woken shred resumes at the moment of the firing, not after it")
    void wakingCostsNoLogicalTime() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            kron.spork("waiter", () -> {
                await(t);
                log.add("woke@" + now());
            });
            kron.spork("firer", () -> {
                advance(ms(70));
                t.fire();
                log.add("fired@" + now());
            });
            kron.run();
        }
        // The firer finishes its segment first (it holds the baton), then the waiter runs — at the
        // same moment. No logical time passes between the firing and the waking.
        assertEquals(List.of("fired@@70ms", "woke@@70ms"), log);
    }

    @Test
    @DisplayName("fire() wakes the longest-waiting shred only")
    void fireWakesOneInWaitOrder() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            for (int i = 0; i < 3; i++) {
                String name = "waiter-" + i;
                kron.spork(name, () -> {
                    await(t);
                    log.add(name);
                });
                kron.spork("spacer-" + i, () -> advance(ms(1)));   // stagger enrolment
            }
            kron.spork("firer", () -> {
                advance(ms(50));
                t.fire();
                advance(ms(50));
                t.fire();
            });
            kron.runUntil(Moment.ORIGIN.plus(s(1)));
        }
        assertEquals(List.of("waiter-0", "waiter-1"), log);
    }

    @Test
    @DisplayName("broadcast() wakes every waiter, in wait order")
    void broadcastWakesAllInOrder() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            for (int i = 0; i < 4; i++) {
                String name = "waiter-" + i;
                kron.spork(name, () -> {
                    await(t);
                    log.add(name + "@" + now());
                });
            }
            kron.spork("firer", () -> {
                advance(ms(20));
                t.broadcast();
            });
            kron.run();
        }
        assertEquals(
                List.of("waiter-0@@20ms", "waiter-1@@20ms", "waiter-2@@20ms", "waiter-3@@20ms"), log);
    }

    @Test
    @DisplayName("await with a timeout reports which one happened")
    void timeoutIsDistinguishableFromFiring() {
        try (Kron kron = Kron.virtual()) {
            AtomicBoolean firstFired = new AtomicBoolean(true);
            AtomicBoolean secondFired = new AtomicBoolean(true);
            Trigger t = kron.trigger("t");
            kron.spork("waiter", () -> {
                firstFired.set(await(t, ms(100)));      // fires at 30 ms
                log.add("first@" + now());
                secondFired.set(await(t, ms(100)));     // never fires again
                log.add("second@" + now());
            });
            kron.spork("firer", () -> {
                advance(ms(30));
                t.fire();
            });
            kron.run();
            assertTrue(firstFired.get());
            assertFalse(secondFired.get());
        }
        assertEquals(List.of("first@@30ms", "second@@130ms"), log);
    }

    @Test
    @DisplayName("a superseded deadline does not drag logical time forward")
    void retractedDeadlineDoesNotAdvanceTime() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            kron.spork("waiter", () -> await(t, s(10)));
            kron.spork("firer", () -> {
                advance(ms(100));
                t.fire();
            });
            kron.run();
            // The 10-second deadline entry is still in the queue, retracted. If the kernel let a
            // retracted entry set `now`, this would read @10s — a moment at which nothing happens.
            assertEquals(Moment.ORIGIN.plus(ms(100)), kron.now());
        }
    }

    @Test
    @DisplayName("firing a trigger nobody is waiting on is a no-op, not an error")
    void firingIntoTheVoidIsHarmless() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            kron.spork(() -> {
                t.fire();
                t.broadcast();
                advance(ms(1));
                log.add("survived");
            });
            kron.run();
        }
        assertEquals(List.of("survived"), log);
    }

    @Test
    @DisplayName("a timed-out wait leaves the trigger's waiter list, however many times it times out")
    void timedOutWaitsDoNotAccumulate() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            AtomicLong timeouts = new AtomicLong();
            int[] most = {0};
            kron.spork("poller", () -> {
                for (int i = 0; i < 100_000; i++) {
                    if (!await(t, ms(1))) {
                        timeouts.incrementAndGet();
                    }
                    most[0] = Math.max(most[0], t.waiting());
                }
            });
            kron.run();
            // waiting() is the list's real length now, not a filtered count, so a leak shows here: the
            // old lazily pruned queue held one entry per timeout, 100 000 of them by the end.
            assertEquals(100_000, timeouts.get());
            assertEquals(0, most[0]);
            assertEquals(0, t.waiting());
        }
    }

    @Test
    @DisplayName("a wait cancelled while enrolled leaves the trigger's waiter list")
    void cancelledWaitLeavesTheList() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            Shred waiter = kron.spork("waiter", () -> await(t, s(10)));
            kron.spork("canceller", () -> {
                advance(ms(1));
                log.add("enrolled " + t.waiting());
                waiter.cancel();
                advance(ms(1));
                log.add("after cancel " + t.waiting());
            });
            kron.run();
        }
        assertEquals(List.of("enrolled 1", "after cancel 0"), log);
    }

    @Test
    @DisplayName("a shred that timed out and waits again queues behind those who waited in between")
    void reEnrolmentAfterTimeoutKeepsWaitOrder() {
        try (Kron kron = Kron.virtual()) {
            Trigger t = kron.trigger("t");
            kron.spork("again", () -> {
                await(t, ms(5));                    // times out at 5 ms
                advance(ms(5));                     // "late" enrols in between, at 7 ms
                await(t);                           // re-enrols at 10 ms
                log.add("again");
            });
            kron.spork("late", () -> {
                advance(ms(7));
                await(t);
                log.add("late");
            });
            kron.spork("firer", () -> {
                advance(ms(20));
                t.fire();
                advance(ms(1));
                t.fire();
            });
            kron.run();
        }
        // A stale entry from the timed-out wait would have put "again" first: it still matched the shred
        // once the shred was waiting on the same trigger again.
        assertEquals(List.of("late", "again"), log);
    }

    @Test
    @DisplayName("a 48 kHz fixed domain under a 3:2 tempo keeps at most one waiter on its retimed trigger")
    void fixedDomainRetimedStaysBounded() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.of(3, 2));
            Rate audio = tempo.fixed("audio", hz(48_000));
            int[] most = {0};
            audio.each(step -> most[0] = Math.max(most[0], audio.retimed().waiting()));
            kron.runUntil(Moment.ORIGIN.plus(s(1)));
            // Every step waits on retimed with the grid line as its timeout, and with no tempo change
            // every one of those waits times out. Before the fix this held 72 002 entries here.
            assertTrue(audio.steps() > 48_000, "steps " + audio.steps());
            assertTrue(most[0] <= 1, "most waiters " + most[0]);
            assertTrue(audio.retimed().waiting() <= 1, "waiters " + audio.retimed().waiting());
        }
    }
}
