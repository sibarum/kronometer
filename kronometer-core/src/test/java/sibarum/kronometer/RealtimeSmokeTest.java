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
