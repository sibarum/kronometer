package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * A world whose time is counted by the steps that finish: due on the domain's grid, run elsewhere, forgiven rather
 * than repaid when it falls behind, and blended by a prediction of when the next step lands.
 */
class DilatedTest {

    @Test
    @DisplayName("a step is due on the grid, taken once, and game time moves only when it finishes")
    void gameTimeIsCountedByFinishedSteps() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            assertFalse(world.take(), "nothing is due yet");

            kron.tick(ms(20).nanos());
            assertEquals(1, world.due());
            assertTrue(world.take());
            assertFalse(world.take(), "taken once");
            assertEquals(Dur.ZERO, world.time(), "not finished, so no time has passed in the world");
            world.finished(1);
            assertEquals(ms(20), world.time());
            assertEquals(ms(20), world.dt());
        }
    }

    @Test
    @DisplayName("the world never runs ahead of the wall: steps run only as the grid makes them due")
    void neverAhead() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            int ran = 0;
            for (int t = 20; t <= 200; t += 20) {
                kron.tick(ms(t).nanos());
                while (world.take()) {
                    world.finished(ms(t).nanos());
                    ran++;
                }
            }
            assertEquals(10, ran, "ten grid lines in 200 ms, however fast the steps are");
            assertEquals(ms(200), world.time());
            assertEquals(0, world.forgiven());
            assertEquals(1.0, world.dilation(), 1e-9, "full speed");
        }
    }

    @Test
    @DisplayName("a world that falls behind forgives what it owes past the bound, and its time runs slow")
    void behindIsForgivenNotRepaid() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            for (int t = 20; t <= 1200; t += 20) {   // sixty due while one step was running
                kron.tick(ms(t).nanos());
            }
            assertEquals(60, world.due());
            int ran = 0;
            while (world.take()) {
                world.finished(ms(1200).nanos() + ran);
                ran++;
            }
            assertEquals(2, ran, "two kept to run, as asked");
            assertEquals(58, world.forgiven());
            assertEquals(ms(40), world.time(), "the world is 1160 ms behind the wall, and stays so");
            assertEquals(2.0 / 60, world.dilation(), 1e-9, "two steps finished for sixty due");
        }
    }

    @Test
    @DisplayName("the phase runs from the newest finished step over the predicted interval, and holds at 1")
    void phaseIsByPrediction() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            assertEquals(1f, world.phase(0), "nothing finished: show the newest");
            for (int k = 1; k <= 3; k++) {
                kron.tick(ms(20L * k).nanos());
                assertTrue(world.take());
                world.finished(ms(20L * k).nanos());
            }
            assertEquals(ms(20), world.predicted(), "steps landing every 20 ms predict 20 ms");
            assertEquals(0.5f, world.phase(ms(70).nanos()), 1e-6f);
            assertEquals(1f, world.phase(ms(95).nanos()), "late: held on the newest, never run past it");
        }
    }

    @Test
    @DisplayName("the prediction follows the steps as they slow, a share of each new interval at a time")
    void predictionIsCorrectedByEachStep() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            long at = 0;
            for (int k = 0; k < 4; k++) {
                kron.tick(ms(20L * (k + 1)).nanos());
                world.take();
                at += ms(20).nanos();
                world.finished(at);
            }
            kron.tick(ms(100).nanos());
            world.take();
            at += ms(60).nanos();                      // one slow step
            world.finished(at);
            double expected = ms(20).nanos() + Dilated.GAIN * (ms(60).nanos() - ms(20).nanos());
            assertEquals(expected, world.predicted().nanos(), 1);
        }
    }

    @Test
    @DisplayName("held, nothing is due; released, the pause is neither run nor taken into the prediction")
    void holding() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            for (int k = 1; k <= 3; k++) {
                kron.tick(ms(20L * k).nanos());
                world.take();
                world.finished(ms(20L * k).nanos());
            }
            world.hold(true);
            for (int t = 80; t <= 1000; t += 20) {
                kron.tick(ms(t).nanos());
            }
            assertFalse(world.take(), "held: nothing due");
            world.hold(false);
            kron.tick(ms(1020).nanos());
            assertTrue(world.take());
            assertFalse(world.take(), "the pause is not replayed");
            world.finished(ms(1020).nanos());
            assertEquals(ms(20), world.predicted(), "the interval across the pause is not a step's");
            assertEquals(0, world.forgiven(), "nothing was owed: the pause made nothing due");
        }
    }

    @Test
    @DisplayName("a step coming due tells the stepping thread, on the timeline")
    void dueIsAnnounced() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            AtomicInteger told = new AtomicInteger();
            world.onDue(told::incrementAndGet);
            kron.tick(ms(60).nanos());
            assertEquals(3, told.get());
        }
    }

    @Test
    @DisplayName("the reading is taken over a second of due steps, and holds between")
    void theReadingIsSteady() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            kron.tick(ms(400).nanos());
            assertEquals(1.0, world.dilation(), "twenty due: too few to read, so the reading is what it was");
        }
    }

    @Test
    @DisplayName("a replaced world forgives every step it owed")
    void discardForgivesEverything() {
        try (Kron kron = Kron.driven()) {
            Dilated world = kron.fixed("physics", ms(20)).dilated(2);
            kron.tick(ms(40).nanos());
            world.discard();
            assertFalse(world.take());
            assertEquals(2, world.forgiven());
        }
    }

    @Test
    @DisplayName("a dilated domain is a fixed one, and owes at least a step")
    void onlyFixed() {
        try (Kron kron = Kron.driven()) {
            assertThrows(IllegalArgumentException.class, () -> kron.fixed("physics", ms(20)).dilated(0));
        }
    }
}
