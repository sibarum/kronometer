package sibarum.kronometer.anim;

import sibarum.kronometer.Cell;
import sibarum.kronometer.Curve;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Effect;
import sibarum.kronometer.Interp;
import sibarum.kronometer.Kron;
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
        run(extent, periodOf(domain), ease, sink);
    }

    /**
     * Play a tween with both endpoints delivered where a consumer can act on them, and a completion
     * that outlives the value.
     *
     * <p>{@link #run} is ninety per cent of this, and the missing ten per cent is invisible to a test
     * that checks only that the endpoints were reached — which is why it was found by running an
     * application rather than by a suite. Two differences, neither of them about graphics:
     *
     * <h2>Zero is delivered before any time passes</h2>
     *
     * {@code run} advances a step before its first sample, so a four-frame ramp starts the consumer at
     * {@code 0.25}. Anything that treats its first sample as "this is where the motion begins" therefore
     * begins by jumping to wherever that landed. {@code Tween} already makes the argument for the other
     * endpoint — an animation that stops one sample short is a bug you find later — and the sentence is
     * just as true at the start. Here {@code progress} sees exactly {@code 0} in the calling segment,
     * before a single step has elapsed.
     *
     * <h2>Settled comes one step after the one that carried one</h2>
     *
     * {@code run} returns in the same moment as {@code sink.at(1f)}, so a consumer that tears down on
     * arrival — hides a node, drops an overlay, releases a buffer, closes a voice — does it in the same
     * batch as the final sample. The end value is written and then overwritten before anything presents
     * it, and the last state anyone saw is the second-to-last sample. At 60 fps over 200 ms that is a
     * fifth of the animation still showing at the instant it disappears.
     *
     * <p>The general rule, which is not a rendering concern: <b>a value's consumer must have had a
     * chance to commit it before being told the motion is over.</b> It applies to anything with a
     * present, flush or commit boundary. So {@code settled} runs one step after the step that carried
     * {@code 1} — on the metro's own grid, so the extra step is drift-free like every other.
     *
     * @param settled run one step after the final sample; may be {@code null} for none
     */
    public static void ramp(Dur extent, Dur step, Ease ease, Sink progress, Runnable settled) {
        Objects.requireNonNull(ease, "ease");
        Objects.requireNonNull(progress, "progress");
        if (extent.nanos() <= 0 || step.nanos() <= 0) {
            throw new IllegalArgumentException("extent and step must be positive");
        }
        Moment start = Time.now();
        Moment end = start.plus(extent);
        Metro metro = Metro.of(step);

        // Exactly 0, not ease.at(0) — the two are equal by Ease's endpoint contract, and saying so
        // literally is what makes the guarantee independent of the ease that was passed.
        progress.at(0f);

        while (true) {
            metro.tick();
            Moment now = Time.now();
            if (!now.isBefore(end)) {
                break;
            }
            float alpha = (float) (now.since(start).nanos() / (double) extent.nanos());
            progress.at(ease.at(Math.clamp(alpha, 0f, 1f)));
        }
        progress.at(1f);

        if (settled != null) {
            metro.tick();
            settled.run();
        }
    }

    /**
     * The same ramp, sampled once per step of {@code domain}, returning immediately.
     *
     * <p>The form a frame loop wants, and the one {@link #ramp} cannot be: {@code ramp} blocks the
     * calling shred and samples on a {@link Metro}, which is a grid of its own making. A display's
     * sample points are whatever the display decides, so a metro-driven tween on a frame clock samples
     * on a grid that has nothing to do with the frames it is animating. This drives a {@link Cell} with
     * the curve and reads it from the domain instead — which also means the whole visible future of the
     * ramp is precomputable, off the frame thread, for free.
     *
     * <p>Same two endpoint guarantees as {@link #ramp}: {@code progress} sees exactly {@code 0} before a
     * single step has elapsed, exactly {@code 1} on the first step at or after the end, and
     * {@code settled} runs one step later so the consumer has had a chance to commit the {@code 1}.
     *
     * <h2>The seam this exists for</h2>
     *
     * {@code (Sink, Runnable)} is deliberately the whole signature. A widget that only wants
     * <em>timing</em> should not have to hand a clock its widgets to get it, so a widget can declare a
     * seam of this shape — in JDK types, naming Kronometer nowhere — and be satisfied by this method
     * without either side depending on the other.
     *
     * <p>The ramp reads {@link Kron#time()} while it is in flight, which is what stops a
     * render-on-demand loop going to sleep between the final sample and the settle. It stops when the
     * ramp does: the returned effect is cancelled after {@code settled} runs, and cancelling it detaches
     * this ramp's handler alone.
     *
     * <p>Safe from a handler thread — it crosses the baton itself. That is also why it does not return
     * the {@link Effect}: from off the timeline there is not one yet.
     */
    public static void rampOn(sibarum.kronometer.Rate domain, Dur extent, Ease ease, Sink progress,
                              Runnable settled) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(ease, "ease");
        Objects.requireNonNull(progress, "progress");
        if (extent.nanos() <= 0) {
            throw new IllegalArgumentException("extent must be positive: " + extent);
        }
        Kron kron = domain.kron();
        kron.onTimeline(() -> begin(kron, domain, extent, ease, progress, settled));
    }

    private static void begin(Kron kron, sibarum.kronometer.Rate domain, Dur extent, Ease ease,
                              Sink progress, Runnable settled) {
        Cell<Float> value = kron.cell("ramp", 0f);
        value.drive(curve(0f, 1f, extent, ease, Interp.FLOAT));
        Moment end = kron.now().plus(extent);

        // Exactly 0, before a single step has elapsed. A consumer that treats its first sample as
        // "this is where the motion begins" is then right, rather than beginning with a jump.
        progress.at(0f);

        Effect[] sampler = new Effect[1];
        boolean[] carriedOne = {false};
        sampler[0] = kron.effect(domain, () -> {
            // Read time so the ramp counts as varying: a host parking on Kron.nextDeadline() must not
            // sleep between the final sample and the settle, which would strand the teardown forever.
            kron.time().get();
            if (carriedOne[0]) {
                sampler[0].cancel();
                if (settled != null) {
                    settled.run();
                }
                return;
            }
            // The cell clamps past the end of its curve, so this is exactly 1 from the first step at or
            // after the end — no sample lands short of the target, whatever the step divides into.
            progress.at(value.get());
            carriedOne[0] = !kron.now().isBefore(end);
        });
    }

    /**
     * A domain's period, with the reason a dynamic one has none.
     *
     * <p>A metro-driven tween samples on a grid of its own making. A dynamic domain's sample points are
     * whatever its driver decides, so there is no period to borrow and a tween that took one would be
     * sampling on a grid that has nothing to do with the frames it is animating. Drive a {@code Cell}
     * with {@link #curve} and read it from the domain's handler instead.
     */
    private static Dur periodOf(sibarum.kronometer.Rate domain) {
        Objects.requireNonNull(domain, "domain");
        Dur period = domain.period();
        if (period == null) {
            throw new IllegalStateException(
                    "a " + domain.kind() + " domain has no period to sample on: " + domain
                            + "; drive a Cell with Tween.curve and read it from the domain's handler");
        }
        return period;
    }
}
