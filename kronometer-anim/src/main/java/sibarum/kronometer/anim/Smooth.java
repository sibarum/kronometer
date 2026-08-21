package sibarum.kronometer.anim;

import sibarum.kronometer.Cell;
import sibarum.kronometer.Curve;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Signal;

import java.util.Objects;

/**
 * The two families of smoothing, and the distinction that has teeth.
 *
 * <table border="1">
 * <caption>Closed-form against integrated</caption>
 * <tr><th></th><th>Closed-form</th><th>Integrated</th></tr>
 * <tr><td>Shape</td><td>{@code value = f(t)}</td><td>{@code value = f(previous, dt)}</td></tr>
 * <tr><td>Example</td><td>{@link #settle} — a damped approach to a <em>fixed</em> target</td>
 *     <td>{@link #chase} — a pursuit of a <em>live</em> one</td></tr>
 * <tr><td>Horizon</td><td>the end of the motion: fully predictable</td><td>{@code now}: not predictable at all</td></tr>
 * <tr><td>Precomputed</td><td>yes, usually in one shot</td><td>never</td></tr>
 * <tr><td>Needs</td><td>nothing</td><td>a <b>fixed-rate</b> domain</td></tr>
 * </table>
 *
 * <p>That last row is enforced rather than advised. An integrated smoother stepped at a varying
 * {@code dt} is framerate-dependent — the classic bug where a UI feels different at 60 Hz and at 144 Hz,
 * because {@code value += (target − value) × k} applied twice as often converges twice as fast. So
 * {@link #chase} <b>refuses a dynamic domain at construction</b>. Put it on a fixed grid and let the
 * graphics domain interpolate the result (architecture §6.2); a compile-time-ish error beats a feel bug
 * nobody can reproduce.
 */
public final class Smooth {

    private Smooth() {
    }

    /**
     * A critically damped approach from {@code from} to {@code to}, as a pure curve.
     *
     * <p>Closed-form, because the target is fixed: {@code 1 − (1 + kt)e^{−kt}} with {@code k} chosen so
     * the motion is within a thousandth of its target at {@code settle}. Reaches the target exactly at
     * the end rather than asymptotically, which is what makes it a {@link Curve} with a real extent and
     * therefore something the precompute pool can evaluate ahead in one batch.
     */
    public static Curve<Double> settle(double from, double to, Dur settle) {
        if (settle.nanos() <= 0) {
            throw new IllegalArgumentException("settle time must be positive: " + settle);
        }
        // (1 + k)e^-k = 0.001 at k ≈ 9.23: the critically damped step response's 0.1 % point.
        double k = 9.23;
        return Curve.of(settle, elapsed -> {
            double t = elapsed.nanos() / (double) settle.nanos();
            if (t >= 1) {
                return to;
            }
            double kt = k * t;
            double remaining = (1 + kt) * Math.exp(-kt);
            // Rescale so the tail lands exactly on the target instead of a thousandth short.
            double tail = (1 + k) * Math.exp(-k);
            double progress = (1 - remaining) / (1 - tail);
            return from + (to - from) * progress;
        });
    }

    /**
     * A first-order pursuit of a live target, integrated on a fixed grid.
     *
     * @param domain       must be {@linkplain Rate.Kind#FIXED fixed}; a varying {@code dt} makes the
     *                     result framerate-dependent, so a dynamic domain is refused
     * @param timeConstant how long to close roughly 63 % of the remaining distance
     * @return a signal declared {@linkplain Cell#live() volatile} — it depends on its own past and on an
     *         unpredictable input, so none of its future is knowable and nothing downstream of it will be
     *         precomputed. That is the honest classification, not a limitation
     */
    public static Signal<Double> chase(
            Kron kron, Rate domain, Signal<Double> target, Dur timeConstant) {

        Objects.requireNonNull(kron, "kron");
        Objects.requireNonNull(target, "target");
        if (domain.kind() != Rate.Kind.FIXED) {
            throw new IllegalStateException(
                    "an integrated smoother needs a fixed dt to be reproducible, but " + domain.name()
                            + " is " + domain.kind() + "; put it on a fixed domain and let the dynamic "
                            + "one interpolate the result");
        }
        if (timeConstant.nanos() <= 0) {
            throw new IllegalArgumentException("time constant must be positive: " + timeConstant);
        }

        Cell<Double> value = kron.cell("chase", 0.0);
        double dt = domain.period().nanos() / (double) timeConstant.nanos();
        double alpha = 1 - Math.exp(-dt);           // constant, because dt is constant. That is the point.

        kron.effect(domain, () -> {
            if (!value.isLive()) {
                value.live();                        // integrated: its future is nobody's to know
            }
            double current = value.get();
            value.set(current + (target.get() - current) * alpha);
        });
        return value;
    }
}
