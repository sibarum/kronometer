package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.min;

/**
 * The M1 exit criterion: ten logical minutes in under 100 ms of wall clock.
 *
 * <p>Ten minutes at 60 Hz is 36 000 baton handoffs. At the 489 ns M0 measured that is ~18 ms, so the
 * budget here is roughly 5× headroom — deliberately loose, because this runs on whatever machine CI
 * happens to be, and a throughput test that fails on a noisy neighbour teaches nobody anything. It is
 * a regression guard against an order-of-magnitude mistake, not a benchmark; the benchmark lives in
 * {@code kronometer-bench}.
 */
class KernelThroughputTest {

    private static final int TICKS_IN_TEN_MINUTES = 60 * 60 * 10;

    private static long runTenLogicalMinutes() {
        long started = System.nanoTime();
        try (Kron kron = Kron.virtual()) {
            kron.spork("frames", () -> {
                Metro metro = Metro.hz(60);
                for (int i = 0; i < TICKS_IN_TEN_MINUTES; i++) {
                    metro.tick();
                }
            });
            kron.run();
            assertTrue(kron.now().since(Moment.ORIGIN).compareTo(min(10)) >= 0,
                    "should have covered ten logical minutes");
        }
        return System.nanoTime() - started;
    }

    @Test
    @DisplayName("ten logical minutes of 60 Hz frames run in under 100 ms")
    void tenLogicalMinutesIsFast() {
        for (int warmup = 0; warmup < 3; warmup++) {
            runTenLogicalMinutes();
        }
        long fastest = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            fastest = Math.min(fastest, runTenLogicalMinutes());
        }
        final long best = fastest;
        assertTrue(best / 1_000_000.0 < 100.0, () -> String.format(
                "ten logical minutes took %.1f ms (%,d handoffs, %.0f ns each)",
                best / 1_000_000.0, TICKS_IN_TEN_MINUTES, best / (double) TICKS_IN_TEN_MINUTES));
    }

    @Test
    @DisplayName("ten thousand shreds park for a few megabytes, not ten thousand OS threads")
    void manyShredsAreCheap() {
        try (Kron kron = Kron.virtual()) {
            for (int i = 0; i < 10_000; i++) {
                kron.spork("sleeper-" + i, () -> Time.advance(min(1)));
            }
            kron.run();
            assertEquals(Moment.ORIGIN.plus(min(1)), kron.now());
        }
    }
}
