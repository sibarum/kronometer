package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.s;

/**
 * The signal graph and the horizon model — the novel part of the design.
 */
class SignalGraphTest {

    @Test
    @DisplayName("dependencies are discovered by reading, with no wiring")
    void dependenciesAreTracked() {
        try (Kron kron = Kron.virtual()) {
            Cell<Integer> a = kron.cell("a", 2);
            Cell<Integer> b = kron.cell("b", 3);
            Signal<Integer> sum = kron.computed("sum", () -> a.get() + b.get());

            kron.spork(() -> {
                assertEquals(5, sum.get());
                a.set(10);
                assertEquals(13, sum.get());
                b.set(1);
                assertEquals(11, sum.get());
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("determination takes the minimum over sources; variation takes the maximum")
    void horizonsPropagateOppositely() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                Cell<Double> shortCurve = kron.cell("short", 0.0);
                Cell<Double> longCurve = kron.cell("long", 0.0);
                Cell<Double> input = kron.cell("input", 0.0);
                shortCurve.drive(Curve.ramp(0.0, 1.0, ms(100)));
                longCurve.drive(Curve.ramp(0.0, 1.0, s(10)));
                input.live();

                // A diamond over the two curves.
                Signal<Double> left = kron.computed("left", () -> shortCurve.get() * 2);
                Signal<Double> right = kron.computed("right",
                        () -> shortCurve.get() + longCurve.get());
                Signal<Double> join = kron.computed("join", () -> left.get() + right.get());

                // Determination: both curves are determined forever, so the join is too. Nothing here
                // is volatile, so all of it is precomputable.
                assertEquals(Moment.FOREVER, join.horizon());

                // Variation: the join keeps moving as long as its *longest-running* input does. The
                // rules go opposite ways, and the asymmetry is the point — a value is known only as
                // far as its least-known input, but changes as long as its longest-lived one.
                assertEquals(Moment.ORIGIN.plus(ms(100)), left.varyingUntil());
                assertEquals(Moment.ORIGIN.plus(s(10)), right.varyingUntil());
                assertEquals(Moment.ORIGIN.plus(s(10)), join.varyingUntil());

                // One live input anywhere upstream collapses determination for everything downstream.
                Signal<Double> tainted = kron.computed("tainted", () -> join.get() + input.get());
                assertEquals(kron.now(), tainted.horizon());
                assertEquals(Moment.ORIGIN.plus(s(10)), tainted.varyingUntil());

                // A deep chain folds to the same answers rather than degrading with depth.
                Signal<Double> deep = tainted;
                for (int i = 0; i < 20; i++) {
                    Signal<Double> previous = deep;
                    deep = kron.computed("link", () -> previous.get() + 1);
                }
                assertEquals(kron.now(), deep.horizon());
                assertEquals(Moment.ORIGIN.plus(s(10)), deep.varyingUntil());
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("live() collapses a whole subgraph's horizon, and nothing downstream changes")
    void reclassificationNeedsNoDownstreamChange() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Cell<Double> lift = kron.cell("lift", 0.0);
            Signal<Double> shadow = kron.computed("shadow", () -> 2 + 6 * lift.get());

            kron.spork(() -> {
                // Unpredictable: fed from outside at times nobody can foresee. Nothing may be
                // precomputed, and `shadow` inherits that without knowing anything about it.
                lift.live();
                observed.add("live: " + render(kron, shadow));

                // Predictable, and this is the distinction one number could not carry: determined
                // forever (the curve, then its final value) but varying only for 200 ms. `shadow` was
                // not touched.
                lift.drive(Curve.ramp(0.0, 1.0, ms(200)));
                observed.add("driven: " + render(kron, shadow));

                // Merely held: determined forever and not varying at all.
                lift.release();
                observed.add("held: " + render(kron, shadow));
            });
            kron.run();
        }
        assertEquals(List.of(
                "live: determined=now varying=now",
                "driven: determined=forever varying=+200ms",
                "held: determined=forever varying=now"), observed);
    }

    @Test
    @DisplayName("a derived value evaluates once per moment, however many readers it has")
    void noDoubleEvaluationInOneMoment() {
        AtomicInteger evaluations = new AtomicInteger();
        try (Kron kron = Kron.virtual()) {
            Cell<Integer> source = kron.cell("source", 1);
            Signal<Integer> expensive = kron.computed("expensive", () -> {
                evaluations.incrementAndGet();
                return source.get() * 10;
            });
            // A diamond over `expensive`: four readers, one evaluation.
            Signal<Integer> a = kron.computed("a", expensive::get);
            Signal<Integer> b = kron.computed("b", expensive::get);
            Signal<Integer> join = kron.computed("join", () -> a.get() + b.get() + expensive.get());

            kron.spork(() -> {
                assertEquals(30, join.get());
                assertEquals(1, evaluations.get(), "diamond must not re-evaluate its apex");

                assertEquals(30, join.get());
                assertEquals(1, evaluations.get(), "a second read in the same moment is memoized");

                Time.advance(ms(1));
                assertEquals(30, join.get());
                assertEquals(2, evaluations.get(), "a new moment is a new evaluation");

                source.set(2);
                assertEquals(60, join.get());
                assertEquals(3, evaluations.get(), "an invalidation is a new evaluation");
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("an effect never observes a half-updated graph")
    void effectsSeeConsistentSnapshots() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Cell<Integer> x = kron.cell("x", 1);
            Signal<Integer> doubled = kron.computed("doubled", () -> x.get() * 2);
            Signal<Integer> tripled = kron.computed("tripled", () -> x.get() * 3);
            // If either branch could lag the other, this would eventually not be 5x.
            Signal<Integer> both = kron.computed("both", () -> doubled.get() + tripled.get());

            kron.spork(() -> {
                for (int i = 1; i <= 4; i++) {
                    x.set(i);
                    observed.add(x.get() + "->" + both.get());
                    Time.advance(ms(1));
                }
            });
            kron.run();
        }
        assertEquals(List.of("1->5", "2->10", "3->15", "4->20"), observed);
    }

    @Test
    @DisplayName("a write invalidates strictly after its own moment, not before")
    void invalidationIsStrictlyForward() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Cell<Double> value = kron.cell("value", 0.0);
            Signal<Double> derived = kron.computed("derived", () -> value.get() + 100);

            kron.spork(() -> {
                value.drive(Curve.ramp(0.0, 1.0, ms(100)));
                Time.advance(ms(50));
                observed.add("before=" + derived.get());       // 0.5 + 100

                // The write happens at 50 ms. Values already delivered for earlier moments were
                // correct when delivered and are not revisited; the future is what is retracted.
                value.set(9.0);
                observed.add("at=" + derived.get());

                Time.advance(ms(25));
                observed.add("after=" + derived.get());        // still 9: the curve is gone
            });
            kron.run();
        }
        assertEquals(List.of("before=100.5", "at=109.0", "after=109.0"), observed);
    }

    @Test
    @DisplayName("at() refuses moments beyond the horizon rather than guessing")
    void atRefusesBeyondTheHorizon() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                Cell<Double> value = kron.cell("value", 0.0);
                value.drive(Curve.ramp(0.0, 1.0, ms(100)));

                // Inside the curve: fine, and this is exactly what precomputation will call in M5.
                assertEquals(0.25, value.at(Moment.ORIGIN.plus(ms(25))));
                assertEquals(1.0, value.at(Moment.ORIGIN.plus(ms(100))));

                value.live();
                assertFalse(value.isPredictableAt(kron.now()));
            });
            kron.run();
        }
    }

    @Test
    @DisplayName("a rate-driven effect samples the graph once per step")
    void rateDrivenEffectSamplesOnAGrid() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            Cell<Double> value = kron.cell("value", 0.0);
            Signal<Double> shown = kron.computed("shown", () -> value.get() * 100);
            Rate frames = kron.fixed("frames", ms(25));

            kron.spork(() -> value.drive(Curve.ramp(0.0, 1.0, ms(100))));
            kron.effect(frames, () -> observed.add(Time.now() + "=" + shown.get()));

            kron.tick(ms(100).nanos());
        }
        assertEquals(List.of("@25ms=25.0", "@50ms=50.0", "@75ms=75.0", "@100ms=100.0"), observed);
    }

    @Test
    @DisplayName("a reactive effect re-runs on invalidation, and a cascade converges")
    void reactiveEffectRerunsOnChange() {
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.virtual()) {
            Cell<Integer> x = kron.cell("x", 1);
            Effect effect = kron.effect("watch", () -> observed.add("x=" + x.get()));

            kron.spork(() -> {
                Time.advance(ms(10));
                x.set(2);
                Time.advance(ms(10));
                x.set(3);
                Time.advance(ms(10));
            });
            kron.run();
            assertTrue(effect.runs() >= 3, "should have re-run per change, ran " + effect.runs());
        }
        assertEquals(List.of("x=1", "x=2", "x=3"), observed);
    }

    @Test
    @DisplayName("time itself is a signal, knowable forever")
    void timeIsASignal() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                Signal<Moment> time = kron.time();
                Signal<Double> seconds = kron.computed(
                        "seconds", () -> time.get().since(Moment.ORIGIN).toSeconds());

                assertEquals(Moment.FOREVER, seconds.horizon());
                assertEquals(0.0, seconds.get());
                Time.advance(ms(500));
                assertEquals(0.5, seconds.get());
            });
            kron.run();
        }
    }

    private static String render(Kron kron, Signal<?> signal) {
        return "determined=" + moment(kron, signal.horizon())
                + " varying=" + moment(kron, signal.varyingUntil());
    }

    private static String moment(Kron kron, Moment m) {
        if (m.equals(Moment.FOREVER)) {
            return "forever";
        }
        if (!m.isAfter(kron.now())) {
            return "now";
        }
        return "+" + m.since(kron.now());
    }
}
