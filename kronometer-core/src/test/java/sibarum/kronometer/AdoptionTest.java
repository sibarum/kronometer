package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The first hour: wiring a clock into an application that already has a frame loop.
 *
 * <p>Every test here is a mistake somebody makes on the way in, or the shortest correct thing they
 * could have written instead.
 */
class AdoptionTest {

    @Test
    @DisplayName("tick() keeps its own origin, so a host never has to invent one")
    void tickKeepsItsOwnOrigin() {
        ManualWall wall = new ManualWall();
        try (Kron kron = Kron.of(Clock.driven(Driven.Mode.INLINE, wall))) {
            Rate frames = kron.dynamic("frames");
            List<Moment> at = new ArrayList<>();
            kron.effect(frames, () -> at.add(kron.now()));

            // The wall is somewhere arbitrary when the app starts, exactly as System.nanoTime() is.
            wall.advance(ms(4_000));
            kron.tick();                       // first call takes the origin: this is Moment.ORIGIN
            wall.advance(ms(16));
            kron.tick();
            wall.advance(ms(16));
            kron.tick();

            assertEquals(List.of(new Moment(0), new Moment(ms(16).nanos()), new Moment(ms(32).nanos())),
                    at);
            assertEquals(3, kron.ticks());
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("the raw-nanoTime mistake costs one bounded frame instead of freezing the app")
    void aRawWallReadingIsForgivenRatherThanReplayed() {
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20));
            AtomicLong steps = new AtomicLong();
            kron.effect(physics, steps::incrementAndGet);

            List<Overrun> reported = new ArrayList<>();
            kron.onOverrun(reported::add);

            // The obvious wrong guess. Unbounded this ran ~17 million steps — about twenty seconds of
            // frozen render thread, on the first frame, silently.
            kron.tick(System.nanoTime());

            assertTrue(steps.get() <= 51, "one second of grid lines at most, not the whole epoch: "
                    + steps.get());
            assertEquals(1, reported.size());
            assertEquals(Overrun.Kind.SKIPPED, reported.get(0).kind());
            assertEquals(ms(1_000), kron.now().since(Moment.ORIGIN));
        }
    }

    @Test
    @DisplayName("a long stall is forgiven once, and every later tick keeps the same offset")
    void forgivenessAccumulatesRatherThanResetting() {
        try (Kron kron = Kron.driven()) {
            kron.dynamic("frames");
            kron.tick(ms(16).nanos());

            // Twenty seconds at a breakpoint. Logical time takes one second of it and writes off the
            // rest; the tick stream carries on from where the wall actually is.
            kron.tick(ms(20_016).nanos());
            assertEquals(ms(1_016), kron.now().since(Moment.ORIGIN));
            assertEquals(ms(19_000), kron.slip());

            kron.tick(ms(20_032).nanos());
            assertEquals(ms(1_032), kron.now().since(Moment.ORIGIN),
                    "a normal frame after the stall advances by a normal frame");
        }
    }

    @Test
    @DisplayName("maxAdvance is opt-out, for a scripted run where a long jump is the point")
    void aScriptedRunCanOptOut() {
        try (Kron kron = Kron.of(Clock.driven(Driven.Mode.INLINE).maxAdvance(Dur.FOREVER))) {
            kron.dynamic("frames");
            kron.tick(ms(60_000).nanos());
            assertEquals(ms(60_000), kron.now().since(Moment.ORIGIN));
            assertEquals(Dur.ZERO, kron.slip());
        }
    }

    @Test
    @DisplayName("a backwards tick is a host bug and says so, rather than making a zero-dt frame")
    void backwardsTicksAreRejected() {
        try (Kron kron = Kron.driven()) {
            kron.dynamic("frames");
            kron.tick(ms(32).nanos());

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> kron.tick(ms(20).nanos()));
            assertTrue(thrown.getMessage().contains("must not go backwards"), thrown.getMessage());

            // Unchanged is still fine: nanoTime resolution is not the host's fault.
            kron.tick(ms(32).nanos());
        }
    }

    @Test
    @DisplayName("bound is the two lines everybody writes first")
    void boundIsTheShortestStart() {
        List<Double> landed = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Rate frames = kron.dynamic("frames");
            Cell<Double> lift = kron.bound(frames, "lift", 0.0, landed::add);

            kron.tick(ms(0).nanos());
            kron.post(new Moment(ms(16).nanos()),
                    () -> lift.drive(Curve.ramp(0.0, 1.0, ms(32), Interp.DOUBLE)));
            kron.tick(ms(16).nanos());
            kron.tick(ms(32).nanos());
            kron.tick(ms(48).nanos());

            assertEquals(List.of(0.0, 0.0, 0.5, 1.0), landed);
        }
    }

    @Test
    @DisplayName("toString answers the first question: is this clock being driven at all")
    void toStringSaysWhetherItIsRunning() {
        try (Kron kron = Kron.driven()) {
            Rate frames = kron.dynamic("frames");
            kron.effect(frames, () -> { });

            assertTrue(kron.toString().contains("0 ticks"), kron.toString());

            kron.tick(ms(16).nanos());
            String after = kron.toString();
            assertTrue(after.contains("1 ticks"), after);
            assertTrue(after.contains("frames[1]"), after);
            assertTrue(after.contains("quiescent"), after);
        }
    }
}
