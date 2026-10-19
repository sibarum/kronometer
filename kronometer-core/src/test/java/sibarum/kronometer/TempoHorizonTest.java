package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.us;

/**
 * A tempo's horizon is honest: a glide is predictable all the way through, a live tempo is predictable
 * not at all, and a followed one is exactly as predictable as what it follows.
 */
class TempoHorizonTest {

    private static Moment at(Dur d) {
        return Moment.ORIGIN.plus(d);
    }

    @Test
    @DisplayName("horizon and varyingUntil follow the table: hold, glide, live, follow, and nesting")
    void horizonTable() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Tempo nested = tempo.child(Ratio.of(1, 2));
            Cell<Ratio> speed = kron.cell("speed", Ratio.ONE);

            kron.spork(() -> {
                assertEquals(Moment.FOREVER, tempo.horizon(), "holding");
                assertEquals(kron.now(), tempo.varyingUntil(), "holding");

                tempo.glide(Ratio.of(2, 1), ms(100));
                assertEquals(Moment.FOREVER, tempo.horizon(), "gliding is pure");
                assertEquals(at(ms(100)), tempo.varyingUntil(), "gliding until it settles");
                assertEquals(at(ms(100)), nested.varyingUntil(), "a nested tempo varies with its parent");

                Time.advance(ms(150));
                assertEquals(kron.now(), tempo.varyingUntil(), "settled");

                tempo.live();
                assertEquals(kron.now(), tempo.horizon(), "live");
                assertEquals(kron.now(), nested.horizon(), "a nested tempo is as unknowable as its parent");
                assertTrue(nested.isLive());

                tempo.rescale(Ratio.ONE);
                assertEquals(kron.now(), tempo.horizon(), "rescaling does not undo live");

                tempo.release();
                assertEquals(Moment.FOREVER, tempo.horizon(), "released");
                assertFalse(nested.isLive());

                tempo.follow(speed, ms(10));
                assertEquals(Moment.FOREVER, tempo.horizon(), "following a held signal");
                speed.live();
                assertEquals(kron.now(), tempo.horizon(), "following a live signal");
                tempo.glide(Ratio.of(3, 1), ms(10));
                assertEquals(Moment.FOREVER, tempo.horizon(), "an explicit glide stops following");
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("a driven cell in a live tempo is not predicted; in a gliding tempo it is")
    void liveTempoBlocksPredictionOfDrivenCells() {
        assertEquals(0, drivenCellHits(true), "nothing in a live tempo may be computed ahead");
        assertTrue(drivenCellHits(false) > 5, "a gliding tempo is predictable all the way through");
    }

    private static long drivenCellHits(boolean live) {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Cell<Double> value = kron.cell("value", tempo, 0.0);
            Signal<Double> shown = kron.computed("shown", () -> value.get() * 2);
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(100));
            Prediction<Double> prediction = frames.predict(shown);

            kron.spork(() -> {
                if (live) {
                    tempo.live();
                } else {
                    tempo.glide(Ratio.of(1, 2), ms(80), Glide.EXPONENTIAL);
                }
                value.drive(Curve.ramp(0.0, 1.0, ms(100)));
            });
            kron.effect(frames, shown::get);
            kron.runUntil(at(ms(100)));
            return prediction.hits();
        }
    }

    @Test
    @DisplayName("a held cell in a live tempo is still predicted, because its value does not depend on time")
    void heldCellsIgnoreTheirTempo() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Cell<Double> value = kron.cell("value", tempo, 3.0);
            Signal<Double> shown = kron.computed("shown", () -> value.get() * 2);
            Rate frames = kron.fixed("frames", ms(10)).lookahead(ms(100));
            Prediction<Double> prediction = frames.predict(shown);

            kron.spork(tempo::live);
            kron.effect(frames, shown::get);
            kron.runUntil(at(ms(100)));
            assertTrue(prediction.hits() > 5, "hits: " + prediction.hits());
        }
    }

    @Test
    @DisplayName("a domain inside a live tempo is not filled ahead, even for a signal that is knowable")
    void domainInALiveTempoIsNotFilled() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Cell<Double> value = kron.cell("value", 0.0);              // root tempo: knowable forever
            Signal<Double> shown = kron.computed("shown", () -> value.get() * 2);
            Rate frames = tempo.fixed("frames", ms(10)).lookahead(ms(100));
            Prediction<Double> prediction = frames.predict(shown);

            kron.spork(() -> {
                tempo.live();
                value.drive(Curve.ramp(0.0, 1.0, ms(100)));
            });
            kron.effect(frames, shown::get);
            kron.runUntil(at(ms(100)));
            assertEquals(0, prediction.hits(), "the grid itself is unknowable past now");
            assertTrue(prediction.misses() > 5, "but the domain still ran");
        }
    }

    @Test
    @DisplayName("a followed signal's every change becomes a glide, so speed stays continuous")
    void followGlidesToEachChange() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Cell<Ratio> speed = kron.cell("speed", Ratio.ONE);
            kron.spork(() -> {
                tempo.follow(speed, ms(10));
                Time.advance(ms(50));
                speed.set(Ratio.of(2, 1));
                Time.advance(ms(50));
                assertEquals(Ratio.of(2, 1), tempo.scale());
            });
            kron.run();

            // 50 ms at 1, a 10 ms glide from 1 to 2 (15 ms of local time), then 40 ms at 2.
            assertEquals(ms(145), tempo.elapsedAt(at(ms(100))));
            long before = rate(tempo, at(ms(50)).minus(us(1)), at(ms(50)));
            long after = rate(tempo, at(ms(50)), at(ms(50)).plus(us(1)));
            assertTrue(Math.abs(after - before) <= 1, "rate jumped from " + before + " to " + after);
        }
    }

    @Test
    @DisplayName("an explicit glide stops following: later changes to the source are ignored")
    void explicitGlideStopsFollowing() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Cell<Ratio> speed = kron.cell("speed", Ratio.ONE);
            kron.spork(() -> {
                tempo.follow(speed, ms(10));
                Time.advance(ms(10));
                tempo.glide(Ratio.of(3, 1), ms(10));
                Time.advance(ms(10));
                speed.set(Ratio.of(1, 2));
                Time.advance(ms(50));
                assertEquals(Ratio.of(3, 1), tempo.scale());
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("prediction on and off agree with a followed tempo and a live one rescaled per event")
    void predictionIsInvisibleWithVolatileTempos() {
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(volatileScenario(Predict.NEVER, seed), volatileScenario(Predict.EAGER, seed),
                    "seed " + seed);
        }
        assertFalse(volatileScenario(Predict.NEVER, 0).isEmpty());
    }

    private static List<String> volatileScenario(Predict policy, long seed) {
        List<String> observed = new ArrayList<>();
        Random random = new Random(seed);
        try (Kron kron = Kron.virtual()) {
            Tempo following = kron.tempo().child(Ratio.ONE);
            Tempo scrubbed = kron.tempo().child(Ratio.ONE);
            Cell<Ratio> speed = kron.cell("speed", Ratio.ONE);
            Cell<Double> a = kron.cell("a", following, 0.0);
            Cell<Double> b = kron.cell("b", scrubbed, 0.0);
            Signal<Double> sum = kron.computed("sum", () -> a.get() + b.get());

            Rate root = kron.fixed("root", ms(7)).lookahead(ms(100));
            Rate inFollowing = following.fixed("inFollowing", ms(5)).lookahead(ms(100));
            Rate inScrubbed = scrubbed.fixed("inScrubbed", ms(6)).lookahead(ms(100));
            root.predict(sum, policy);
            inFollowing.predict(a, policy);
            inScrubbed.predict(b, policy);

            kron.spork(() -> {
                following.follow(speed, ms(8));
                scrubbed.live();
                a.drive(Curve.ramp(0.0, 1.0, ms(200)));
                b.drive(Curve.ramp(0.0, 1.0, ms(200)));
                for (int i = 0; i < 6; i++) {
                    Time.advance(ms(5 + random.nextInt(30)));
                    if (random.nextBoolean()) {
                        speed.set(Ratio.of(1 + random.nextInt(4), 1 + random.nextInt(4)));
                    } else {
                        scrubbed.rescale(Ratio.of(1 + random.nextInt(4), 1 + random.nextInt(4)));
                    }
                }
            });
            kron.effect(root, () -> observed.add("root " + Time.now() + " " + sum.get()));
            kron.effect(inFollowing, () -> observed.add("following " + Time.now() + " " + a.get()));
            kron.effect(inScrubbed, () -> observed.add("scrubbed " + Time.now() + " " + b.get()));

            kron.runUntil(at(ms(300)));
        }
        return observed;
    }

    /** Local nanoseconds that pass between two global moments. */
    private static long rate(Tempo tempo, Moment from, Moment to) {
        return tempo.elapsedAt(to).minus(tempo.elapsedAt(from)).nanos();
    }
}
