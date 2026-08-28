package sibarum.kronometer.anim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Time;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * The two policies a retrigger can have towards the motion it displaces.
 *
 * <p>Both are correct, for different things, and the difference only shows when a second play arrives
 * inside the first one's duration — which is to say, when a user is quick.
 */
class SupersedeTest {

    /** One shared slot both plays write, the way a decoration overlay is shared. */
    private final List<String> slot = new ArrayList<>();

    @Test
    @DisplayName("unwinding: the loser's finally runs before the winner starts")
    void theLoserUnwindsByDefault() {
        List<String> order = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Animator animator = new Animator(kron);
            kron.spork(() -> {
                animator.play("slot", () -> {
                    try {
                        order.add("first starts@" + Time.now());
                        Time.advance(ms(200));
                    } finally {
                        order.add("first unwinds@" + Time.now());
                    }
                });
                Time.advance(ms(50));
                animator.play("slot", () -> order.add("second starts@" + Time.now()));
            });
            kron.run();
        }
        assertEquals(List.of("first starts@@0s", "first unwinds@@50ms", "second starts@@50ms"), order);
    }

    @Test
    @DisplayName("abandoning: a superseded play's samples and its teardown both become no-ops")
    void aClaimSilencesTheLoser() {
        try (Kron kron = Kron.virtual()) {
            Animator animator = new Animator(kron);
            kron.spork(() -> {
                animator.play("slot", () -> cue(animator, "first"));
                Time.advance(ms(50));
                animator.play("slot", () -> cue(animator, "second"));
            });
            kron.run();
        }

        // The bug this exists to prevent: without the claim, the first cue's teardown clears the paint
        // the second one just put down, and the flash erases itself part-way through.
        assertTrue(slot.stream().anyMatch(s -> s.startsWith("second")),
                "the winner painted: " + slot);
        assertFalse(slot.contains("clear by first"),
                "the loser's cleanup must not undo the winner: " + slot);
        assertEquals("clear by second", slot.get(slot.size() - 1));
    }

    @Test
    @DisplayName("a claim taken by the only play stays current to the end")
    void anUninterruptedClaimSurvives() {
        try (Kron kron = Kron.virtual()) {
            Animator animator = new Animator(kron);
            kron.spork(() -> animator.play("slot", () -> cue(animator, "only")));
            kron.run();
        }
        assertTrue(slot.contains("clear by only"), slot.toString());
        assertTrue(slot.stream().filter(s -> s.startsWith("only")).count() > 1, slot.toString());
    }

    @Test
    @DisplayName("stop invalidates the claim too, so a teardown after it is silent")
    void stopEndsTheTurn() {
        try (Kron kron = Kron.virtual()) {
            Animator animator = new Animator(kron);
            kron.spork(() -> {
                animator.play("slot", () -> cue(animator, "first"));
                Time.advance(ms(50));
                animator.stop("slot");
            });
            kron.run();
        }
        assertFalse(slot.contains("clear by first"), slot.toString());
    }

    /** A one-shot decoration: it exists only in the middle, and its cleanup is "put the slot back". */
    private void cue(Animator animator, String who) {
        Animator.Claim mine = animator.claim("slot");
        Tween.ramp(ms(200), ms(25), Ease.OUT_CUBIC,
                a -> mine.ifCurrent(() -> slot.add(who + " " + a)),
                () -> mine.ifCurrent(() -> slot.add("clear by " + who)));
    }
}
