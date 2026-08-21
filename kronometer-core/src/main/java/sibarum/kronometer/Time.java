package sibarum.kronometer;

import java.util.Objects;

/**
 * The time intrinsics: what a shred says to make logical time pass.
 *
 * <p>Static on purpose. The current shred is carried by a {@code ScopedValue}, so these read as bare
 * verbs once statically imported, which is as close to ChucK's {@code 250::ms => now} as Java gets:
 *
 * <pre>{@code
 * import static sibarum.kronometer.Time.*;
 * import static sibarum.kronometer.Dur.*;
 *
 * advance(ms(250));
 * }</pre>
 *
 * <p>Every method here requires a current shred and throws {@link Failures.NotOnTimeline} without
 * one. Outside the timeline you address a {@link Kron} instead — the split is a compile-time-visible
 * reminder of which side of the baton you are on.
 */
public final class Time {

    private Time() {
    }

    /** The shred running this code. */
    public static Shred self() {
        if (!Kron.CURRENT.isBound()) {
            throw new Failures.NotOnTimeline(
                    "no current shred: the time intrinsics only work inside a shred");
        }
        return Kron.CURRENT.get();
    }

    /** The runtime this shred belongs to. */
    public static Kron kron() {
        return self().kron();
    }

    /** The current logical moment. Constant for the whole of a zero-time segment. */
    public static Moment now() {
        return self().kron().now();
    }

    /**
     * Let {@code d} of logical time pass.
     *
     * <p>Lands on exactly {@code now + d}, because the target is computed from the shred's logical
     * {@code now} and never from a wall-clock reading — so error cannot accumulate across a sequence
     * of advances.
     *
     * <p>{@code advance(Dur.ZERO)} is legal and useful: it yields to anything else scheduled at this
     * moment without letting time pass.
     */
    public static void advance(Dur d) {
        Objects.requireNonNull(d, "d");
        if (d.isNegative()) {
            throw new IllegalArgumentException("cannot advance by a negative duration: " + d);
        }
        Shred self = self();
        self.suspendUntil(self.kron().now().plus(d));
    }

    /** Let logical time pass until {@code moment}, which must not be in the past. */
    public static void until(Moment moment) {
        Objects.requireNonNull(moment, "moment");
        Shred self = self();
        Moment now = self.kron().now();
        if (moment.isBefore(now)) {
            throw new IllegalArgumentException("cannot go back in time: " + moment + " < " + now);
        }
        self.suspendUntil(moment);
    }

    /**
     * Let logical time pass until the next grid line of {@code period}, measured from the origin.
     *
     * <p>Quantization, and drift-free by construction: the target is a multiple of the period rather
     * than an offset from the last wake, so a loop of {@code sync(ms(500))} stays exactly on the
     * half-second forever.
     *
     * <p>Always advances to a grid line <em>strictly</em> after now — landing on one does not mean
     * returning immediately, which is what keeps {@code while (true) sync(p)} from spinning at zero
     * logical time.
     */
    public static void sync(Dur period) {
        Objects.requireNonNull(period, "period");
        if (period.nanos() <= 0) {
            throw new IllegalArgumentException("period must be positive: " + period);
        }
        Shred self = self();
        long p = period.nanos();
        long index = Math.floorDiv(self.kron().now().nanos(), p) + 1;
        self.suspendUntil(new Moment(Math.multiplyExact(index, p)));
    }

    /**
     * Wait until {@code trigger} fires. Logical time passes meanwhile; the shred resumes at the
     * moment of the firing.
     */
    public static void await(Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        self().suspendOnTrigger(trigger, null);
    }

    /**
     * Wait until {@code trigger} fires, or {@code timeout} of logical time passes.
     *
     * @return {@code true} if the trigger fired, {@code false} if it timed out
     */
    public static boolean await(Trigger trigger, Dur timeout) {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout cannot be negative: " + timeout);
        }
        Shred self = self();
        return self.suspendOnTrigger(trigger, self.kron().now().plus(timeout));
    }

    /** Create a child shred, to start later in this same step. */
    public static Shred spork(Runnable body) {
        return self().kron().spork(body);
    }

    public static Shred spork(String name, Runnable body) {
        return self().kron().spork(name, body);
    }

    public static Shred spork(Detach detach, String name, Runnable body) {
        return self().kron().spork(detach, name, body);
    }
}
