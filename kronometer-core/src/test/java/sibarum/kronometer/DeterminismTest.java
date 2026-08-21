package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Time.advance;
import static sibarum.kronometer.Time.await;
import static sibarum.kronometer.Time.now;
import static sibarum.kronometer.Time.spork;
import static sibarum.kronometer.Time.sync;

/**
 * Determinism is the property the virtual clock exists to provide, and the trace is where it is
 * visible. Two runs of the same scenario must produce identical traces — not similar, identical —
 * because that is what lets a test assert on a whole schedule rather than on sampled state.
 */
class DeterminismTest {

    /** Deliberately tangled: nested sporks, a trigger, a timeout that loses, and a cancellation. */
    private static String runScenario() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Trace trace = kron.trace();
            Trigger gong = kron.trigger("gong");

            kron.spork("ringer", () -> {
                for (int i = 0; i < 3; i++) {
                    sync(ms(100));
                    gong.broadcast();
                }
            });

            kron.spork("listener", () -> {
                for (int i = 0; i < 3; i++) {
                    boolean fired = await(gong, ms(250));
                    observed.add((fired ? "gong@" : "timeout@") + now());
                }
            });

            Shred worker = kron.spork("worker", () -> {
                Metro metro = Metro.hz(50);
                while (true) {
                    metro.tick();
                    if (metro.ticks() % 4 == 0) {
                        spork("burst", () -> {
                            advance(ms(5));
                            observed.add("burst@" + now());
                        });
                    }
                }
            });

            kron.spork("reaper", () -> {
                advance(ms(220));
                worker.cancel();
            });

            kron.run();
            return trace.render() + "---\n" + String.join("\n", observed) + "\n";
        }
    }

    @Test
    @DisplayName("1000 runs of the same scenario produce byte-identical traces")
    void identicalTracesAcrossManyRuns() {
        String reference = runScenario();
        for (int run = 1; run < 1_000; run++) {
            assertEquals(reference, runScenario(), "run " + run + " diverged from run 0");
        }
    }

    @Test
    @DisplayName("A trace records nothing that could vary between runs")
    void traceIsFreeOfWallClockAndIdentity() {
        String rendered = runScenario();
        // Identity hashes render as `@1a2b3c4d`; a moment renders as `@250ms`. If an object ever
        // leaked into a trace via toString(), this is what would catch it.
        for (String line : rendered.split("\n")) {
            assertEquals(false, line.matches(".*@[0-9a-f]{6,}\\b.*"),
                    "trace line looks like it contains an identity hash: " + line);
        }
    }
}
