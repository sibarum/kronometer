package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.s;

/**
 * The local→global map and its inverse agree to the nanosecond.
 *
 * <p>{@link Tempo#globalAt} is defined as the least moment whose local time reaches the target, which
 * makes the pair a Galois connection: {@code elapsedAt(globalAt(L)) >= L} and
 * {@code globalAt(elapsedAt(g)) <= g}, always. A {@link Rate} depends on exactly that to place a grid line
 * and find it again. These are property tests over generated chains rather than examples, because the
 * cases that break a rounded inverse are the ones nobody would think to write down.
 */
class TempoMapTest {

    @Test
    @DisplayName("the closed-form inverse is already the least moment, and the fix-up never moves it")
    void closedFormIsExact() {
        Random random = new Random(1);
        for (int i = 0; i < 200_000; i++) {
            Ratio scale = Ratio.of(1 + random.nextInt(2000), 1 + random.nextInt(2000));
            long target = switch (i % 3) {
                case 0 -> random.nextLong(-1_000, 1_000);
                case 1 -> random.nextLong(-10_000_000_000L, 10_000_000_000L);
                default -> random.nextLong(-1_000_000_000_000_000L, 1_000_000_000_000_000L);  // overflow path
            };
            long d;
            try {
                d = Tempo.leastReaching(scale, target);
            } catch (ArithmeticException beyondRange) {
                continue;                            // the forward map itself overflows: no moment to find
            }
            assertEquals(d, Tempo.closedFormReaching(scale, target),
                    () -> "fix-up moved the candidate for " + scale + " at " + target);
            assertTrue(Tempo.floorScaled(scale, d) >= target, () -> scale + " at " + target + " does not reach it");
            assertTrue(Tempo.floorScaled(scale, d - 1) < target, () -> scale + " at " + target + " is not the least");
        }
    }

    @Test
    @DisplayName("the map and its inverse form a Galois pair, over random nested chains and rescales")
    void galoisPairOverRandomChains() {
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
                        chain.get(random.nextInt(chain.size())).rescale(smallRatio(random));
                        for (Tempo t : chain) {
                            checkGalois(t, kron.now(), random, chainSeed);
                        }
                    }
                });
                kron.run();
            }
        }
    }

    @Test
    @DisplayName("every grid line in an inexact tempo is found again as itself")
    void gridLinesRoundTrip() {
        try (Kron kron = Kron.virtual()) {
            Tempo slow = kron.tempo().child(Ratio.of(3, 7));
            Tempo fast = kron.tempo().child(Ratio.of(13, 5));
            Rate slowGrid = slow.fixed("slow", Dur.ns(997));
            Rate fastGrid = fast.fixed("fast", Dur.ns(1_009));
            List<Long> slowSteps = new ArrayList<>();
            List<Long> fastSteps = new ArrayList<>();
            slowGrid.each(step -> slowSteps.add(step.index()));
            fastGrid.each(step -> fastSteps.add(step.index()));

            kron.runUntil(Moment.ORIGIN.plus(ms(2)));

            for (Rate grid : List.of(slowGrid, fastGrid)) {
                for (long n = 0; n < 5_000; n++) {
                    assertEquals(n, grid.gridIndexAtOrBefore(grid.gridLineAt(n)),
                            "grid line " + n + " of " + grid + " read back as another line");
                    assertEquals(n - 1, grid.gridIndexAtOrBefore(grid.gridLineAt(n).minus(Dur.ns(1))),
                            "the nanosecond before grid line " + n + " of " + grid + " is not the line before");
                }
            }
            assertContiguous(slowSteps);
            assertContiguous(fastSteps);
        }
    }

    @Test
    @DisplayName("sibling grids still coincide exactly at their common multiple")
    void exactCasesAreUnchanged() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                Tempo threeTwo = kron.tempo().child(Ratio.of(3, 2));
                Tempo fourThree = kron.tempo().child(Ratio.of(4, 3));
                assertEquals(Moment.ORIGIN.plus(ms(72)), threeTwo.globalAt(ms(108)));
                assertEquals(Moment.ORIGIN.plus(ms(72)), fourThree.globalAt(ms(96)));
                assertEquals(Moment.ORIGIN.plus(s(1)), kron.tempo().globalAt(s(1)));
            });
            kron.run();
        }
    }

    // -------------------------------------------------------------- helpers

    private static Ratio smallRatio(Random random) {
        return Ratio.of(1 + random.nextInt(64), 1 + random.nextInt(64));
    }

    static void checkGalois(Tempo tempo, Moment now, Random random, long seed) {
        for (int i = 0; i < 200; i++) {
            Moment g = now.plus(Dur.ns(random.nextLong(-1_000_000_000L, 10_000_000_000L)));
            Dur local = tempo.elapsedAt(g);
            assertTrue(!tempo.globalAt(local).isAfter(g),
                    () -> "seed " + seed + ": globalAt(elapsedAt(" + g + ")) is after it in " + tempo);
            assertTrue(tempo.elapsedAt(g.plus(Dur.ns(1))).compareTo(local) >= 0,
                    () -> "seed " + seed + ": elapsedAt is not monotone at " + g + " in " + tempo);

            Dur target = local.plus(Dur.ns(random.nextLong(-1_000, 1_000)));
            Moment at = tempo.globalAt(target);
            assertTrue(tempo.elapsedAt(at).compareTo(target) >= 0,
                    () -> "seed " + seed + ": globalAt(" + target + ") does not reach it in " + tempo);
            assertTrue(tempo.elapsedAt(at.minus(Dur.ns(1))).compareTo(target) < 0,
                    () -> "seed " + seed + ": globalAt(" + target + ") is not the least moment in " + tempo);
        }
    }

    private static void assertContiguous(List<Long> indices) {
        assertTrue(indices.size() > 100, "the grid should have stepped, got " + indices.size());
        for (int i = 1; i < indices.size(); i++) {
            assertEquals(indices.get(i - 1) + 1, indices.get(i), "step indices skipped or repeated at " + i);
        }
    }
}
