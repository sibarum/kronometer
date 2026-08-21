package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * Precomputation, and the one property that matters:
 *
 * <blockquote><b>Prediction is an optimization and must be observationally invisible.</b> Any scenario,
 * run with it on and with it off, must produce identical output.</blockquote>
 *
 * <p>That property is why M4 had to be trustworthy before M5 could start: the lazy path is the reference
 * implementation, and these tests are a differential comparison against it. It is also what catches an
 * impure {@code computed} body, which is otherwise a bug that hides until the day something is evaluated
 * on a different thread.
 */
class PrecomputeTest {

    /**
     * One scenario, parameterized only by whether prediction is enabled. Deliberately tangled: a curve,
     * a derived diamond, a tempo, and a mid-flight write that retracts predictions.
     */
    private static List<String> scenario(Predict policy, long seed) {
        List<String> observed = new ArrayList<>();
        Random random = new Random(seed);
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.of(2, 3));
            Cell<Double> a = kron.cell("a", tempo, 0.0);
            Cell<Double> b = kron.cell("b", tempo, 1.0);

            Signal<Double> left = kron.computed("left", () -> a.get() * 2);
            Signal<Double> right = kron.computed("right", () -> a.get() + b.get());
            Signal<Double> join = kron.computed("join", () -> left.get() + right.get());

            Rate frames = tempo.fixed("frames", ms(10)).lookahead(ms(80));
            frames.predict(join, policy);
            frames.predict(left, policy);

            kron.spork("driver", () -> {
                a.drive(Curve.ramp(0.0, 1.0, ms(60)));
                for (int i = 0; i < 6; i++) {
                    Time.advance(ms(17));
                    if (random.nextBoolean()) {
                        b.set((double) random.nextInt(5));           // retracts the predicted future
                    }
                }
            });
            kron.effect(frames, () ->
                    observed.add(Time.now() + " join=" + join.get() + " left=" + left.get()));

            kron.runUntil(Moment.ORIGIN.plus(ms(150)));
        }
        return observed;
    }

    @Test
    @DisplayName("prediction on and off produce identical output, over many seeds")
    void predictionIsObservationallyInvisible() {
        for (long seed = 0; seed < 200; seed++) {
            List<String> lazy = scenario(Predict.NEVER, seed);
            List<String> eager = scenario(Predict.EAGER, seed);
            List<String> oneStep = scenario(Predict.LAZY, seed);

            assertEquals(lazy, eager, "EAGER diverged from the reference at seed " + seed);
            assertEquals(lazy, oneStep, "LAZY diverged from the reference at seed " + seed);
            assertFalse(lazy.isEmpty(), "the scenario should produce output");
        }
    }

    @Test
    @DisplayName("a predicted read is served from the buffer, not evaluated")
    void readsAreServedFromTheBuffer() {
        AtomicInteger evaluations = new AtomicInteger();
        Prediction<Double> prediction;
        try (Kron kron = Kron.virtual()) {
            Cell<Double> value = kron.cell("value", 0.0);
            Signal<Double> expensive = kron.computed("expensive", () -> {
                evaluations.incrementAndGet();
                return value.get() * 100;
            });
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(100));
            prediction = frames.predict(expensive);

            kron.spork(() -> value.drive(Curve.ramp(0.0, 1.0, ms(100))));
            kron.effect(frames, expensive::get);

            kron.runUntil(Moment.ORIGIN.plus(ms(100)));
        }
        // Ten frames, all served from the buffer that was filled before the first one ran.
        assertTrue(prediction.hits() >= 9,
                "expected buffered reads, got " + prediction.hits() + " hits / "
                        + prediction.misses() + " misses");
        assertTrue(evaluations.get() >= 10, "the work still happened — just earlier and in parallel");
    }

    @Test
    @DisplayName("the constant tail past varyingUntil is one sample, not a thousand")
    void constantTailIsStoredOnce() {
        Prediction<Double> prediction;
        try (Kron kron = Kron.virtual()) {
            Cell<Double> value = kron.cell("value", 0.0);
            Signal<Double> derived = kron.computed("derived", () -> value.get() + 1);
            // A 20 ms curve, but a 500 ms lookahead: 50 grid lines of window over 2 lines of change.
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(500));
            prediction = frames.predict(derived);

            kron.spork(() -> value.drive(Curve.ramp(0.0, 1.0, ms(20))));
            kron.effect(frames, derived::get);

            kron.runUntil(Moment.ORIGIN.plus(ms(300)));
        }
        // Without the varyingUntil split this would have filled the whole window with identical
        // samples. This is the number M4's second horizon exists to keep small.
        assertTrue(prediction.filled() < 15,
                "the constant tail should collapse, but filled " + prediction.filled() + " samples");
    }

    @Test
    @DisplayName("a volatile signal is not predicted at all")
    void liveSignalsAreNotPredicted() {
        Prediction<Double> prediction;
        try (Kron kron = Kron.virtual()) {
            Cell<Double> input = kron.cell("input", 0.0);
            Signal<Double> derived = kron.computed("derived", () -> input.get() * 2);
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(100));
            prediction = frames.predict(derived);

            kron.spork(() -> {
                input.live();
                for (int i = 0; i < 5; i++) {
                    Time.advance(ms(10));
                    input.set((double) i);
                }
            });
            kron.effect(frames, derived::get);

            kron.runUntil(Moment.ORIGIN.plus(ms(60)));
        }
        assertEquals(0, prediction.filled(),
                "nothing about a live signal's future is knowable, so nothing should be filled");
    }

    @Test
    @DisplayName("an invalidation discards the predictions after it, and only those")
    void invalidationDiscardsTheFuture() {
        Prediction<Double> prediction;
        try (Kron kron = Kron.virtual()) {
            Cell<Double> value = kron.cell("value", 0.0);
            Signal<Double> derived = kron.computed("derived", () -> value.get() + 1);
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(100));
            prediction = frames.predict(derived);

            kron.spork(() -> {
                value.drive(Curve.ramp(0.0, 1.0, ms(200)));
                Time.advance(ms(45));
                value.set(9.0);                      // retracts everything after 45 ms
            });
            kron.effect(frames, derived::get);

            kron.runUntil(Moment.ORIGIN.plus(ms(100)));
        }
        assertTrue(prediction.discarded() > 0, "the retracted future should have been discarded");
        assertTrue(prediction.waste() > 0 && prediction.waste() < 1,
                "waste should be measurable and partial, was " + prediction.waste());
    }

    @Test
    @DisplayName("a signal that keeps invalidating gets demoted, and the waste is on the record")
    void wastefulPredictionIsDemoted() {
        Prediction<Double> prediction;
        try (Kron kron = Kron.virtual()) {
            Cell<Double> churn = kron.cell("churn", 0.0);
            Signal<Double> derived = kron.computed("derived", () -> churn.get() + 1);
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(200));
            prediction = frames.predict(derived);

            kron.spork(() -> {
                for (int i = 0; i < 40; i++) {
                    // Drive a fresh curve every step, so every fill is immediately retracted.
                    churn.drive(Curve.ramp(i, i + 1.0, ms(200)));
                    Time.advance(ms(10));
                }
            });
            kron.effect(frames, derived::get);

            kron.runUntil(Moment.ORIGIN.plus(ms(400)));
        }
        assertTrue(prediction.isDemoted(),
                "sustained waste of " + prediction.waste() + " should have demoted the policy");
        assertEquals(Predict.LAZY, prediction.policy());
    }

    @Test
    @DisplayName("predicting on a dynamic domain is refused, because there is no grid to fill")
    void dynamicDomainsCannotBePredicted() {
        try (Kron kron = Kron.driven()) {
            Signal<Double> value = Signal.constant(1.0);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> kron.dynamic("frames").predict(value));
        }
    }
}
