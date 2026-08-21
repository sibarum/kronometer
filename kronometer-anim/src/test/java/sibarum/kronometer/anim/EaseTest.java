package sibarum.kronometer.anim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EaseTest {

    /**
     * Every named ease in the interface, found once so the endpoint test cannot fall out of date when a
     * new one is added — which is the failure mode a hand-written list has.
     *
     * <p>The one place reflection appears in this project, and only in a test. Nothing in the shipped
     * modules uses it, so native-image reachability is unaffected.
     */
    private static Map<String, Ease> allEases() {
        Map<String, Ease> eases = new LinkedHashMap<>();
        for (Field field : Ease.class.getDeclaredFields()) {
            if (Ease.class.isAssignableFrom(field.getType())) {
                try {
                    eases.put(field.getName(), (Ease) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return eases;
    }

    @Test
    @DisplayName("every ease returns exactly 0 at 0 and exactly 1 at 1")
    void endpointsAreExact() {
        Map<String, Ease> eases = allEases();
        assertTrue(eases.size() >= 14, "expected the whole library, found " + eases.keySet());

        eases.forEach((name, ease) -> {
            // Exact equality, not a tolerance. An animation that stops at 0.999 of its target leaves a
            // shadow that never quite settles, and 2^-10 is what the textbook exponential ease returns
            // at zero.
            assertEquals(0f, ease.at(0f), 0f, name + " must start at exactly 0");
            assertEquals(1f, ease.at(1f), 0f, name + " must end at exactly 1");
        });
    }

    @Test
    @DisplayName("derived eases pin their endpoints too")
    void derivedEndpointsAreExact() {
        assertEquals(0f, Ease.bezier(0.42f, 0f, 0.58f, 1f).at(0f));
        assertEquals(1f, Ease.bezier(0.42f, 0f, 0.58f, 1f).at(1f));
        assertEquals(0f, Ease.steps(5).at(0f));
        assertEquals(1f, Ease.steps(5).at(1f));
        assertEquals(0f, Ease.OUT_EXPO.reversed().at(0f));
        assertEquals(1f, Ease.OUT_EXPO.reversed().at(1f));
        assertEquals(0f, Ease.of(t -> t * 0.5f).at(0f));
        assertEquals(1f, Ease.of(t -> t * 0.5f).at(1f));
    }

    @Test
    @DisplayName("eases are monotonic where they claim to be")
    void standardEasesAreMonotonic() {
        Map<String, Ease> eases = allEases();
        eases.forEach((name, ease) -> {
            if (name.contains("BACK")) {
                return;                              // overshoot is the point of these
            }
            float previous = ease.at(0f);
            for (int i = 1; i <= 100; i++) {
                float value = ease.at(i / 100f);
                assertTrue(value >= previous - 1e-6f,
                        name + " went backwards at t=" + (i / 100f) + ": " + previous + " -> " + value);
                previous = value;
            }
        });
    }

    @Test
    @DisplayName("a linear bezier is the identity")
    void linearBezierIsIdentity() {
        Ease linear = Ease.bezier(1f / 3, 1f / 3, 2f / 3, 2f / 3);
        for (int i = 0; i <= 10; i++) {
            float t = i / 10f;
            assertEquals(t, linear.at(t), 1e-3f, "at t=" + t);
        }
    }

    @Test
    @DisplayName("steps quantizes, and the last step lands on 1")
    void stepsQuantize() {
        Ease four = Ease.steps(4);
        assertEquals(0f, four.at(0.1f));
        assertEquals(0.25f, four.at(0.3f));
        assertEquals(0.5f, four.at(0.6f));
        assertEquals(0.75f, four.at(0.9f));
        assertEquals(1f, four.at(1f));
        assertThrows(IllegalArgumentException.class, () -> Ease.steps(0));
    }

    @Test
    @DisplayName("reversed() mirrors the shape")
    void reversedMirrors() {
        Ease out = Ease.OUT_CUBIC;
        Ease back = out.reversed();
        for (int i = 1; i < 10; i++) {
            float t = i / 10f;
            assertEquals(1 - out.at(1 - t), back.at(t), 1e-6f, "at t=" + t);
        }
    }
}
