package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sibarum.kronometer.Dur.ms;

/**
 * Reading one domain's output from another, smoothly.
 *
 * <p>The graphics frame that lands between two physics steps is the case everybody hand-rolls, and
 * gets subtly wrong in one of two ways: showing the older step (stutter) or the newer one (time
 * travel). Interpolation is the answer, and it costs exactly one step of latency — which these tests
 * assert rather than gloss over.
 */
class CrossDomainTest {

    @Test
    @DisplayName("a frame between two physics steps sees the interpolated value")
    void framesInterpolateBetweenPhysicsSteps() {
        List<Double> rendered = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20)).priority(0);
            Rate frames = kron.dynamic("frames").priority(1);

            AtomicReference<Double> x = new AtomicReference<>(0.0);
            Sampled<Double> shown = physics.sample(x::get, Interp.DOUBLE);

            physics.each(step -> x.set((double) step.index()));
            frames.each(step -> rendered.add(shown.isReady() ? shown.at(step.at()) : null));

            for (int t = 10; t <= 80; t += 10) {
                kron.tick(ms(t).nanos());
            }
        }

        // t=10: physics has not stepped yet — nothing to show.
        // t=20: physics reaches 1, and the frame lands exactly on it: alpha 0.
        // t=30: halfway to the next step, but the next step does not exist yet, so it holds.
        // t=50: halfway between steps 1 and 2 → 1.5.
        assertEquals(java.util.Arrays.asList(null, 1.0, 1.0, 1.0, 1.5, 2.0, 2.5, 3.0), rendered);
    }

    @Test
    @DisplayName("interpolation lags by exactly one step, which is the price of smoothness")
    void interpolationLagsOneStep() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20)).priority(0);
            Rate frames = kron.dynamic("frames").priority(1);

            AtomicReference<Double> x = new AtomicReference<>(0.0);
            Sampled<Double> shown = physics.sample(x::get, Interp.DOUBLE);

            physics.each(step -> x.set((double) step.index()));
            frames.each(step -> {
                if (shown.isReady()) {
                    observed.add("shown=" + shown.at(step.at()) + " latest=" + shown.latest());
                }
            });

            for (int t = 20; t <= 60; t += 20) {
                kron.tick(ms(t).nanos());
            }
        }
        // At each frame the simulation is one step ahead of what is drawn. `latest()` is the escape
        // hatch for a caller who would rather have the latency than the smoothness.
        assertEquals(List.of(
                "shown=1.0 latest=1.0",
                "shown=1.0 latest=2.0",
                "shown=2.0 latest=3.0"), observed);
    }

    @Test
    @DisplayName("interpolation never extrapolates past the newest step")
    void alphaIsClamped() {
        List<Double> rendered = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20)).priority(0);
            Rate frames = kron.dynamic("frames").priority(1);

            AtomicReference<Double> x = new AtomicReference<>(0.0);
            Sampled<Double> shown = physics.sample(x::get, Interp.DOUBLE);

            Shred simulation = physics.each(step -> x.set((double) step.index()));
            frames.each(step -> rendered.add(shown.isReady() ? shown.at(step.at()) : null));

            kron.tick(ms(20).nanos());
            // Stop the simulation, then keep rendering nine periods past its last step. Blending
            // onward would mean inventing steps that never happened.
            simulation.cancel();
            kron.tick(ms(200).nanos());
        }
        assertEquals(java.util.Arrays.asList(1.0, 1.0), rendered);
    }

    @Test
    @DisplayName("reading before the producing domain has stepped is an error, not a guess")
    void readingBeforeAnyStepIsRefused() {
        try (Kron kron = Kron.driven()) {
            Rate physics = kron.fixed("physics", ms(20));
            Sampled<Double> shown = physics.sample(() -> 1.0, Interp.DOUBLE);
            physics.each(step -> { });
            assertThrows(IllegalStateException.class, () -> shown.at(Moment.ORIGIN));
        }
    }

    @Test
    @DisplayName("Interp.step() holds until the very end, for values that cannot be blended")
    void stepInterpolationHolds() {
        Interp<String> held = Interp.step();
        assertEquals("a", held.between("a", "b", 0f));
        assertEquals("a", held.between("a", "b", 0.99f));
        assertEquals("b", held.between("a", "b", 1f));
    }
}
