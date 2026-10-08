package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.kronometer.Dur.ms;

/**
 * A fixed domain's steps run by the host, after the tick, as a render thread runs GPU work: due on the timeline,
 * done off it, and blended between the last two that ran.
 */
class HandoffTest {

    @Test
    @DisplayName("the steps due in a tick are run by the host, in order, with the domain's dt")
    void theHostRunsWhatCameDue() {
        try (Kron kron = Kron.driven()) {
            Handoff steps = kron.fixed("physics", ms(20)).handoff();
            List<Long> ran = new ArrayList<>();

            kron.tick(ms(50).nanos());
            assertEquals(2, steps.pending(), "20 ms and 40 ms are due by 50 ms");
            assertEquals(2, steps.drain(step -> {
                ran.add(step.index());
                assertEquals(ms(20), step.dt());
            }));
            assertEquals(0, steps.pending());

            kron.tick(ms(60).nanos());
            steps.drain(step -> ran.add(step.index()));
            assertEquals(3, ran.size());
            assertEquals(List.of(ran.get(0), ran.get(0) + 1, ran.get(0) + 2), ran, "in order, none twice");
        }
    }

    @Test
    @DisplayName("the phase runs from the last step that ran towards the next, and holds there")
    void thePhaseIsBetweenTheStepsThatRan() {
        try (Kron kron = Kron.driven()) {
            Handoff steps = kron.fixed("physics", ms(20)).handoff();
            assertEquals(1f, steps.phase(kron.now()), "nothing has run: show the newest");

            kron.tick(ms(20).nanos());
            steps.drain(step -> { });
            assertEquals(0f, steps.phase(kron.now()), 1e-6f, "on the step that just ran");

            kron.tick(ms(30).nanos());
            steps.drain(step -> { });
            assertEquals(0.5f, steps.phase(kron.now()), 1e-6f, "halfway to the next");

            kron.tick(ms(40).nanos());
            assertEquals(1f, steps.phase(kron.now()), 1e-6f, "due but not drained: it has not run, so it does not count");
            steps.drain(step -> { });
            assertEquals(0f, steps.phase(kron.now()), 1e-6f);
        }
    }

    @Test
    @DisplayName("held, nothing runs and the picture rests; released, the pause is not replayed")
    void holdingDropsTheStepsAndReleasingDoesNotReplayThem() {
        try (Kron kron = Kron.driven()) {
            Moment origin = kron.now();
            Handoff steps = kron.fixed("physics", ms(20)).handoff();
            kron.tick(ms(20).nanos());
            steps.drain(step -> { });

            steps.hold(true);
            for (int t = 40; t <= 200; t += 20) {
                kron.tick(ms(t).nanos());
            }
            assertEquals(0, steps.drain(step -> { }), "held: nothing to run");
            assertEquals(1f, steps.phase(kron.now()), "resting on the last step that ran");
            assertFalse(kron.domains().getFirst().hasHandlers(), "held, the domain has nothing on it, and parks");

            steps.hold(false);
            kron.tick(ms(220).nanos());
            assertEquals(1, steps.drain(step -> assertEquals(origin.plus(ms(220)), step.at())),
                    "only the step after the release");
        }
    }

    @Test
    @DisplayName("discarding forgets what came due, as a reset must")
    void discardForgetsWhatWasDue() {
        try (Kron kron = Kron.driven()) {
            Handoff steps = kron.fixed("physics", ms(20)).handoff();
            kron.tick(ms(60).nanos());
            steps.discard();
            assertEquals(0, steps.drain(step -> { }));
            assertEquals(3, steps.dropped());
        }
    }

    @Test
    @DisplayName("a dynamic domain has no grid to say when a step is due, so it cannot hand one off")
    void onlyAFixedDomain() {
        try (Kron kron = Kron.driven()) {
            assertThrows(IllegalArgumentException.class, () -> kron.dynamic("frames").handoff());
        }
    }
}
