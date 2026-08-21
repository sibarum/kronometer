package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.s;

/**
 * Nested, localized, scalable time — with real time never bent.
 */
class TempoTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("a declared rate becomes a slower actual rate inside a slower tempo")
    void scaleAdjustsTheActualRate() {
        try (Kron kron = Kron.virtual()) {
            Tempo bullet = kron.tempo().child(Ratio.of(1, 4));
            bullet.fixed("physics", ms(20)).each(step -> log.add(step.at().toString()));

            kron.runUntil(Moment.ORIGIN.plus(ms(400)));
        }
        // 20 ms of *local* time is 80 ms of global time at quarter speed. The domain still believes it
        // is running at 50 Hz; against the wall it delivers 12.5 Hz.
        assertEquals(List.of("@80ms", "@160ms", "@240ms", "@320ms", "@400ms"), log);
    }

    @Test
    @DisplayName("scales compose down the tree, exactly")
    void scalesCompose() {
        try (Kron kron = Kron.virtual()) {
            Tempo half = kron.tempo().child(Ratio.HALF);
            Tempo halfOfHalf = half.child(Ratio.HALF);
            Tempo backToUnity = half.child(Ratio.DOUBLE);

            assertEquals(Ratio.HALF, half.effectiveScale());
            assertEquals(Ratio.of(1, 4), halfOfHalf.effectiveScale());
            assertEquals(Ratio.ONE, backToUnity.effectiveScale());

            // And a musical chain, which is the case exactness exists for.
            Tempo musical = kron.tempo()
                    .child(Ratio.of(3, 2))
                    .child(Ratio.of(4, 3))
                    .child(Ratio.of(7, 4));
            assertEquals(Ratio.of(7, 2), musical.effectiveScale());
        }
    }

    @Test
    @DisplayName("sibling tempos on commensurate ratios coincide exactly at their common multiple")
    void siblingGridsCoincideExactly() {
        List<Moment> three = new ArrayList<>();
        List<Moment> four = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Tempo a = kron.tempo().child("3:2", Ratio.of(3, 2));
            Tempo b = kron.tempo().child("4:3", Ratio.of(4, 3));

            a.fixed("a", ms(12)).priority(0).each(step -> three.add(step.at()));
            b.fixed("b", ms(12)).priority(1).each(step -> four.add(step.at()));

            kron.runUntil(Moment.ORIGIN.plus(ms(200)));
        }
        // a's lines land every 8 ms of global time (12 ÷ 3/2), b's every 9 ms (12 ÷ 4/3). They meet at
        // 72 ms — exactly, on the nanosecond, which is the whole reason the scale is a Ratio. With
        // doubles this assertion would hold for a while and then quietly stop.
        Moment meeting = Moment.ORIGIN.plus(ms(72));
        assertTrue(three.contains(meeting), "3:2 grid missed the common multiple: " + three);
        assertTrue(four.contains(meeting), "4:3 grid missed the common multiple: " + four);
        assertEquals(Moment.ORIGIN.plus(ms(144)), three.get(17));
        assertEquals(Moment.ORIGIN.plus(ms(144)), four.get(15));
    }

    @Test
    @DisplayName("a curve driven in a slow tempo stretches with it, without being told")
    void curvesStretchWithTheirTempo() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Tempo slow = kron.tempo().child(Ratio.HALF);
            Cell<Double> value = kron.cell("value", slow, 0.0);

            kron.spork(() -> {
                value.drive(Curve.ramp(0.0, 1.0, ms(200)));
                Metro metro = Metro.of(ms(100));
                for (int i = 0; i < 5; i++) {
                    metro.tick();
                    observed.add(Time.now() + "=" + value.get());
                }
            });
            kron.run();
        }
        // A 200 ms ramp at half speed takes 400 ms of wall time, so it is only half done at 200 ms.
        // Nothing in the animation said anything about slow motion.
        assertEquals(List.of(
                "@100ms=0.25", "@200ms=0.5", "@300ms=0.75", "@400ms=1.0", "@500ms=1.0"), observed);
    }

    @Test
    @DisplayName("local time is continuous across a rescale — nothing jumps")
    void rescaleIsContinuous() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            kron.spork(() -> {
                Metro metro = Metro.of(ms(100));
                for (int i = 0; i < 5; i++) {
                    metro.tick();
                    observed.add(Time.now() + " local=" + tempo.elapsed());
                    if (i == 1) {
                        tempo.rescale(Ratio.HALF);
                    }
                }
            });
            kron.run();
        }
        // Local time reads 200 ms at the moment of the change and keeps going from there at half rate;
        // it does not restart, and it does not skip.
        assertEquals(List.of(
                "@100ms local=100ms",
                "@200ms local=200ms",
                "@300ms local=250ms",
                "@400ms local=300ms",
                "@500ms local=350ms"), observed);
    }

    @Test
    @DisplayName("moments are global, so they stay comparable across tempos")
    void momentsAreGlobal() {
        try (Kron kron = Kron.virtual()) {
            Tempo fast = kron.tempo().child(Ratio.DOUBLE);
            Tempo slow = kron.tempo().child(Ratio.HALF);

            kron.spork(() -> {
                Time.advance(ms(100));
                // Local elapsed differs per tempo, as it should…
                assertEquals(ms(200), fast.elapsed());
                assertEquals(ms(50), slow.elapsed());
                // …but `now` is one number both agree on, which is what makes cross-tempo scheduling
                // and interpolation well defined at all.
                assertEquals(Moment.ORIGIN.plus(ms(100)), Time.now());
                assertEquals(ms(100), fast.globalAt(ms(200)).since(Moment.ORIGIN));
                assertEquals(ms(100), slow.globalAt(ms(50)).since(Moment.ORIGIN));
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("a dynamic domain is wall-locked, so slow motion does not slow the frame rate")
    void dynamicDomainsIgnoreTempo() {
        List<String> frames = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Tempo bullet = kron.tempo().child(Ratio.of(1, 4));
            bullet.fixed("sim", ms(20)).priority(0).each(step -> log.add("sim@" + step.at()));
            kron.dynamic("frames").priority(1).each(step -> frames.add("frame@" + step.at()));

            for (int t = 40; t <= 160; t += 40) {
                kron.tick(ms(t).nanos());
            }
        }
        // Four ticks, four frames — the picture keeps its rate. The simulation inside the slow tempo
        // steps half as often over the same span. That is slow motion.
        assertEquals(List.of("frame@@40ms", "frame@@80ms", "frame@@120ms", "frame@@160ms"), frames);
        assertEquals(List.of("sim@@80ms", "sim@@160ms"), log);
    }

    @Test
    @DisplayName("a rescale retracts what was predicted after it")
    void rescaleInvalidates() {
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(Ratio.ONE);
            Cell<Double> value = kron.cell("value", tempo, 0.0);
            Signal<Double> doubled = kron.computed(() -> value.get() * 2);

            kron.spork(() -> {
                value.drive(Curve.ramp(0.0, 1.0, ms(100)));
                Time.advance(ms(50));
                assertEquals(1.0, doubled.get());                 // halfway: 0.5 × 2

                tempo.rescale(Ratio.HALF);                        // from here, half speed
                assertEquals(1.0, doubled.get());                 // continuous at the moment of change

                Time.advance(ms(50));
                assertEquals(1.5, doubled.get());                 // 25 ms of local progress, not 50
            });
            kron.run();
        }
    }
}
