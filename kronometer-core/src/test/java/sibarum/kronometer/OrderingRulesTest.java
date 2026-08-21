package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.kronometer.Dur.ZERO;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Time.advance;
import static sibarum.kronometer.Time.now;
import static sibarum.kronometer.Time.spork;

/**
 * The five ordering rules of {@code docs/architecture.md} §4, as executable specification.
 *
 * <p>These are the contract, not implementation detail — they are what makes a run reproducible, so
 * each one gets a test that fails if the rule is broken. Rule 4 (rate-domain priority) arrives with
 * rate domains in M3.
 */
class OrderingRulesTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("Rule 1: shreds waking at the same moment run in sequence-number order")
    void rule1_sameMomentRunsInSchedulingOrder() {
        try (Kron kron = Kron.virtual()) {
            // Two shreds arrive at 20 ms having decided to be there at different times: `early`
            // committed at 0 ms, `late` only at 5 ms. Oldest scheduling decision wins, which is not
            // the same as whoever happens to be sporked first.
            kron.spork("late", () -> {
                advance(ms(5));
                advance(ms(15));
                log.add("late");
            });
            kron.spork("early", () -> {
                advance(ms(20));
                log.add("early");
            });
            kron.run();
        }
        assertEquals(List.of("early", "late"), log);
    }

    @Test
    @DisplayName("Rule 2: spork returns immediately; the child runs later in the same step")
    void rule2_childStartsAfterTheParentSegment() {
        try (Kron kron = Kron.virtual()) {
            kron.spork("parent", () -> {
                log.add("parent-before-spork");
                spork("child", () -> log.add("child"));
                log.add("parent-after-spork");   // the segment is atomic: the child cannot interleave
                advance(ZERO);
                log.add("parent-next-segment");
            });
            kron.run();
        }
        assertEquals(
                List.of("parent-before-spork", "parent-after-spork", "child", "parent-next-segment"),
                log);
    }

    @Test
    @DisplayName("Rule 3: work enqueued at the current moment runs in that same step")
    void rule3_zeroDelayWorkRunsBeforeTimeAdvances() {
        try (Kron kron = Kron.virtual()) {
            kron.spork("a", () -> {
                log.add("a@" + now());
                spork("b", () -> {
                    log.add("b@" + now());
                    spork("c", () -> log.add("c@" + now()));
                });
                advance(ms(1));
                log.add("a-again@" + now());
            });
            kron.run();
        }
        // The whole spork chain resolves at moment 0. Time only moves once nothing is left there.
        assertEquals(List.of("a@@0s", "b@@0s", "c@@0s", "a-again@@1ms"), log);
    }

    @Test
    @DisplayName("Rule 3: a shred that keeps enqueueing zero-delay work stalls logical time")
    void rule3_zeroDelayWorkCanStallTime() {
        try (Kron kron = Kron.virtual()) {
            kron.spork("greedy", () -> {
                for (int i = 0; i < 1_000; i++) {
                    advance(ZERO);
                }
                log.add("done@" + now());
            });
            kron.run();
        }
        // A thousand yields, and logical time has not moved. Legal, and exactly why the segment
        // budget diagnostic exists (§11).
        assertEquals(List.of("done@@0s"), log);
    }

    @Test
    @DisplayName("Rule 5: post from off the timeline lands at the moment it declared")
    void rule5_postLandsAtItsDeclaredMoment() {
        try (Kron kron = Kron.virtual()) {
            kron.post(Moment.ORIGIN.plus(ms(50)), () -> log.add("posted@" + now()));
            kron.spork("shred", () -> {
                advance(ms(100));
                log.add("shred@" + now());
            });
            kron.run();
        }
        assertEquals(List.of("posted@@50ms", "shred@@100ms"), log);
    }

    @Test
    @DisplayName("Rule 5: a post with no declared moment is rejected under the virtual clock")
    void rule5_undeclaredPostIsRejected() {
        try (Kron kron = Kron.virtual()) {
            IllegalStateException e =
                    assertThrows(IllegalStateException.class, () -> kron.post(() -> { }));
            assertEquals(true, e.getMessage().contains("reproducible"));
        }
    }

    @Test
    @DisplayName("A post whose moment has already passed is an error, not a silent catch-up")
    void postInThePastIsRejected() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> advance(ms(10)));
            kron.run();
            assertThrows(IllegalArgumentException.class,
                    () -> kron.post(Moment.ORIGIN.plus(ms(1)), () -> { }));
        }
    }
}
