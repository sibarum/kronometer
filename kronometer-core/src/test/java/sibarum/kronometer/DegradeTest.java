package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * Degradation: the only real answer to slip that never drains.
 *
 * <p>Slip that plateaus is not a scheduling problem, it is a capacity problem, and no settlement
 * policy fixes it — the machine is simply being asked for more than it has. The honest response is to
 * ask for less. The hard part is doing that without oscillating at the threshold, which is what the
 * asymmetric counters are for: quick to degrade, slow to restore.
 */
class DegradeTest {

    @Test
    @DisplayName("sustained slip steps the rate down, and stops once it fits")
    void sustainedSlipDegradesTheRate() {
        List<Dur> changes = new ArrayList<>();
        ManualWall wall = new ManualWall();
        try (Kron kron = Kron.of(Clock.realtime(wall).repayment(Repayment.none()))) {
            Rate frames = kron.fixed(ms(10)).degrade(ms(10), ms(20), ms(40));
            frames.onRateChange(rate -> changes.add(rate.period()));
            frames.each(step -> {
                if (step.index() == 2 && changes.isEmpty()) {
                    wall.advance(ms(45));     // a stall the repayment policy will never pay back
                }
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(2_000)));
        }

        // 35 ms of permanent debt does not fit in a 10 ms period, nor in 20 ms, but it does fit in
        // 40 ms — so the ladder walks down exactly two rungs and then stops. It does not keep
        // degrading, and it does not oscillate back up, because the debt never drains.
        assertEquals(List.of(ms(20), ms(40)), changes);
    }

    @Test
    @DisplayName("a rate that recovers is restored, but only after a long clean run")
    void recoveredSlipRestoresTheRate() {
        List<Dur> changes = new ArrayList<>();
        ManualWall wall = new ManualWall();
        try (Kron kron = Kron.of(Clock.realtime(wall).repayment(Repayment.unbounded()))) {
            Rate frames = kron.fixed(ms(10)).degrade(ms(10), ms(20));
            frames.onRateChange(rate -> changes.add(rate.period()));
            frames.each(step -> {
                if (step.index() == 2 && changes.isEmpty()) {
                    wall.advance(ms(45));
                }
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(60_000)));
        }

        // Down once under the stall, back up once the debt has been clear for a long stretch. Exactly
        // two changes: the hysteresis is what stops this being a flutter.
        assertEquals(List.of(ms(20), ms(10)), changes);
    }

    @Test
    @DisplayName("a healthy run never degrades")
    void healthyRunKeepsItsRate() {
        List<Dur> changes = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Rate frames = kron.fixed(ms(10)).degrade(ms(10), ms(20), ms(40));
            frames.onRateChange(rate -> changes.add(rate.period()));
            frames.each(step -> { });
            kron.runUntil(Moment.ORIGIN.plus(ms(5_000)));
            assertEquals(ms(10), frames.period());
        }
        assertTrue(changes.isEmpty(), "an unpaced clock has no slip, so nothing to degrade for");
    }
}
