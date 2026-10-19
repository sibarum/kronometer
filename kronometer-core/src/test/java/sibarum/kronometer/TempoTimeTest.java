package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * {@link Tempo#time()}: a tempo's local clock as a signal, for consumers that build phase on it.
 */
class TempoTimeTest {

    private static Moment at(Dur d) {
        return Moment.ORIGIN.plus(d);
    }

    @Test
    @DisplayName("time() reads the tempo's map, through a glide")
    void readsTheMap() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            kron.spork(() -> tempo.glide(Ratio.of(2, 1), ms(100), Glide.EXPONENTIAL));
            kron.run();
            for (long t = 0; t <= 300; t += 7) {
                assertEquals(tempo.elapsedAt(at(ms(t))), tempo.time().at(at(ms(t))));
            }
            assertTrue(tempo.time() == tempo.time(), "one signal, so reading it is one dependency");
        }
    }

    @Test
    @DisplayName("read at both ends of a block inside a computed, it advances, predicted or not")
    void twoReadsInOneEvaluationDiffer() {
        List<Dur> lazy = blockAdvances(Predict.NEVER);
        List<Dur> eager = blockAdvances(Predict.EAGER);
        assertEquals(lazy, eager);
        assertTrue(lazy.size() > 5);
        for (Dur advance : lazy) {
            assertEquals(ms(2), advance, "a 1 ms block in a 2:1 tempo advances 2 ms of local time");
        }
    }

    /**
     * Faizuray's {@code Voice} pattern: one evaluation reads the clock at the start and the end of a
     * block. A memoized clock would make every block advance by zero.
     */
    private static List<Dur> blockAdvances(Predict policy) {
        List<Dur> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.of(2, 1));
            Signal<Moment> now = kron.time();
            Signal<Dur> local = tempo.time();
            Signal<Dur> block = kron.computed("block", () -> {
                Moment start = now.get();
                return local.at(start.plus(ms(1))).minus(local.at(start));
            });
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(100));
            Prediction<Dur> prediction = frames.predict(block, policy);
            kron.effect(frames, () -> observed.add(block.get()));
            kron.runUntil(at(ms(100)));
            if (policy == Predict.EAGER) {
                assertTrue(prediction.hits() > 5, "the block should have been computed ahead");
            }
        }
        return observed;
    }

    @Test
    @DisplayName("a computed over time() inherits the tempo's horizon")
    void computedInheritsTheHorizon() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Signal<Double> phase = kron.computed("phase", () -> 440.0 * tempo.time().get().toSeconds());
            kron.spork(() -> {
                phase.get();
                assertEquals(Moment.FOREVER, phase.horizon(), "holding");
                tempo.glide(Ratio.of(3, 2), ms(50));
                phase.get();
                assertEquals(Moment.FOREVER, phase.horizon(), "gliding");
                tempo.live();
                phase.get();
                assertEquals(kron.now(), phase.horizon(), "live");
            });
            kron.run();
        }
    }
}
