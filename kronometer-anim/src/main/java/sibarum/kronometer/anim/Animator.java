package sibarum.kronometer.anim;

import sibarum.kronometer.Cell;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Interp;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Shred;
import sibarum.kronometer.Time;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Retriggering, which is the thing every animation system gets wrong.
 *
 * <p>A hover-in interrupted halfway by a hover-out should reverse from where it actually is, not snap
 * back to the start and run again. Getting that right needs the interrupted animation's current value,
 * and most APIs make it hard to find.
 *
 * <h2>For a driven cell, you do not need this class</h2>
 *
 * Worth saying plainly, because it is a nice consequence of the M4 design rather than a feature anyone
 * built: {@link #retarget} reads the cell's <em>current</em> value and drives a fresh curve from it, and
 * {@code Cell.drive} replaces whatever curve was there. So continuity is automatic and there is nothing
 * to cancel — the interruption is just a new curve anchored at now, and it is still fully precomputable.
 *
 * <pre>{@code
 * animator.retarget(card, 1.0, ms(200), Ease.OUT_CUBIC);   // hover in
 * animator.retarget(card, 0.0, ms(120), Ease.OUT_QUAD);    // interrupt: continues from where it got to
 * }</pre>
 *
 * <h2>For a procedural tween, you do</h2>
 *
 * A {@link Tween#run} lives in a shred, and a shred has to be cancelled. {@link #play} keys running
 * motions so a retrigger cancels the previous one — and because cancellation unwinds on the timeline
 * (§4), the outgoing motion's {@code finally} runs before the incoming one starts.
 */
public final class Animator {

    private final Kron kron;
    private final Map<Object, Shred> running = new HashMap<>();

    public Animator(Kron kron) {
        this.kron = Objects.requireNonNull(kron, "kron");
    }

    /**
     * Drive {@code cell} to {@code target}, starting from wherever it is now.
     *
     * <p>Interruption-safe by construction, and precomputable, because the result is a curve rather
     * than a thread.
     */
    public <T> void retarget(Cell<T> cell, T target, Dur extent, Ease ease, Interp<T> interp) {
        Objects.requireNonNull(cell, "cell");
        cell.drive(Tween.curve(cell.get(), target, extent, ease, interp));
    }

    public void retarget(Cell<Double> cell, double target, Dur extent, Ease ease) {
        retarget(cell, target, extent, ease, Interp.DOUBLE);
    }

    /**
     * Play {@code motion} under {@code key}, cancelling whatever was playing under that key.
     *
     * <p>For procedural motions. The cancellation is delivered on the timeline, so the outgoing motion
     * unwinds — its {@code finally} blocks run at this moment — before the replacement begins.
     */
    public Shred play(Object key, Motion motion) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(motion, "motion");
        stop(key);
        Shred shred = Time.spork("anim:" + key, motion::play);
        running.put(key, shred);
        return shred;
    }

    /** Cancel whatever is playing under {@code key}, if anything is. */
    public void stop(Object key) {
        Shred previous = running.remove(key);
        if (previous != null && previous.isAlive()) {
            previous.cancel();
        }
    }

    /** Whether something is currently playing under {@code key}. */
    public boolean isPlaying(Object key) {
        Shred shred = running.get(key);
        return shred != null && shred.isAlive();
    }

    public Kron kron() {
        return kron;
    }
}
