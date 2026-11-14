package sibarum.kronometer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Asserts that two runs observed the same events, and when they did not, says where they parted.
 *
 * <p>The differential and determinism tests compare whole event logs, hundreds of lines each. Handing
 * those to {@code assertEquals} fails with both logs printed in full, which hides the one line that
 * matters. This reports the first event that differs instead: its index, both sides of it, and the
 * event before it, which is the last thing the two runs agreed on.
 */
final class Divergence {

    private Divergence() {
    }

    /**
     * @param context    what was being compared, e.g. {@code "seed 31"}
     * @param reference  label for the run taken as correct, e.g. {@code "NEVER"}
     * @param other      label for the run checked against it, e.g. {@code "EAGER"}
     */
    static void assertSameEvents(String context, String reference, List<String> expected,
                                 String other, List<String> actual) {
        int common = Math.min(expected.size(), actual.size());
        int i = 0;
        while (i < common && expected.get(i).equals(actual.get(i))) {
            i++;
        }
        if (i == expected.size() && i == actual.size()) {
            return;
        }
        int width = Math.max("last agreed".length(), Math.max(reference.length(), other.length()));
        fail(context + ": " + other + " diverged from " + reference + " at event " + i
                + " (" + reference + " has " + expected.size() + " events, " + other + " has " + actual.size() + ")"
                + "\n  " + pad("last agreed", width) + "  " + (i == 0 ? "(none: the first event differs)" : expected.get(i - 1))
                + "\n  " + pad(reference, width) + "  " + (i < expected.size() ? expected.get(i) : "(no more events)")
                + "\n  " + pad(other, width) + "  " + (i < actual.size() ? actual.get(i) : "(no more events)"));
    }

    private static String pad(String label, int width) {
        String withColon = label + ":";
        return withColon + " ".repeat(Math.max(0, width + 1 - withColon.length()));
    }
}
