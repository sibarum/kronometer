package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * What a host that owns its own wait needs to know: when there is next any point ticking the kernel.
 *
 * <p>Every test here drives the clock, because that is the case the question exists for — a render loop
 * blocking in {@code waitEvents(timeout)} rather than spinning at the display rate.
 */
class QuiescenceTest {

    @Test
    @DisplayName("a runtime with nothing scheduled and nothing varying is quiescent")
    void emptyIsQuiescent() {
        try (Kron kron = Kron.driven()) {
            kron.tick(ms(16).nanos());
            assertTrue(kron.isQuiescent());
            assertEquals(Moment.FOREVER, kron.nextDeadline());
            assertEquals(List.of(), kron.whyBusy());
        }
    }

    @Test
    @DisplayName("a sleeping shred is a deadline, and its moment is the answer")
    void sleepingShredIsADeadline() {
        try (Kron kron = Kron.driven()) {
            kron.spork("late", () -> Time.advance(ms(100)));
            kron.tick(ms(16).nanos());

            assertFalse(kron.isQuiescent());
            assertEquals(new Moment(ms(100).nanos()), kron.nextDeadline());

            kron.tick(ms(100).nanos());
            assertTrue(kron.isQuiescent());
        }
    }

    @Test
    @DisplayName("a value still varying makes the answer now, and stops doing so when the curve ends")
    void varyingValueWantsTheNextFrame() {
        try (Kron kron = Kron.driven()) {
            Cell<Double> lift = kron.cell("lift", 0.0);
            Rate frames = kron.dynamic("frames");
            List<Double> shown = new ArrayList<>();

            kron.effect(frames, () -> shown.add(lift.get()));
            kron.tick(ms(0).nanos());

            // Nothing is animating yet: the frame loop may sleep.
            assertTrue(kron.isQuiescent());

            kron.post(new Moment(ms(16).nanos()),
                    () -> lift.drive(Curve.ramp(0.0, 1.0, ms(100), Interp.DOUBLE)));
            kron.tick(ms(16).nanos());

            // Mid-flight there is no discrete moment to name: the next frame that can be drawn is due.
            assertFalse(kron.isQuiescent());
            assertEquals(kron.now(), kron.nextDeadline());
            assertTrue(kron.whyBusy().stream().anyMatch(r -> r.contains("varying until")));

            kron.tick(ms(80).nanos());
            assertFalse(kron.isQuiescent());

            // The tick that lands on the end of the curve carries the final value, and the loop is
            // told it may sleep only *after* that frame has been computed — never before it.
            kron.tick(ms(116).nanos());
            assertEquals(1.0, shown.get(shown.size() - 1), 1e-9);
            assertTrue(kron.isQuiescent());
        }
    }

    @Test
    @DisplayName("a fixed domain with handlers is a deadline; cancelling the last one parks it")
    void aDomainWithoutHandlersHasNoDeadline() {
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20));
            AtomicInteger steps = new AtomicInteger();
            Effect effect = kron.effect(physics, steps::incrementAndGet);

            kron.tick(ms(50).nanos());
            assertEquals(2, steps.get());
            assertFalse(kron.isQuiescent());
            assertEquals(new Moment(ms(60).nanos()), kron.nextDeadline());

            effect.cancel();

            // One more wake, running nothing: the driver parks at the top of the following step rather
            // than being reached into mid-segment. Conservative in the safe direction.
            kron.tick(ms(70).nanos());
            assertEquals(2, steps.get());

            assertFalse(physics.hasHandlers());
            assertTrue(physics.isIdle());
            assertTrue(kron.isQuiescent(), "a domain being stepped for nothing is not a reason to wake");
        }
    }

    @Test
    @DisplayName("re-registering resumes a parked domain on its original grid phase, owing nothing")
    void resumingKeepsThePhaseAndSkipsTheDebt() {
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20));
            List<Moment> at = new ArrayList<>();
            Effect first = kron.effect(physics, () -> at.add(kron.now()));

            kron.tick(ms(50).nanos());
            first.cancel();
            kron.tick(ms(70).nanos());          // the one wasted wake, then parked
            at.clear();

            kron.tick(ms(500).nanos());         // a long doze, well past maxCatchUp
            assertEquals(List.of(), at);

            kron.effect(physics, () -> at.add(kron.now()));
            kron.tick(ms(560).nanos());

            // No burst of replayed steps for the time it slept, and the lines it does run are the ones
            // the original grid would have had — 20 ms apart, on multiples of 20.
            assertEquals(List.of(new Moment(ms(520).nanos()),
                            new Moment(ms(540).nanos()),
                            new Moment(ms(560).nanos())),
                    at);
        }
    }

    @Test
    @DisplayName("work posted between ticks is a deadline, though no loop has seen it yet")
    void workPostedBetweenTicksIsADeadline() {
        try (Kron kron = Kron.driven()) {
            kron.tick(ms(16).nanos());
            assertTrue(kron.isQuiescent());

            // The host is about to go to sleep on this answer, so the answer has to account for work
            // that has been placed on the timeline but not yet looked at. Posted from the thread that
            // ticks, it is placed at once and the deadline is exact; from a worker it would wait in the
            // inbox and the answer would be `now`, costing one tick to learn the real one.
            kron.post(new Moment(ms(20).nanos()), () -> { });

            assertFalse(kron.isQuiescent());
            assertEquals(new Moment(ms(20).nanos()), kron.nextDeadline());

            kron.tick(ms(20).nanos());
            assertTrue(kron.isQuiescent());
        }
    }

    @Test
    @DisplayName("onTimeline always runs the work inside a shred, whichever side it was called from")
    void onTimelineAlwaysLandsOnTheTimeline() {
        try (Kron kron = Kron.driven()) {
            List<String> where = new ArrayList<>();

            // From setup, before the kernel is running. Running it inline here would satisfy the
            // on-timeline check and still fail on the first time intrinsic, which is the whole reason
            // anyone crosses the baton.
            kron.onTimeline(() -> {
                where.add("setup:" + kron.isOnTimeline());
                Time.spork("child", () -> where.add("child ran"));
            });

            kron.spork("worker", () -> {
                kron.onTimeline(() -> where.add("shred:" + kron.isOnTimeline()));
                Time.advance(ms(10));
            });
            kron.tick(ms(20).nanos());

            assertEquals(List.of("setup:true", "shred:true", "child ran"), where);
        }
    }
}
