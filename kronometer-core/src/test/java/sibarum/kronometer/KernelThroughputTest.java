package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
