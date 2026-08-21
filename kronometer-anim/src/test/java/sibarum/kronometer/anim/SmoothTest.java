package sibarum.kronometer.anim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Curve;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Signal;
import sibarum.kronometer.Time;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The closed-form / integrated split, which is the one place §9's classification has teeth.
 */
class SmoothTest {

    @Test
    @DisplayName("a closed-form settle is a pure curve, and lands exactly on its target")
    void settleIsClosedForm() {
        Curve<Double> curve = Smooth.settle(0.0, 10.0, ms(200));

        assertEquals(0.0, curve.at(Dur.ZERO), 1e-9, "starts where it was told");
        assertEquals(10.0, curve.at(ms(200)), 1e-9, "ends exactly on target, not a thousandth short");
        assertTrue(curve.at(ms(100)) > 8.0, "critically damped: most of the way by halfway");
        assertEquals(ms(200), curve.extent());

        // Monotone approach — a critically damped response does not overshoot.
        double previous = -1;
        for (int i = 0; i <= 100; i++) {
            double value = curve.at(ms(2).times(i));
            assertTrue(value >= previous, "overshot at " + i);
            assertTrue(value <= 10.0 + 1e-9, "exceeded the target at " + i);
            previous = value;
        }
    }

    @Test
    @DisplayName("a closed-form settle stays fully predictable — the point of being closed-form")
    void settleIsPredictable() {
        try (Kron kron = Kron.virtual()) {
            Cell<Double> value = kron.cell("value", 0.0);
            kron.spork(() -> {
                value.drive(Smooth.settle(0.0, 1.0, ms(200)));
                assertEquals(Moment.FOREVER, value.horizon(), "determined: a pure function of time");
                assertEquals(Moment.ORIGIN.plus(ms(200)), value.varyingUntil());
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("an integrated chase refuses a dynamic domain, loudly")
    void integratedSmootherRejectsDynamicDomains() {
        try (Kron kron = Kron.driven()) {
            Rate frames = kron.dynamic("frames");
            Signal<Double> target = Signal.constant(1.0);

            // The classic feel bug: `value += (target - value) * k` applied at a varying dt converges at
            // a rate that depends on the frame rate, so the same UI feels different at 60 and 144 Hz.
            // Refusing it at construction beats shipping something nobody can reproduce.
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Smooth.chase(kron, frames, target, ms(100)));
            assertTrue(e.getMessage().contains("fixed dt"), e.getMessage());
            assertTrue(e.getMessage().contains("interpolate"), "should say what to do instead");
        }
    }

    @Test
    @DisplayName("an integrated chase converges towards a live target")
    void integratedChaseConverges() {
        List<Double> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Cell<Double> target = kron.cell("target", 0.0).live();
            Rate physics = kron.fixed("physics", ms(10)).priority(0);
            Signal<Double> smoothed = Smooth.chase(kron, physics, target, ms(50));

            Rate observer = kron.fixed("observer", ms(50)).priority(1);
            kron.effect(observer, () -> observed.add(Math.round(smoothed.get() * 100) / 100.0));

            kron.spork(() -> target.set(1.0));
            kron.runUntil(Moment.ORIGIN.plus(ms(250)));
        }
        // One time constant per 50 ms, so roughly 63 % of the remaining gap closes each time.
        assertTrue(observed.get(0) > 0.5 && observed.get(0) < 0.75,
                "about one time constant in: " + observed);
        assertTrue(observed.get(observed.size() - 1) > 0.97, "converged: " + observed);
        for (int i = 1; i < observed.size(); i++) {
            assertTrue(observed.get(i) >= observed.get(i - 1), "should approach monotonically");
        }
    }

    @Test
    @DisplayName("an integrated chase is declared volatile, so nothing downstream is precomputed")
    void integratedChaseIsNotPredictable() {
        try (Kron kron = Kron.virtual()) {
            Cell<Double> target = kron.cell("target", 0.0).live();
            Rate physics = kron.fixed("physics", ms(10));
            Signal<Double> smoothed = Smooth.chase(kron, physics, target, ms(50));
            Signal<Double> downstream = kron.computed("downstream", () -> smoothed.get() * 2);

            kron.spork(() -> {
                Time.advance(ms(30));
                // It depends on its own past and on an unpredictable input, so none of its future is
                // knowable. That is the honest classification, and it propagates without being asked.
                assertEquals(kron.now(), smoothed.horizon());
                assertEquals(kron.now(), downstream.horizon());
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(60)));
        }
    }

    @Test
    @DisplayName("a fixed-domain chase steps at a constant dt, so it is framerate-independent")
    void integratedChaseIsReproducible() {
        // The same scenario on two different observation rates must produce the same trajectory, because
        // the integration happens on the fixed grid rather than on whatever is watching.
        List<Double> atFifty = trajectory(ms(50));
        List<Double> atSeventy = trajectory(ms(70));

        // Sample the shared moments: 350 ms is a multiple of both.
        assertEquals(round(atFifty.get(6)), round(atSeventy.get(4)),
                "the trajectory must not depend on who is looking at it");
    }

    private static List<Double> trajectory(Dur observePeriod) {
        List<Double> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Cell<Double> target = kron.cell("target", 0.0).live();
            Rate physics = kron.fixed("physics", ms(10)).priority(0);
            Signal<Double> smoothed = Smooth.chase(kron, physics, target, ms(100));
            Rate observer = kron.fixed("observer", observePeriod).priority(1);
            kron.effect(observer, () -> observed.add(smoothed.get()));
            kron.spork(() -> target.set(1.0));
            kron.runUntil(Moment.ORIGIN.plus(ms(400)));
        }
        return observed;
    }

    private static double round(double value) {
        return Math.round(value * 1e6) / 1e6;
    }
}
