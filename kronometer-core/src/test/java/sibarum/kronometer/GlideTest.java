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
import static sibarum.kronometer.Dur.s;
import static sibarum.kronometer.Dur.us;

/**
 * A tempo whose speed changes in a straight line: local time is the exact integral, the rate is
 * continuous, and once the glide ends the tempo converts through one exact ratio again.
 */
class GlideTest {

    private static Moment at(Dur d) {
        return Moment.ORIGIN.plus(d);
    }

    @Test
    @DisplayName("local time is the exact integral of a linear speed, and holds the end speed after it")
    void exactIntegral() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            kron.spork(() -> tempo.glide(Ratio.of(2, 1), s(1)));
            kron.run();

            // I(t) = t + t²/2 over the first second, then 2 per second.
            assertEquals(ms(625), tempo.elapsedAt(at(ms(500))));
            assertEquals(ms(1_500), tempo.elapsedAt(at(s(1))));
            assertEquals(ms(3_500), tempo.elapsedAt(at(s(2))));
            assertEquals(at(ms(500)), tempo.globalAt(ms(625)));
            assertEquals(at(s(2)), tempo.globalAt(ms(3_500)));
            assertEquals(Ratio.of(2, 1), tempo.scale());
        }
    }

    @Test
    @DisplayName("the rate is continuous where a glide ends")
    void rateIsContinuousAtTheKnot() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            kron.spork(() -> tempo.glide(Ratio.of(2, 1), s(1)));
            kron.run();

            long before = rate(tempo, at(s(1)).minus(us(1)), at(s(1)));
            long after = rate(tempo, at(s(1)), at(s(1)).plus(us(1)));
            assertEquals(2_000, after);
            assertTrue(Math.abs(after - before) <= 1, "rate jumped from " + before + " to " + after);
        }
    }

    @Test
    @DisplayName("a glide that replaces one in progress starts from the speed it had reached")
    void retargetKeepsTheSpeed() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            kron.spork(() -> {
                tempo.glide(Ratio.of(2, 1), s(1));
                Time.advance(ms(500));
                tempo.glide(Ratio.ONE, ms(500));                 // at 1.5, heading back down
            });
            kron.run();

            long before = rate(tempo, at(ms(500)).minus(us(1)), at(ms(500)));
            long after = rate(tempo, at(ms(500)), at(ms(500)).plus(us(1)));
            assertTrue(Math.abs(before - 1_500) <= 1, "speed before the retarget: " + before);
            assertTrue(Math.abs(after - 1_500) <= 1, "speed after the retarget: " + after);
            // 625 ms by the first glide, then 1.5 → 1 over 500 ms adds 625 ms, then 1:1.
            assertEquals(ms(1_250), tempo.elapsedAt(at(s(1))));
            assertEquals(ms(1_750), tempo.elapsedAt(at(ms(1_500))));
        }
    }

    @Test
    @DisplayName("after a glide, a grid in the tempo is exact again")
    void exactnessReturnsAfterTheGlide() {
        List<Moment> steps = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Rate grid = tempo.fixed("grid", ms(3));
            grid.each(step -> steps.add(step.at()));
            kron.spork(() -> tempo.glide(Ratio.of(3, 2), ms(100)));
            kron.runUntil(at(ms(400)));
        }
        List<Long> afterKnot = new ArrayList<>();
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i - 1).isAfter(at(ms(100)))) {
                afterKnot.add(steps.get(i).since(steps.get(i - 1)).nanos());
            }
        }
        assertTrue(afterKnot.size() > 100, "the grid should keep stepping: " + afterKnot.size());
        for (long spacing : afterKnot) {
            assertEquals(ms(2).nanos(), spacing, "3 ms of local time at 3:2 is exactly 2 ms of wall");
        }
    }

    @Test
    @DisplayName("a child holding 2:1 inside a gliding parent runs at exactly twice its parent's time")
    void nestedGlidesCompose() {
        try (Kron kron = Kron.virtual()) {
            Tempo parent = kron.tempo().child(Ratio.ONE);
            Tempo child = parent.child(Ratio.of(2, 1));
            kron.spork(() -> parent.glide(Ratio.of(3, 1), ms(200)));
            kron.run();

            for (long t = 0; t <= 400_000_000L; t += 1_234_567) {
                Moment g = Moment.ORIGIN.plus(Dur.ns(t));
                assertEquals(parent.elapsedAt(g).nanos() * 2, child.elapsedAt(g).nanos(), "at " + g);
            }
            assertEquals(Ratio.of(6, 1), child.effectiveScale());
        }
    }

    @Test
    @DisplayName("the map and its inverse stay a Galois pair through random glides, rescales and nesting")
    void galoisPairThroughGlides() {
        for (long seed = 0; seed < 100; seed++) {
            Random random = new Random(seed);
            long chainSeed = seed;
            try (Kron kron = Kron.virtual()) {
                kron.spork(() -> {
                    List<Tempo> chain = new ArrayList<>();
                    Tempo tempo = kron.tempo();
                    int depth = 1 + random.nextInt(3);
                    for (int level = 0; level < depth; level++) {
                        tempo = tempo.child(smallRatio(random));
                        chain.add(tempo);
                    }
                    for (int round = 0; round < 30; round++) {
                        Time.advance(Dur.ns(random.nextLong(1, 50_000_000)));
                        Tempo target = chain.get(random.nextInt(chain.size()));
                        if (random.nextInt(3) == 0) {
                            target.rescale(smallRatio(random));
                        } else {
                            target.glide(smallRatio(random), Dur.ns(random.nextLong(1_000, 100_000_000)),
                                    random.nextBoolean() ? Glide.LINEAR : Glide.EXPONENTIAL);
                        }
                        for (Tempo t : chain) {
                            TempoMapTest.checkGalois(t, kron.now(), random, chainSeed);
                        }
                    }
                });
                kron.run();
            }
        }
    }

    @Test
    @DisplayName("prediction on and off agree through glides, for a domain outside the tempo and one inside it")
    void predictionIsInvisibleThroughGlides() {
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(gliding(Predict.NEVER, seed), gliding(Predict.EAGER, seed), "seed " + seed);
        }
        assertFalse(gliding(Predict.NEVER, 0).isEmpty());
    }

    private static List<String> gliding(Predict policy, long seed) {
        List<String> observed = new ArrayList<>();
        Random random = new Random(seed);
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Cell<Double> value = kron.cell("value", tempo, 0.0);
            Signal<Double> shown = kron.computed("shown", () -> value.get());

            Rate outside = kron.fixed("outside", ms(7)).lookahead(ms(150));
            Rate inside = tempo.fixed("inside", ms(5)).lookahead(ms(150));
            outside.predict(shown, policy);
            inside.predict(shown, policy);

            kron.spork(() -> {
                value.drive(Curve.ramp(0.0, 1.0, ms(200)));
                for (int i = 0; i < 4; i++) {
                    Time.advance(ms(5 + random.nextInt(40)));
                    tempo.glide(Ratio.of(1 + random.nextInt(4), 1 + random.nextInt(4)),
                            ms(1 + random.nextInt(80)),
                            random.nextBoolean() ? Glide.LINEAR : Glide.EXPONENTIAL);
                }
            });
            kron.effect(outside, () -> observed.add("out " + Time.now() + " " + shown.get()));
            kron.effect(inside, () -> observed.add("in " + Time.now() + " " + shown.get()));

            kron.runUntil(at(ms(400)));
        }
        return observed;
    }

    @Test
    @DisplayName("a glide's square-root inverse is within one nanosecond of the least moment, before fix-up")
    void closedFormInverseIsTight() {
        Random random = new Random(7);
        int moved = 0;
        for (int i = 0; i < 20_000; i++) {
            Segment.Linear glide = Segment.Linear.between(
                    smallRatio(random), smallRatio(random), random.nextLong(1, 10_000_000_000L));
            long target = random.nextLong(-1_000_000, 20_000_000_000L);
            long least = glide.leastReaching(target);
            long candidate = glide.closedForm(target);
            assertTrue(glide.advance(least) >= target, "does not reach " + target + " in " + glide);
            assertTrue(glide.advance(least - 1) < target, "not the least for " + target + " in " + glide);
            assertTrue(Math.abs(candidate - least) <= 1, "candidate " + candidate + " vs " + least);
            if (candidate != least) {
                moved++;
            }
        }
        assertTrue(moved < 200, "the fix-up moved " + moved + " of 20 000 candidates");
    }

    // ------------------------------------------------------- exponential

    @Test
    @DisplayName("an exponential glide is at the geometric mean halfway, and lands on its closed form")
    void exponentialIsEvenInLogSpeed() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            kron.spork(() -> tempo.glide(Ratio.of(4, 1), s(1), Glide.EXPONENTIAL));
            kron.run();

            long halfway = rate(tempo, at(ms(500)), at(ms(500)).plus(us(1)));
            assertTrue(Math.abs(halfway - 2_000) <= 1, "speed halfway from 1 to 4 should be 2, was " + halfway);

            // I(D) = D·(ρ − 1) / ln ρ, with ρ = 4.
            long expected = (long) Math.floor(1e9 * 3 / Math.log(4));
            long atEnd = tempo.elapsedAt(at(s(1))).nanos();
            assertTrue(Math.abs(atEnd - expected) <= 1, "end of glide: " + atEnd + " vs " + expected);
            assertEquals(atEnd + 4_000_000_000L, tempo.elapsedAt(at(s(2))).nanos(), "exactly 4:1 after it");
        }
    }

    @Test
    @DisplayName("the rate is continuous where an exponential glide ends, and where shapes replace each other")
    void exponentialRateIsContinuous() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            kron.spork(() -> {
                tempo.glide(Ratio.of(4, 1), s(1), Glide.EXPONENTIAL);
                Time.advance(ms(500));
                tempo.glide(Ratio.ONE, ms(500), Glide.LINEAR);        // from 2, down in a straight line
                Time.advance(ms(250));
                tempo.glide(Ratio.of(3, 1), ms(500), Glide.EXPONENTIAL);   // from 1.5, up again
            });
            kron.run();

            for (Moment change : List.of(at(ms(500)), at(ms(750)), at(ms(1_250)))) {
                long before = rate(tempo, change.minus(us(1)), change);
                long after = rate(tempo, change, change.plus(us(1)));
                assertTrue(Math.abs(after - before) <= 2,
                        "rate jumped from " + before + " to " + after + " at " + change);
            }
            assertEquals(3_000, rate(tempo, at(s(2)), at(s(2)).plus(us(1))));
        }
    }

    @Test
    @DisplayName("after an exponential glide, a grid in the tempo is exact again")
    void exactnessReturnsAfterAnExponentialGlide() {
        List<Moment> steps = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Rate grid = tempo.fixed("grid", ms(3));
            grid.each(step -> steps.add(step.at()));
            kron.spork(() -> tempo.glide(Ratio.of(3, 2), ms(100), Glide.EXPONENTIAL));
            kron.runUntil(at(ms(400)));
        }
        int checked = 0;
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i - 1).isAfter(at(ms(100)))) {
                assertEquals(ms(2).nanos(), steps.get(i).since(steps.get(i - 1)).nanos());
                checked++;
            }
        }
        assertTrue(checked > 100, "the grid should keep stepping: " + checked);
    }

    @Test
    @DisplayName("an exponential glide's log1p inverse is within a nanosecond of the least moment, before fix-up")
    void exponentialInverseIsTight() {
        Random random = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            Segment.Exponential glide = Segment.Exponential.between(
                    smallRatio(random), smallRatio(random), random.nextLong(1, 10_000_000_000L));
            long target = random.nextLong(-1_000_000, 20_000_000_000L);
            long least = glide.leastReaching(target);
            assertTrue(glide.advance(least) >= target, "does not reach " + target + " in " + glide);
            assertTrue(glide.advance(least - 1) < target, "not the least for " + target + " in " + glide);
            assertTrue(Math.abs(glide.closedForm(target) - least) <= 1,
                    "candidate " + glide.closedForm(target) + " vs " + least + " in " + glide);
        }
    }

    @Test
    @DisplayName("an exponential glide never steps local time backwards")
    void exponentialIsMonotone() {
        Random random = new Random(13);
        for (int i = 0; i < 200; i++) {
            Segment.Exponential glide = Segment.Exponential.between(
                    smallRatio(random), smallRatio(random), random.nextLong(1_000, 10_000_000_000L));
            long x = random.nextLong(-1_000, glide.extent() + 1_000);
            long previous = glide.advance(x - 1);
            for (long p = x; p < x + 5_000; p++) {
                long next = glide.advance(p);
                assertTrue(next >= previous, glide + " stepped back at " + p);
                previous = next;
            }
        }
    }

    // -------------------------------------------------------------- helpers

    /** Local nanoseconds that pass between two global moments. */
    private static long rate(Tempo tempo, Moment from, Moment to) {
        return tempo.elapsedAt(to).minus(tempo.elapsedAt(from)).nanos();
    }

    private static Ratio smallRatio(Random random) {
        return Ratio.of(1 + random.nextInt(64), 1 + random.nextInt(64));
    }
}
