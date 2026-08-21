package sibarum.kronometer.demo;

import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Predict;
import sibarum.kronometer.Prediction;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Ratio;
import sibarum.kronometer.Signal;
import sibarum.kronometer.Tempo;
import sibarum.kronometer.Time;
import sibarum.kronometer.anim.Animator;
import sibarum.kronometer.anim.Ease;
import sibarum.kronometer.anim.Smooth;
import sibarum.kronometer.anim.Turn;
import sibarum.kronometer.atchung.KronBridge;

import java.util.ArrayList;
import java.util.List;

import static sibarum.kronometer.Dur.hz;
import static sibarum.kronometer.Dur.ms;

/**
 * Everything at once, headless.
 *
 * <p>One scenario exercising every milestone: a nested tempo running the simulation at a third speed,
 * three rate domains at different rates, live input arriving from an Atchung bus, an animation that is
 * precomputed because it is a curve and an integrated smoother that is not because it chases that input,
 * and a retrigger halfway through to prove the animation continues rather than snapping.
 *
 * <p>Headless on purpose. The interesting output of a timing framework is a <b>schedule</b>, and a
 * schedule is text — so this writes a table you can read and diff, and the reactor never grows a GUI
 * dependency to look at it. Run it under the virtual clock and the table is identical every time, which
 * is the whole point of §5.3.
 *
 * <pre>{@code
 * mvn -q -pl kronometer-demo -am exec:java -Dexec.mainClass=sibarum.kronometer.demo.Demo
 * }</pre>
 */
public final class Demo {

    /** Pointer pressure, arriving from outside on whatever thread published it. */
    private static final Topic<Double> PRESSURE = Topic.of("pressure", Double.class);

    record Row(Moment at, double simX, double shown, double smoothed, double pressure, Turn spin) { }

    private final List<Row> rows = new ArrayList<>();
    private Prediction<Double> animationPrediction;
    private Prediction<Turn> spinPrediction;

    public static void main(String[] args) {
        Demo demo = new Demo();
        demo.run();
        demo.report();
    }

    private void run() {
        Atchung bus = Atchung.create();

        try (Kron kron = Kron.virtual();
             KronBridge bridge = new KronBridge(kron, bus)) {

            // --- time ---------------------------------------------------------
            // The world runs at a third speed. Nothing below mentions slow motion again: a 3:1 ratio is
            // exact, so the simulation's grid lines land on exact nanoseconds however deep it nests.
            Tempo world = kron.tempo().child("world", Ratio.of(1, 3));

            // --- domains ------------------------------------------------------
            Rate physics = world.fixed("physics", hz(50)).priority(0).maxCatchUp(4);
            Rate frames = kron.fixed("frames", hz(60)).priority(1).lookahead(ms(200));
            Rate control = kron.fixed("control", ms(100)).priority(2);

            // --- the graph ----------------------------------------------------
            Cell<Double> pressure = bridge.cell(PRESSURE, 0.0);          // live: horizon == now
            Cell<Double> lift = kron.cell("lift", 0.0);                  // animated: predictable
            Cell<Turn> spin = kron.cell("spin", world, Turn.ZERO);       // animated in the slow tempo

            // Predictable: derived only from a curve, so the whole window is computed ahead.
            Signal<Double> shown = kron.computed("shown", () -> 2 + 6 * lift.get());
            // Not predictable: integrated, and chasing an unpredictable input. Both facts are inferred.
            Signal<Double> smoothed = Smooth.chase(kron, physics, pressure, ms(80));

            animationPrediction = frames.predict(shown, Predict.EAGER);
            spinPrediction = frames.predict(spin, Predict.EAGER);

            // --- wiring -------------------------------------------------------
            // The bridge registers first, so every effect below sees *this* step's input rather than
            // the previous step's. Registration order is the whole contract (M7).
            bridge.drainOn(frames);

            double[] simX = {0};
            physics.each(step -> simX[0] += step.dt().toSeconds() * 10);

            kron.effect(frames, () -> rows.add(new Row(
                    Time.now(), round(simX[0]), round(shown.get()), round(smoothed.get()),
                    round(pressure.get()), spin.get())));

            // --- the script ---------------------------------------------------
            Animator animator = new Animator(kron);
            kron.spork("interaction", () -> {
                animator.retarget(lift, 1.0, ms(300), Ease.OUT_CUBIC);
                spin.drive(sibarum.kronometer.anim.Tween.curve(
                        Turn.of(0.9), Turn.of(0.1), ms(200), Ease.IN_OUT_SINE, Turn.SHORTEST));

                Time.advance(ms(120));
                // Interrupt at 40 %. The reversal starts from wherever the value actually is, and stays
                // precomputable, because it is a fresh curve rather than a cancelled thread.
                animator.retarget(lift, 0.0, ms(200), Ease.OUT_QUAD);
            });

            // Input arrivals, scripted so the run is reproducible. The virtual clock insists.
            bridge.inject(Moment.ORIGIN.plus(ms(50)), PRESSURE, 0.8);
            bridge.inject(Moment.ORIGIN.plus(ms(230)), PRESSURE, 0.2);

            control.each(step -> { });          // a third domain, just to crowd the same moments

            kron.runUntil(Moment.ORIGIN.plus(ms(400)));
        }
    }

    private void report() {
        System.out.printf("Kronometer demo — one scenario, every milestone%n%n");
        System.out.printf("  %-9s %8s %8s %10s %10s %9s%n",
                "moment", "sim.x", "shown", "smoothed", "pressure", "spin");
        System.out.printf("  %s%n", "-".repeat(60));
        for (Row row : rows) {
            System.out.printf("  %-9s %8.2f %8.2f %10.3f %10.2f %9.3f%n",
                    row.at().since(Moment.ORIGIN), row.simX(), row.shown(),
                    row.smoothed(), row.pressure(), row.spin().wrapped().turns());
        }

        System.out.printf("%n  frames: %d%n", rows.size());
        System.out.printf("  animation prediction: %d filled, %d served from buffer, %.0f%% wasted%n",
                animationPrediction.filled(), animationPrediction.hits(),
                animationPrediction.waste() * 100);
        System.out.printf("  spin prediction:      %d filled, %d served from buffer%n",
                spinPrediction.filled(), spinPrediction.hits());
        System.out.printf("%n  Read it: `sim.x` advances at a third of wall speed — the tempo, not the%n"
                + "  code. `shown` is precomputed because it descends from a curve; `smoothed` is not,%n"
                + "  because it integrates an input nobody can foresee. Neither was told which.%n");
    }

    /** The table is a diffable artifact, so round rather than printing float noise. */
    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    List<Row> rows() {
        return List.copyOf(rows);
    }

    Prediction<Double> animationPrediction() {
        return animationPrediction;
    }

    Prediction<Turn> spinPrediction() {
        return spinPrediction;
    }

    static Demo runScenario() {
        Demo demo = new Demo();
        demo.run();
        return demo;
    }
}
