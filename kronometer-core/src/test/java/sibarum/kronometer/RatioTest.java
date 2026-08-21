package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.ns;

/**
 * Exact rational arithmetic, because nested musical ratios are the point and doubles would drift.
 */
class RatioTest {

    @Test
    @DisplayName("ratios are always in lowest terms, so equal ratios are equal objects")
    void reducedOnConstruction() {
        assertEquals(Ratio.of(3, 2), Ratio.of(6, 4));
        assertEquals(Ratio.of(3, 2), Ratio.of(300, 200));
        assertEquals(Ratio.ONE, Ratio.of(7, 7));
        assertEquals("3:2", Ratio.of(6, 4).toString());
    }

    @Test
    @DisplayName("a ratio must be positive — logical time does not run backwards")
    void nonPositiveIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Ratio.of(0, 1));
        assertThrows(IllegalArgumentException.class, () -> Ratio.of(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> Ratio.of(1, 0));
    }

    @Test
    @DisplayName("a deeply nested chain of musical ratios composes exactly")
    void nestedRatiosAreExact() {
        // 3:2 inside 4:3 inside 7:4 inside 5:4 — the kind of chain a rhythmic hierarchy makes.
        Ratio composed = Ratio.of(3, 2)
                .times(Ratio.of(4, 3))
                .times(Ratio.of(7, 4))
                .times(Ratio.of(5, 4));
        assertEquals(Ratio.of(35, 8), composed);

        // The same factors in a different order, which floating point would not guarantee.
        Ratio reordered = Ratio.of(5, 4)
                .times(Ratio.of(7, 4))
                .times(Ratio.of(3, 2))
                .times(Ratio.of(4, 3));
        assertEquals(composed, reordered);
    }

    @Test
    @DisplayName("composing a ratio with its reciprocal returns exactly one")
    void reciprocalIsExact() {
        Ratio r = Ratio.of(48_000, 44_100);
        assertEquals(Ratio.ONE, r.times(r.reciprocal()));
        assertEquals(Ratio.ONE, r.dividedBy(r));
    }

    @Test
    @DisplayName("scaling a duration is exact when the denominator divides it")
    void scalingIsExactWhenItCanBe() {
        assertEquals(ms(30), Ratio.of(3, 2).scale(ms(20)));
        assertEquals(ms(5), Ratio.of(1, 4).scale(ms(20)));
        assertEquals(ns(1), Ratio.of(1, 3).scale(ns(3)));
    }

    @Test
    @DisplayName("scaling rounds to nearest when it cannot be exact, and never accumulates")
    void inexactScalingRoundsToNearest() {
        // 10/3 ns rounds to 3, not truncates to 3 by accident — check the .5 boundary explicitly.
        assertEquals(ns(3), Ratio.of(1, 3).scale(ns(10)));
        assertEquals(ns(2), Ratio.of(1, 2).scale(ns(3)));      // 1.5 rounds up
        assertEquals(ns(1), Ratio.of(1, 4).scale(ns(3)));      // 0.75 rounds up
        assertEquals(ns(0), Ratio.of(1, 4).scale(ns(1)));      // 0.25 rounds down

        // A thousand independent conversions each round at most half a nanosecond, and because the
        // caller converts from an origin the error does not compound: the thousandth conversion is
        // just as accurate as the first.
        Ratio third = Ratio.of(1, 3);
        long exact = third.scale(ms(1).nanos() * 1000);
        long ideal = Math.round(ms(1).nanos() * 1000 / 3.0);
        assertEquals(ideal, exact);
    }

    @Test
    @DisplayName("ratios compare by value, not by representation")
    void comparison() {
        assertEquals(0, Ratio.of(1, 2).compareTo(Ratio.of(2, 4)));
        assertEquals(-1, Ratio.of(1, 3).compareTo(Ratio.of(1, 2)));
        assertEquals(1, Ratio.of(3, 2).compareTo(Ratio.ONE));
    }
}
