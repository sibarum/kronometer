package sibarum.kronometer;

import java.util.ArrayList;
import java.util.List;

/**
 * A record of everything the kernel did, in order.
 *
 * <p>Under {@link Clock#virtual()} a trace is a <em>reproducible artifact</em>: two runs of the same
 * scenario produce identical traces, so a test can assert on the whole schedule rather than on
 * sampled state. Nothing here records wall-clock time or identity hashes, which is what makes that
 * true.
 *
 * <p>Off by default — {@link Kron#trace()} turns it on. Tracing costs one small allocation per event,
 * which is nothing against a 489 ns handoff but is not nothing in a tight animation loop.
 */
public final class Trace {

    /** What happened. */
    public enum Kind {
        /** A shred was created. */
        SPORK,
        /** A shred was given the baton. */
        RESUME,
        /** A shred released the baton to let logical time pass. */
        ADVANCE,
        /** A shred released the baton to wait on a trigger. */
        AWAIT,
        /** A trigger woke a shred. */
        WOKEN,
        /** A shred's await timed out. */
        TIMEOUT,
        /** A shred was asked to stop. */
        CANCEL,
        /** A shred's body returned or threw. */
        END
    }

    /**
     * One recorded event.
     *
     * @param moment  the logical moment it happened at
     * @param shredId the shred it happened to
     * @param kind    what happened
     * @param detail  a short, deterministic description — never a wall-clock time or a hash
     */
    public record Entry(Moment moment, long shredId, Kind kind, String detail) {

        @Override
        public String toString() {
            return moment + " #" + shredId + " " + kind + (detail.isEmpty() ? "" : " " + detail);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    Trace() {
    }

    void record(Moment moment, long shredId, Kind kind, String detail) {
        entries.add(new Entry(moment, shredId, kind, detail));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    /** The trace as one line per event — the form to assert on, and to diff between runs. */
    public String render() {
        StringBuilder sb = new StringBuilder(entries.size() * 32);
        for (Entry e : entries) {
            sb.append(e).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Trace(" + entries.size() + " entries)";
    }
}
