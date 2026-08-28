package sibarum.kronometer.anim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Time;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The two endpoint properties a consumer needed and {@link Tween#run} does not have.
 *
 * <p>Both are invisible to a test that checks only that the endpoints were reached — which is why they
 * were found by running an application. These tests check <em>when</em> each endpoint arrives, not
 * merely that it did.
 */
class RampTest {

    @Test
    @DisplayName("zero is delivered before any time passes, not a step into the duration")
    void zeroArrivesBeforeTheFirstStep() {
        List<String> samples = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> Tween.ramp(ms(100), ms(25), Ease.LINEAR,
                    a -> samples.add(a + "@" + Time.now()), null));
            kron.run();
        }

        // A four-step ramp used to start the consumer at 0.25, so anything treating its first sample
        // as "here is where the motion begins" began by jumping there.
        assertEquals("0.0@@0s", samples.get(0));
        assertEquals("0.25@@25ms", samples.get(1));
        assertEquals("1.0@@100ms", samples.get(samples.size() - 1));
    }

    @Test
    @DisplayName("settled comes one step after the step that carried one")
    void settledOutlivesTheFinalValue() {
        List<String> log = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> Tween.ramp(ms(100), ms(25), Ease.LINEAR,
                    a -> log.add("value " + a + "@" + Time.now()),
                    () -> log.add("settled@" + Time.now())));
            kron.run();
        }

        // The point of the whole exercise: a consumer that tears down on arrival gets its chance to
        // present the 1 first. Same batch, and the last state anyone saw would be the 0.75.
        assertEquals("value 1.0@@100ms", log.get(log.size() - 2));
        assertEquals("settled@@125ms", log.get(log.size() - 1));
    }

    @Test
    @DisplayName("the endpoints are exact even when the step does not divide the extent")
    void endpointsAreExactOnARaggedStep() {
        List<Float> samples = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> Tween.ramp(ms(100), ms(30), Ease.OUT_CUBIC, samples::add, null));
            kron.run();
        }
        assertEquals(0f, samples.get(0), 0f);
        assertEquals(1f, samples.get(samples.size() - 1), 0f);
        assertTrue(samples.size() >= 4, "should have sampled along the way: " + samples);
    }

    @Test
    @DisplayName("run on a dynamic domain says why it cannot, instead of failing on a null period")
    void aDynamicDomainHasNoPeriodToSampleOn() {
        try (Kron kron = Kron.driven()) {
            Rate frames = kron.dynamic("frames");
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> Tween.run(frames, ms(100), Ease.LINEAR, a -> { }));
            assertTrue(thrown.getMessage().contains("no period"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Tween.curve"), thrown.getMessage());
        }
    }

    @Test
    @DisplayName("rampOn samples once per frame, both endpoints exact, settled a frame later")
    void rampOnFollowsTheFrames() {
        List<String> log = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Rate frames = kron.dynamic("frames");
            Tween.rampOn(frames, ms(48), Ease.LINEAR,
                    a -> log.add("v " + a), () -> log.add("settled"));

            // The ramp begins on the timeline, so nothing has happened until the first tick pumps it.
            assertEquals(List.of(), log);

            kron.tick(ms(16).nanos());
            // And when it does begin, 0 is the first thing the consumer sees — before any step of the
            // ramp has elapsed, which is the guarantee stated against the ramp rather than the call.
            assertEquals("v 0.0", log.get(0));

            for (int frame = 2; frame <= 5; frame++) {
                kron.tick(ms(16L * frame).nanos());
            }
        }
        assertEquals(List.of("v 0.0", "v 0.33333334", "v 0.6666667", "v 1.0", "settled"), log);
    }

    @Test
    @DisplayName("a rampOn in flight keeps the runtime busy, so a parked loop cannot strand the settle")
    void rampOnKeepsTheLoopAwakeUntilItSettles() {
        try (Kron kron = Kron.driven()) {
            Rate frames = kron.dynamic("frames");
            kron.tick(ms(0).nanos());
            assertTrue(kron.isQuiescent());

            List<String> log = new ArrayList<>();
            Tween.rampOn(frames, ms(32), Ease.LINEAR, a -> log.add("v"), () -> log.add("settled"));

            kron.tick(ms(16).nanos());
            assertFalse(kron.isQuiescent(), "mid-flight");

            kron.tick(ms(32).nanos());
            assertFalse(kron.isQuiescent(), "the frame carrying 1 still owes a settle");

            kron.tick(ms(48).nanos());
            assertTrue(log.contains("settled"));
            assertTrue(kron.isQuiescent(), "and now there is genuinely nothing left to do");
        }
    }

    @Test
    @DisplayName("rampOn works on a fixed domain too, on that domain's grid")
    void rampOnFollowsAFixedGrid() {
        List<Moment> at = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20));
            Tween.rampOn(physics, ms(40), Ease.LINEAR, a -> at.add(kron.now()), null);
            kron.tick(ms(100).nanos());
        }
        // 0 lands at ORIGIN before any step; the grid then carries the samples.
        assertEquals(List.of(new Moment(0), new Moment(ms(20).nanos()), new Moment(ms(40).nanos())),
                at);
    }
}
