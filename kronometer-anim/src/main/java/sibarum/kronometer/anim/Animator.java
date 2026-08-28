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
    /** Bumped every time a key changes hands, so a superseded play can tell that it has. */
    private final Map<Object, Long> generation = new HashMap<>();

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
     *
     * <h2>Unwinding is one of two policies, and it is not always the right one</h2>
     *
     * Worth saying next to the method rather than leaving to be discovered, because the bug it causes
     * only appears when a user is quick enough to produce two events inside one duration, and it erases
     * itself on the way past.
     *
     * <table border="1">
     * <caption>What a retrigger should do to the motion it displaces</caption>
     * <tr><th>Policy</th><th>Right when</th><th>Example</th></tr>
     * <tr><td><b>Unwind</b> the loser — what this method does</td>
     *     <td>it owns a resource that must be released, or a state that must be restored</td>
     *     <td>a shred holding a voice, a lock, a reservation</td></tr>
     * <tr><td><b>Abandon</b> the loser — see {@link #claim}</td>
     *     <td>its teardown would undo the winner, because both write the same slot</td>
     *     <td>a one-shot decoration; anything whose cleanup is "put the shared thing back"</td></tr>
     * </table>
     *
     * <p>Play a one-shot acknowledgement into a slot whose resting value is nothing — a sweep across a
     * field that took a command, a ring around one that refused — and unwinding is exactly backwards:
     * the loser's teardown clears the paint the winner just put down, and the flash erases itself
     * part-way through.
     *
     * <p>There is no policy argument here, and the reason is worth stating: a {@link Motion} is
     * ordinary consumer code, so nothing in this class can suppress its writes. Abandonment has to
     * happen where the writing happens, which is what {@link #claim} is for.
     */
    public Shred play(Object key, Motion motion) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(motion, "motion");
        stop(key);
        Shred shred = Time.spork("anim:" + key, motion::play);
        running.put(key, shred);
        return shred;
    }

    /**
     * A single play's claim on {@code key}: true until something else plays or stops under it.
     *
     * <p>The abandon policy, in one line instead of an identity table. Take a claim at the top of a
     * motion and gate everything it writes — samples and teardown alike — and a superseded play becomes
     * a no-op rather than an eraser:
     *
     * <pre>{@code
     * animator.play(slot, () -> {
     *     Animator.Claim mine = animator.claim(slot);
     *     Tween.ramp(frames, ms(200), Ease.OUT_CUBIC,
     *             a -> mine.ifCurrent(() -> node.overlay(cue.at(a))),
     *             () -> mine.ifCurrent(node::clear));       // the loser's cleanup: silent
     * });
     * }</pre>
     *
     * <p>The loser is still cancelled, so it stops sampling and its shred goes away. What changes is
     * that its {@code finally} finds itself out of date and does nothing — which is the whole
     * difference between abandoned and unwound.
     *
     * <p>Take the claim <em>inside</em> the motion. Taken outside, it is claimed before {@link #play}
     * has superseded the previous holder, and it would be stale from the start.
     */
    public Claim claim(Object key) {
        Objects.requireNonNull(key, "key");
        long mine = generation.getOrDefault(key, 0L);
        return () -> generation.getOrDefault(key, 0L) == mine;
    }

    /** One play's claim on a key. See {@link #claim}. */
    @FunctionalInterface
    public interface Claim {

        /** Whether the play that took this claim is still the current one under its key. */
        boolean isCurrent();

        /** Run {@code body} only if this claim is still current. */
        default void ifCurrent(Runnable body) {
            if (isCurrent()) {
                body.run();
            }
        }
    }

    /**
     * Cancel whatever is playing under {@code key}, if anything is.
     *
     * <p>Invalidates any {@link #claim} on the key too — a stop with no replacement is still the end of
     * that play's turn, and a teardown that reads its claim afterwards should find it stale.
     */
    public void stop(Object key) {
        generation.merge(key, 1L, Long::sum);
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
