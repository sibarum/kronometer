package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sibarum.kronometer.Dur.ms;

/**
 * Blending between the last two steps of a domain whose tempo is not 1:1.
 *
 * <p>A step's {@code dt} is its period in the domain's <em>local</em> time, but a blend is read at global
 * moments, so the span it blends over must be the global distance to the next grid line. In a 1:4 tempo a
 * 20 ms step is 80 ms of wall; blending over 20 ms moves for a quarter of each step and then freezes, which is
 * slow motion that stutters, the canonical case that tempo exists for.
 */
class TempoBlendTest {

    private static Moment at(Dur d) {
        return Moment.ORIGIN.plus(d);
    }

    @Test
    @DisplayName("a handoff's phase spans the wall time between steps in a slow tempo")
    void handoffPhaseInASlowTempo() {
        try (Kron kron = Kron.driven()) {
            Tempo slow = kron.tempo().child(Ratio.of(1, 4));
            Handoff steps = slow.fixed("physics", ms(20)).handoff();

            kron.tick(ms(80).nanos());                       // the first 20 ms step, 80 ms of wall
            steps.drain(step -> { });
            kron.tick(ms(120).nanos());
            assertEquals(0.5f, steps.phase(kron.now()), 1e-6f, "40 ms into an 80 ms gap is halfway");
        }
    }

    @Test
    @DisplayName("a sampled value blends across the wall time between steps in a slow tempo")
    void sampledInASlowTempo() {
        AtomicReference<Double> shown = new AtomicReference<>();
        try (Kron kron = Kron.virtual()) {
            Tempo slow = kron.tempo().child(Ratio.of(1, 4));
            Rate physics = slow.fixed("physics", ms(20));
            Sampled<Double> x = physics.sample(() -> slow.elapsed().toMillis(), Interp.DOUBLE);
            physics.each(step -> { });

            kron.spork(() -> {
                Time.advance(ms(200));                       // steps at 80 and 160 committed 20 and 40
                shown.set(x.at(Time.now()));
            });
            kron.runUntil(at(ms(200)));
        }
        assertEquals(30.0, shown.get(), 1e-9, "halfway from 160 to the next line at 240: halfway from 20 to 40");
    }

    @Test
    @DisplayName("in a gliding tempo, a handoff's phase reaches exactly 1 at the next grid line")
    void handoffPhaseInAGlidingTempo() {
        try (Kron kron = Kron.driven()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Rate physics = tempo.fixed("physics", ms(20));
            Handoff steps = physics.handoff();
            kron.post(kron.now(), () -> tempo.glide(Ratio.of(1, 3), ms(500), Glide.EXPONENTIAL));

            Step[] last = new Step[1];
            kron.tick(ms(300).nanos());
            steps.drain(step -> last[0] = step);

            Moment from = last[0].at();
            Moment next = physics.gridLineAt(last[0].index() + 1);
            Moment midway = from.plus(new Dur(next.since(from).nanos() / 2));
            assertEquals(0.5f, steps.phase(midway), 1e-6f, "halfway along this step's own wall span");
            assertEquals(1f, steps.phase(next), 1e-6f, "and all the way at the next line");
        }
    }

    @Test
    @DisplayName("a domain waiting for its next grid line follows a rescale: later when slowed, earlier when sped up")
    void aWaitingDomainFollowsARescale() {
        assertEquals(List.of("1@50ms local 20ms"), stepsAfterRescale(Ratio.of(1, 4)),
                "slowed at 10 ms: local 20 ms is 10 + 4 × 10 = 50 ms, not the 20 ms worked out before");
        List<String> fast = stepsAfterRescale(Ratio.of(4, 1));
        assertEquals("1@12.5ms local 20ms", fast.get(0), "sped up at 10 ms: local 20 ms is at 12.5 ms");
        assertEquals("2@17.5ms local 40ms", fast.get(1), "and no step is replayed as catch-up");
    }

    private static List<String> stepsAfterRescale(Ratio to) {
        List<String> seen = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Rate physics = tempo.fixed("physics", ms(20));
            physics.each(step -> seen.add(step.index() + "@" + step.at().since(Moment.ORIGIN)
                    + " local " + tempo.elapsed()));
            kron.spork(() -> {
                Time.advance(ms(10));
                tempo.rescale(to);
            });
            kron.runUntil(at(ms(100)));
        }
        return seen;
    }

    @Test
    @DisplayName("a dilated world in a gliding tempo: due on every grid line, game time in local steps, full speed")
    void dilatedInAGlidingTempo() {
        try (Kron kron = Kron.driven()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Rate physics = tempo.fixed("physics", ms(20));
            Dilated world = physics.dilated(2);
            kron.post(kron.now(), () -> tempo.glide(Ratio.of(1, 4), ms(400), Glide.EXPONENTIAL));

            for (int t = 5; t <= 1_000; t += 5) {
                kron.tick(ms(t).nanos());
                while (world.take()) {
                    world.finished(ms(t).nanos());
                }
                assertEquals(physics.gridIndexAtOrBefore(kron.now()), world.due(),
                        "one step due per grid line passed, at " + t + " ms");
            }
            assertEquals(ms(20).times(world.finished()), world.time(), "game time is local steps");
            assertEquals(0, world.forgiven());
            assertEquals(1.0, world.dilation(), 1e-9, "keeping up with a slowing grid is full speed");
        }
    }
}
