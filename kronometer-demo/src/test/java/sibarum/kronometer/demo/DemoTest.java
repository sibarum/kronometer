package sibarum.kronometer.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo is the integration test, which is why it is headless: a schedule is text, and text can be
 * asserted on.
 */
class DemoTest {

    @Test
    @DisplayName("the whole scenario is reproducible, run after run")
    void demoIsDeterministic() {
        String reference = render(Demo.runScenario());
        for (int run = 1; run < 50; run++) {
            assertEquals(reference, render(Demo.runScenario()), "run " + run + " diverged");
        }
    }

    @Test
    @DisplayName("the nested tempo slows the simulation without slowing the frames")
    void tempoSlowsOnlyWhatItContains() {
        Demo demo = Demo.runScenario();
        List<Demo.Row> rows = demo.rows();

        // 60 Hz of frames over 400 ms is 23 of them: the picture keeps its rate.
        assertEquals(23, rows.size());

        // The simulation is a 50 Hz grid inside a 1:3 tempo, so it steps every 60 ms of wall time and
        // advances 0.2 per step. Over 400 ms that is a little over 1.2, not the 4.0 it would reach at
        // full speed — the tempo, not the code.
        double finalX = rows.get(rows.size() - 1).simX();
        assertTrue(finalX > 1.0 && finalX < 1.5, "simulation should run at a third speed, got " + finalX);
    }

    @Test
    @DisplayName("the retrigger is continuous — no snap back to the start")
    void retriggerIsContinuous() {
        List<Demo.Row> rows = Demo.runScenario().rows();

        double peak = rows.stream().mapToDouble(Demo.Row::shown).max().orElseThrow();
        // The animation is interrupted at 40 % of a 300 ms rise, so it never reaches the 8.0 it was
        // heading for. If a retrigger snapped to the target and ran back, the peak would be 8.
        assertTrue(peak < 7.5, "should have reversed before arriving, peaked at " + peak);
        assertTrue(peak > 6.0, "should have got most of the way, peaked at " + peak);

        // And it comes to rest exactly on its target, rather than a thousandth short.
        assertEquals(2.0, rows.get(rows.size() - 1).shown(), 1e-9);
    }

    @Test
    @DisplayName("the curve is precomputed and the integrated smoother is not")
    void theGraphClassifiesItself() {
        Demo demo = Demo.runScenario();

        // Nobody told either of these which world it lives in.
        assertTrue(demo.animationPrediction().filled() > 0,
                "an animation descends from a curve, so it is predictable");
        assertTrue(demo.animationPrediction().hits() > 15,
                "and most frames should be served from the buffer, got "
                        + demo.animationPrediction().hits());
        assertTrue(demo.spinPrediction().filled() > 0, "so is a tween in a slow tempo");

        // The waste here is the genuine retrigger, not the unrelated input arriving every frame — which
        // is what targeted invalidation bought (72 % waste before it, 34 % after).
        assertTrue(demo.animationPrediction().waste() < 0.5,
                "waste should reflect real invalidations only, was "
                        + demo.animationPrediction().waste());
    }

    private static String render(Demo demo) {
        StringBuilder sb = new StringBuilder();
        for (Demo.Row row : demo.rows()) {
            sb.append(row.at()).append(' ').append(row.simX()).append(' ').append(row.shown())
                    .append(' ').append(row.smoothed()).append(' ').append(row.pressure())
                    .append(' ').append(row.spin().wrapped().turns()).append('\n');
        }
        return sb.toString();
    }
}
