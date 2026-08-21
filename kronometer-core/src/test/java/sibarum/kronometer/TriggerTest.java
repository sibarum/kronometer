package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
