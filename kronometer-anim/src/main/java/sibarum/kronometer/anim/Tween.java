package sibarum.kronometer.anim;

import sibarum.kronometer.Curve;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Interp;
import sibarum.kronometer.Metro;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Time;

import java.util.Objects;

/**
 * A shaped interpolation from one value to another, in two forms.
 *
 * <p>The two forms are not redundant, and picking between them is the main decision this class asks of
 * you:
 *
 * <ul>
 *   <li>{@link #curve} builds a {@link Curve}, which is a <b>pure function of time</b> and therefore
 *       precomputable. Drive a {@code Cell} with it and the whole animation can be evaluated ahead, off
 *       the timeline, on a pool. This is the form to reach for.</li>
 *   <li>{@link #run} plays a tween <b>procedurally</b>, in the calling shred, returning when it is done.
 *       It reads beautifully — straight-line code with a real call stack across time — and it costs a
 *       baton handoff per sample, so it cannot be precomputed. Use it when the sequencing genuinely is
 *       the logic.</li>
 * </ul>
 */
public final class Tween {

    /** Where a procedural tween delivers its shaped progress. */
    @FunctionalInterface
    public interface Sink {
        void at(float alpha);
    }

    private Tween() {
    }

    /**
     * A pure curve from {@code from} to {@code to} over {@code extent}, shaped by {@code ease}.
     *
     * <p>Anchored when it is handed to {@code Cell.drive}, and measured in that cell's tempo — so the
     * same 200 ms tween takes 800 ms of wall time inside a 1:4 region, with nothing here mentioning it.
     */
    public static <T> Curve<T> curve(T from, T to, Dur extent, Ease ease, Interp<T> interp) {
        Objects.requireNonNull(ease, "ease");
        Objects.requireNonNull(interp, "interp");
        Curve<T> ramp = Curve.ramp(from, to, extent, interp);
        return new Curve<>() {
            @Override
            public Dur extent() {
                return extent;
            }

            @Override
            public T at(Dur elapsed) {
                float alpha = (float) (elapsed.nanos() / (double) extent.nanos());
                float shaped = ease.at(Math.clamp(alpha, 0f, 1f));
                return interp.between(from, to, shaped);
            }

            @Override
            public String toString() {
                return "tween(" + from + " -> " + to + " over " + extent + ")";
            }
        };
    }

    public static Curve<Double> curve(double from, double to, Dur extent, Ease ease) {
        return curve(from, to, extent, ease, Interp.DOUBLE);
    }

    /**
     * Play a tween in the calling shred, sampling every {@code step} of local time, and return when it
     * is finished.
     *
     * <p>The sink is guaranteed to see exactly {@code 1.0f} on its final call, whatever the step size
     * divides into — an animation that stops one sample short of its target is a bug you find later, in
     * the form of a shadow that never quite settles.
     */
    public static void run(Dur extent, Dur step, Ease ease, Sink sink) {
        Objects.requireNonNull(ease, "ease");
        Objects.requireNonNull(sink, "sink");
        if (extent.nanos() <= 0 || step.nanos() <= 0) {
            throw new IllegalArgumentException("extent and step must be positive");
        }
        Moment start = Time.now();
        Moment end = start.plus(extent);
        Metro metro = Metro.of(step);

        while (true) {
            metro.tick();
            Moment now = Time.now();
            if (!now.isBefore(end)) {
                break;
            }
            float alpha = (float) (now.since(start).nanos() / (double) extent.nanos());
            sink.at(ease.at(Math.clamp(alpha, 0f, 1f)));
        }
        sink.at(1f);
    }

    /** Play a tween sampling on a rate domain's period. */
    public static void run(sibarum.kronometer.Rate domain, Dur extent, Ease ease, Sink sink) {
        run(extent, domain.period(), ease, sink);
    }
}
