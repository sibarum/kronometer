package sibarum.kronometer.anim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.kronometer.Ratio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two interpolations a lerp gets wrong: wrapped angles, and rotations.
 */
class RotationTest {

    // ---------------------------------------------------------------- turns

    @Test
    @DisplayName("angle interpolation takes the short way round across the wrap")
    void shortestArcCrossesTheWrap() {
        Turn from = Turn.of(0.9);
        Turn to = Turn.of(0.1);

        // The whole point: 0.9 -> 0.1 is a fifth of a turn *forwards* through zero, not four fifths
        // backwards through the middle. A plain lerp would spin the wheel the wrong way.
        assertEquals(0.95, Turn.SHORTEST.between(from, to, 0.25f).turns(), 1e-9);
        assertEquals(1.0, Turn.SHORTEST.between(from, to, 0.5f).turns(), 1e-9);
        assertEquals(0.05, Turn.SHORTEST.between(from, to, 0.75f).wrapped().turns(), 1e-9);

        // And the direct interpolation, for when the winding matters, goes the long way as asked.
        assertEquals(0.7, Turn.DIRECT.between(from, to, 0.25f).turns(), 1e-9);
        assertEquals(0.5, Turn.DIRECT.between(from, to, 0.5f).turns(), 1e-9);
    }

    @Test
    @DisplayName("the shortest arc is shortest in both directions")
    void shortestArcIsSymmetric() {
        // Backwards across the wrap, which is the other half of the same bug.
        Turn mid = Turn.SHORTEST.between(Turn.of(0.1), Turn.of(0.9), 0.5f);
        assertEquals(0.0, mid.wrapped().turns(), 1e-9);

        // And a plain interior case is untouched.
        assertEquals(0.35, Turn.SHORTEST.between(Turn.of(0.2), Turn.of(0.5), 0.5f).turns(), 1e-9);
    }

    @Test
    @DisplayName("a half-turn tie resolves the same way every time")
    void halfTurnIsDeterministic() {
        // There is no right answer for exactly half a turn, so the requirement is that it is the *same*
        // answer every time — otherwise a run stops being reproducible.
        double first = Turn.SHORTEST.between(Turn.ZERO, Turn.HALF, 0.5f).turns();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, Turn.SHORTEST.between(Turn.ZERO, Turn.HALF, 0.5f).turns());
        }
    }

    @Test
    @DisplayName("wrapping is exact, because a turn wraps at 1 rather than at 2 pi")
    void wrappingIsExact() {
        // Accumulate a thousand exact eighth-turns and the phase is still exactly on an eighth. The same
        // loop in radians drifts, because the wrap divides by an irrational.
        Turn phase = Turn.ZERO;
        for (int i = 0; i < 1000; i++) {
            phase = phase.plus(Turn.of(0.125)).wrapped();
        }
        assertEquals(0.0, phase.turns(), 0.0, "exactly zero, not nearly");
    }

    @Test
    @DisplayName("a ratio read as a slope has an angle in turns")
    void slopeAndAngleAgree() {
        // Unity slope is an eighth of a turn — 45 degrees — which is the anchor of the whole scheme.
        assertEquals(0.125, Turn.ofSlope(Ratio.ONE).turns(), 1e-12);
        assertEquals(1.0, Turn.of(0.125).slope(), 1e-12);

        // And the round trip holds for a musical ratio.
        Ratio fifth = Ratio.of(3, 2);
        assertEquals(1.5, Turn.ofSlope(fifth).slope(), 1e-12);
    }

    @Test
    @DisplayName("unit conversions round-trip")
    void unitConversions() {
        assertEquals(0.25, Turn.ofDegrees(90).turns(), 1e-12);
        assertEquals(0.5, Turn.ofRadians(Math.PI).turns(), 1e-12);
        assertEquals(180.0, Turn.HALF.degrees(), 1e-12);
        assertEquals(Math.PI, Turn.HALF.radians(), 1e-12);
        assertEquals(1.0, Turn.QUARTER.sin(), 1e-12);
    }

    // ------------------------------------------------------------ rotations

    @Test
    @DisplayName("slerp holds constant angular velocity")
    void slerpIsConstantAngularVelocity() {
        Hyper a = Hyper.rotation(Turn.ZERO, 0, 0, 1);
        Hyper b = Hyper.rotation(Turn.of(0.25), 0, 0, 1);      // a quarter turn about z

        // Sample evenly and measure the angle between consecutive samples. Slerp keeps them equal;
        // a component-wise lerp does not, which is what makes a lerped rotation speed up in the middle.
        double[] slerpSteps = new double[8];
        double[] lerpSteps = new double[8];
        for (int i = 0; i < 8; i++) {
            float t0 = i / 8f;
            float t1 = (i + 1) / 8f;
            slerpSteps[i] = angleBetween(
                    Hyper.SLERP.between(a, b, t0), Hyper.SLERP.between(a, b, t1));
            lerpSteps[i] = angleBetween(
                    Hyper.LINEAR.between(a, b, t0).normalized(),
                    Hyper.LINEAR.between(a, b, t1).normalized());
        }
        for (int i = 1; i < 8; i++) {
            assertEquals(slerpSteps[0], slerpSteps[i], 1e-9,
                    "slerp step " + i + " should match step 0");
        }
        assertNotEquals(lerpSteps[0], lerpSteps[3], "a lerp is not constant velocity, by construction");
    }

    @Test
    @DisplayName("slerp takes the short way round the double cover")
    void slerpUsesTheShorterPath() {
        Hyper a = Hyper.rotation(Turn.of(0.02), 0, 0, 1);
        Hyper b = Hyper.rotation(Turn.of(0.02), 0, 0, 1).scaled(-1);   // same rotation, opposite sign

        // q and -q are the same rotation, so the distance between them is zero, not half a revolution.
        // Without the sign flip a two-degree turn interpolates the 358-degree way round.
        Hyper mid = Hyper.SLERP.between(a, b, 0.5f);
        assertEquals(0.0, angleBetween(a, mid), 1e-6);
    }

    @Test
    @DisplayName("slerp survives near-parallel inputs without dividing by nearly nothing")
    void slerpHandlesNearParallel() {
        Hyper a = Hyper.rotation(Turn.ZERO, 0, 0, 1);
        Hyper b = Hyper.rotation(Turn.of(1e-9), 0, 0, 1);
        Hyper mid = Hyper.SLERP.between(a, b, 0.5f);
        assertEquals(1.0, mid.norm(), 1e-9, "still a unit rotation");
        assertFalse(Double.isNaN(mid.re()), "sin(theta) -> 0 must not produce NaN");
    }

    @Test
    @DisplayName("slerp endpoints are the inputs")
    void slerpEndpoints() {
        Hyper a = Hyper.rotation(Turn.of(0.1), 1, 0, 0);
        Hyper b = Hyper.rotation(Turn.of(0.4), 0, 1, 0);
        assertEquals(0.0, angleBetween(a, Hyper.SLERP.between(a, b, 0f)), 1e-9);
        assertEquals(0.0, angleBetween(b, Hyper.SLERP.between(a, b, 1f)), 1e-9);
    }

    // ---------------------------------------------- the Cayley-Dickson tower

    @Test
    @DisplayName("the tower reproduces complex and quaternion multiplication")
    void productMatchesTheKnownCases() {
        // i * i = -1
        Hyper i = Hyper.complex(0, 1);
        assertEquals(Hyper.complex(-1, 0), i.times(i));

        // (1+2i)(3+4i) = -5 + 10i
        assertEquals(Hyper.complex(-5, 10), Hyper.complex(1, 2).times(Hyper.complex(3, 4)));

        // Quaternion basis: i*j = k, j*i = -k. Non-commutative, as level 2 must be.
        Hyper qi = Hyper.quaternion(0, 1, 0, 0);
        Hyper qj = Hyper.quaternion(0, 0, 1, 0);
        Hyper qk = Hyper.quaternion(0, 0, 0, 1);
        assertEquals(qk, qi.times(qj));
        assertEquals(qk.scaled(-1), qj.times(qi));
        assertNotEquals(qi.times(qj), qj.times(qi));
    }

    @Test
    @DisplayName("octonions are non-associative — which is why the tempo tree is not flattened")
    void octonionsAreNonAssociative() {
        // Level 3 loses associativity. This is not trivia: architecture 6.3 keeps the tempo tree as a
        // tree precisely because a product that depends on its bracketing cannot be collapsed into an
        // effective scale.
        Hyper e1 = unit(8, 1);
        Hyper e2 = unit(8, 2);
        Hyper e4 = unit(8, 4);

        Hyper left = e1.times(e2).times(e4);
        Hyper right = e1.times(e2.times(e4));
        assertNotEquals(left, right, "octonion multiplication must not be associative");

        // Quaternions, one level down, still are.
        Hyper q1 = unit(4, 1);
        Hyper q2 = unit(4, 2);
        Hyper q3 = unit(4, 3);
        assertEquals(q1.times(q2).times(q3), q1.times(q2.times(q3)));
    }

    @Test
    @DisplayName("levels and dimensions line up, and a non-power-of-two is refused")
    void dimensionsAreChecked() {
        assertEquals(0, Hyper.real(1).level());
        assertEquals(1, Hyper.complex(1, 0).level());
        assertEquals(2, Hyper.quaternion(1, 0, 0, 0).level());
        assertEquals(3, Hyper.of(1, 0, 0, 0, 0, 0, 0, 0).level());
        assertThrows(IllegalArgumentException.class, () -> Hyper.of(1, 2, 3));
    }

    @Test
    @DisplayName("a lower level embeds in a higher one")
    void levelsEmbed() {
        // A complex number times a quaternion should work, by zero-filling the smaller.
        Hyper result = Hyper.complex(0, 1).times(Hyper.quaternion(0, 0, 1, 0));
        assertEquals(4, result.dimension());
        assertEquals(Hyper.quaternion(0, 0, 0, 1), result);
    }

    @Test
    @DisplayName("the components array is copied in and out")
    void recordDoesNotLeakItsArray() {
        double[] source = {1, 2};
        Hyper h = new Hyper(source);
        source[0] = 99;
        assertEquals(1.0, h.re(), "constructing must copy");
        h.components()[1] = 99;
        assertEquals(2.0, h.get(1), "accessing must copy");
    }

    private static Hyper unit(int dimension, int index) {
        double[] c = new double[dimension];
        c[index] = 1;
        return new Hyper(c);
    }

    /** The angle between two unit numbers, via the dot product. */
    private static double angleBetween(Hyper a, Hyper b) {
        double dot = Math.abs(a.normalized().dot(b.normalized()));
        return Math.acos(Math.clamp(dot, -1.0, 1.0));
    }
}
