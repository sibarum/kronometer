package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The only tests here that watch a real clock, and they assert as little as possible.
 *
 * <p>Everything interesting about the slip model is arithmetic and lives in {@link SlipModelTest},
 * where it is exact. What is left for a real wall is the question arithmetic cannot answer — that
 * {@link Wall#system()} actually paces, and roughly correctly — so these use wide tolerances on
 * purpose. A timing test with tight bounds is a test that fails on a busy machine and teaches nobody
 * anything.
 */
class RealtimeSmokeTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("200 ms of logical time takes about 200 ms of wall-clock time")
    void realtimeActuallyPaces() {
        long started = System.nanoTime();
        try (Kron kron = Kron.realtime()) {
            kron.spork("frames", () -> {
                Metro metro = Metro.hz(60);
                for (int i = 0; i < 12; i++) {         // 12 frames ≈ 200 ms
                    metro.tick();
                }
            });
            kron.run();
        }
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        assertTrue(elapsedMs > 150, "should have actually waited, took " + elapsedMs + " ms");
        assertTrue(elapsedMs < 1_000, "should not have waited far too long, took " + elapsedMs + " ms");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an unloaded 60 Hz run accrues little slip")
    void unloadedRunStaysNearlyOnTime() {
        Realtime clock = Clock.realtime();
        try (Kron kron = Kron.of(clock)) {
            kron.spork("frames", () -> {
                Metro metro = Metro.hz(60);
                for (int i = 0; i < 30; i++) {
                    metro.tick();
                }
            });
            kron.run();
            // Half a frame of accumulated debt over half a second would be a real problem; anything
            // under that is the machine, not the kernel.
            assertTrue(clock.slip().compareTo(ms(8)) < 0, "slip after 30 frames: " + clock.slip());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a real stall becomes slip, and is reported")
    void aRealStallIsReported() {
        List<Overrun> events = new ArrayList<>();
        Realtime clock = Clock.realtime().repayment(Repayment.none());
        clock.onOverrun(events::add);

        try (Kron kron = Kron.of(clock)) {
            kron.spork("frames", () -> {
                Metro metro = Metro.of(ms(10));
                for (int i = 0; i < 6; i++) {
                    metro.tick();
                    if (i == 2) {
                        burnWallClock(ms(60));      // a segment that takes six frames to finish
                    }
                }
            });
            kron.run();
        }

        List<Overrun> late = events.stream().filter(e -> e.kind() == Overrun.Kind.LATE).toList();
        assertTrue(!late.isEmpty(), "a 60 ms stall on a 10 ms grid must be reported");
        assertTrue(clock.slip().compareTo(ms(30)) > 0,
                "slip should reflect most of the stall, was " + clock.slip());
        assertEquals(Settlement.SLIP, late.get(0).settlement());
    }

    /** Spin, rather than sleep: a blocked segment and a busy segment are the same thing to the kernel. */
    private static void burnWallClock(Dur howLong) {
        long until = System.nanoTime() + howLong.nanos();
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }
}
