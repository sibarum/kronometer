package sibarum.kronometer.anim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Interp;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Time;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * Tweens, motion composition, and the retrigger behaviour every animation system gets wrong.
 */
class MotionTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("a procedural tween ends on exactly 1.0, whatever the step divides into")
    void tweenEndsOnItsTarget() {
        List<Float> samples = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            // 100 ms in 30 ms steps: deliberately not a whole number of steps.
            kron.spork(() -> Tween.run(ms(100), ms(30), Ease.LINEAR, samples::add));
            kron.run();
        }
        assertEquals(1f, samples.get(samples.size() - 1), 0f, "must land exactly on the target");
        assertTrue(samples.size() >= 4, "should have sampled along the way: " + samples);
        assertEquals(0.3f, samples.get(0), 1e-6f);
    }

    @Test
    @DisplayName("sequencing is straight-line code, because a shred survives across time")
    void sequenceReadsAsCode() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> Motion.sequence(
                    Motion.of(() -> log.add("first@" + Time.now())),
                    Motion.delay(ms(50)),
                    Motion.of(() -> log.add("second@" + Time.now())),
                    Motion.delay(ms(25)),
                    Motion.of(() -> log.add("third@" + Time.now()))).play());
            kron.run();
        }
        assertEquals(List.of("first@@0s", "second@@50ms", "third@@75ms"), log);
    }

    @Test
    @DisplayName("parallel returns when the slowest branch finishes")
    void parallelWaitsForTheSlowest() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                Motion.parallel(
                        Motion.sequence(Motion.delay(ms(30)), Motion.of(() -> log.add("fast@" + Time.now()))),
                        Motion.sequence(Motion.delay(ms(80)), Motion.of(() -> log.add("slow@" + Time.now()))),
                        Motion.of(() -> log.add("instant@" + Time.now()))).play();
                log.add("joined@" + Time.now());
            });
            kron.run();
        }
        assertEquals(List.of("instant@@0s", "fast@@30ms", "slow@@80ms", "joined@@80ms"), log);
    }

    @Test
    @DisplayName("stagger cascades, and still joins on the last one")
    void staggerCascades() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                List<Motion> items = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    int index = i;
                    items.add(Motion.of(() -> log.add("row" + index + "@" + Time.now())));
                }
                Motion.stagger(ms(20), items).play();
                log.add("joined@" + Time.now());
            });
            kron.run();
        }
        assertEquals(List.of(
                "row0@@0s", "row1@@20ms", "row2@@40ms", "row3@@60ms", "joined@@60ms"), log);
    }

    @Test
    @DisplayName("parallel joining an already-finished branch does not hang")
    void parallelHandlesAlreadyFinishedBranches() {
        // The check-then-act that would be a race anywhere else: a Trigger does not latch, so awaiting
        // one that already fired would block forever. Holding the baton makes it safe, and this asserts
        // it rather than trusting it — every branch here finishes before the join begins.
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                Motion.parallel(
                        Motion.of(() -> log.add("a")),
                        Motion.of(() -> log.add("b")),
                        Motion.of(() -> log.add("c"))).play();
                log.add("joined");
            });
            kron.run();
        }
        assertEquals(List.of("a", "b", "c", "joined"), log);
    }

    @Test
    @DisplayName("repeat plays a motion over")
    void repeatPlaysOver() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> Motion.sequence(
                    Motion.of(() -> log.add("beat@" + Time.now())),
                    Motion.delay(ms(10))).repeat(3).play());
            kron.run();
        }
        assertEquals(List.of("beat@@0s", "beat@@10ms", "beat@@20ms"), log);
    }

    // ---------------------------------------------------------- retriggering

    @Test
    @DisplayName("a retargeted cell continues from its current value rather than snapping")
    void retargetIsContinuous() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Cell<Double> lift = kron.cell("lift", 0.0);
            Animator animator = new Animator(kron);
            Rate frames = kron.fixed("frames", ms(20));
            kron.effect(frames, () -> observed.add(Time.now() + "=" + round(lift.get())));

            kron.spork(() -> {
                animator.retarget(lift, 1.0, ms(100), Ease.LINEAR);   // hover in
                Time.advance(ms(40));                                 // interrupt at 40 %
                animator.retarget(lift, 0.0, ms(100), Ease.LINEAR);   // hover out
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(140)));
        }
        // At 40 ms the value is 0.4. The reversal starts *from* 0.4 and unwinds, rather than snapping to
        // 1.0 and running back — which is the bug this whole class exists to avoid. Nothing had to
        // cancel anything: a fresh curve anchored at now simply replaces the old one.
        assertEquals(List.of(
                "@20ms=0.2", "@40ms=0.4", "@60ms=0.32", "@80ms=0.24",
                "@100ms=0.16", "@120ms=0.08", "@140ms=0.0"), observed);
    }

    @Test
    @DisplayName("a retargeted cell is still fully predictable")
    void retargetStaysPredictable() {
        try (Kron kron = Kron.virtual()) {
            Cell<Double> lift = kron.cell("lift", 0.0);
            Animator animator = new Animator(kron);
            kron.spork(() -> {
                animator.retarget(lift, 1.0, ms(100), Ease.OUT_CUBIC);
                // The interruption is a curve, not a thread, so the whole animation remains something
                // the precompute pool can evaluate ahead.
                assertEquals(Moment.FOREVER, lift.horizon());
                assertTrue(lift.isDriven());
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("a keyed procedural motion is cancelled by its replacement")
    void keyedMotionIsReplaced() {
        try (Kron kron = Kron.virtual()) {
            Animator animator = new Animator(kron);
            kron.spork(() -> {
                animator.play("slide", Motion.sequence(
                        Motion.delay(ms(100)),
                        Motion.of(() -> log.add("first finished"))));
                Time.advance(ms(30));
                animator.play("slide", Motion.sequence(
                        Motion.delay(ms(40)),
                        Motion.of(() -> log.add("second finished@" + Time.now()))));
            });
            kron.run();
        }
        // The first never completes: its replacement cancelled it on the timeline.
        assertEquals(List.of("second finished@@70ms"), log);
    }

    @Test
    @DisplayName("a tween curve honours its ease and its interp")
    void tweenCurveShapesAndBlends() {
        try (Kron kron = Kron.virtual()) {
            Cell<Turn> angle = kron.cell("angle", Turn.of(0.9));
            kron.spork(() -> {
                // A shortest-arc tween across the wrap, shaped by an ease.
                angle.drive(Tween.curve(
                        Turn.of(0.9), Turn.of(0.1), ms(100), Ease.LINEAR, Turn.SHORTEST));
                Time.advance(ms(50));
                assertEquals(1.0, angle.get().turns(), 1e-9);
                Time.advance(ms(50));
                assertEquals(0.1, angle.get().wrapped().turns(), 1e-9);
            });
            kron.run();
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
