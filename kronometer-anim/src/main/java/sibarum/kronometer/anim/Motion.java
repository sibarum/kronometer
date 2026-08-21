package sibarum.kronometer.anim;

import sibarum.kronometer.Dur;
import sibarum.kronometer.Shred;
import sibarum.kronometer.Time;

import java.util.List;
import java.util.Objects;

/**
 * Something that takes time to happen, composable with other such things.
 *
 * <p>A motion runs on the timeline, in a shred, and may advance logical time — which is exactly why
 * composing them needs no scheduler, no completion callbacks and no state machine. Sequencing is calling
 * one after another; running things together is sporking them and waiting.
 */
@FunctionalInterface
public interface Motion {

    /** Play, on the timeline, returning when finished. */
    void play();

    /** Anything at all, treated as a motion that takes no logical time. */
    static Motion of(Runnable body) {
        Objects.requireNonNull(body, "body");
        return body::run;
    }

    /** Do nothing for {@code d}. */
    static Motion delay(Dur d) {
        return () -> Time.advance(d);
    }

    /** One after another, each starting when the previous finishes. */
    static Motion sequence(Motion... motions) {
        List<Motion> steps = List.of(motions);
        return () -> steps.forEach(Motion::play);
    }

    /**
     * All at once, returning when the slowest finishes.
     *
     * <p>The wait is worth a look, because it shows the baton earning its keep:
     *
     * <pre>{@code
     * if (child.isAlive()) await(child.done());
     * }</pre>
     *
     * A {@code Trigger} does not latch, so awaiting one that has already fired would block forever — the
     * classic check-then-act race. Here it is not a race at all: this code holds the baton, so no child
     * can finish between the check and the await. The same two lines under a normal scheduler would need
     * a latch.
     */
    static Motion parallel(Motion... motions) {
        List<Motion> branches = List.of(motions);
        return () -> {
            List<Shred> children = branches.stream()
                    .map(branch -> Time.spork("motion", branch::play))
                    .toList();
            for (Shred child : children) {
                if (child.isAlive()) {
                    Time.await(child.done());
                }
            }
        };
    }

    /** All at once, but each starting {@code gap} after the one before — a cascade. */
    static Motion stagger(Dur gap, List<Motion> motions) {
        Objects.requireNonNull(gap, "gap");
        List<Motion> items = List.copyOf(motions);
        return () -> {
            List<Shred> children = new java.util.ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                Motion item = items.get(i);
                Dur offset = gap.times(i);
                children.add(Time.spork("stagger-" + i, () -> {
                    if (!offset.isZero()) {
                        Time.advance(offset);
                    }
                    item.play();
                }));
            }
            for (Shred child : children) {
                if (child.isAlive()) {
                    Time.await(child.done());
                }
            }
        };
    }

    /** Play this motion, then that one. */
    default Motion then(Motion next) {
        return sequence(this, next);
    }

    /** Repeat this motion {@code times} over. */
    default Motion repeat(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times cannot be negative: " + times);
        }
        Motion self = this;
        return () -> {
            for (int i = 0; i < times; i++) {
                self.play();
            }
        };
    }
}
