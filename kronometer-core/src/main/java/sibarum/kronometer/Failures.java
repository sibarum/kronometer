package sibarum.kronometer;

import java.util.List;

/**
 * The kernel's failure vocabulary, gathered in one place so the modes it can fail in are legible.
 *
 * <p>Each of these names a distinct thing going wrong, and none of them is a generic
 * {@code IllegalStateException}: a strongly-timed system's failures are specific, and diagnosing them
 * from the outside is otherwise miserable.
 */
public final class Failures {

    private Failures() {
    }

    /**
     * Thrown inside a shred at its yield point when the shred is cancelled.
     *
     * <p>Catch it only to clean up, and let it propagate — {@code finally} blocks and
     * try-with-resources run normally, on the timeline, at the current moment. A cancelled shred
     * cannot advance time again, so cleanup is inherently bounded.
     */
    public static final class ShredCancelled extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ShredCancelled(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * A time intrinsic was called from a thread that is not a shred.
     *
     * <p>Outside the timeline you address a {@link Kron}; inside a shred you speak in {@link Time}.
     * This is what that distinction feels like when you get it wrong.
     */
    public static final class NotOnTimeline extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NotOnTimeline(String message) {
            super(message);
        }
    }

    /**
     * Nothing is scheduled, but shreds are still alive — so logical time can never advance again and
     * they can never run.
     *
     * <p>Under the virtual clock this is always a bug (every input is supposed to be scripted), so it
     * is an error rather than an idle wait. Under the realtime clock the same state is simply an idle
     * application.
     */
    public static final class TimelineStalled extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final transient List<String> waiting;

        TimelineStalled(List<String> waiting) {
            super("logical time cannot advance: nothing is scheduled, but " + waiting.size()
                    + " shred(s) are still alive and waiting — " + String.join(", ", waiting));
            this.waiting = List.copyOf(waiting);
        }

        /** The shreds that were stuck, described as {@code name#id(state)}. */
        public List<String> waiting() {
            return waiting;
        }
    }

    /**
     * One or more shreds ended by throwing. Reported once the timeline has drained, with every
     * original failure attached as a suppressed exception, so a run surfaces all of its problems
     * rather than only the first.
     */
    public static final class ShredFailed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ShredFailed(List<Throwable> causes) {
            super(causes.size() + " shred(s) ended by throwing");
            causes.forEach(this::addSuppressed);
        }
    }
}
