package sibarum.kronometer;

import java.util.ArrayList;
import java.util.List;

/**
 * A wall clock the test drives by hand.
 *
 * <p>This is the seam that makes the slip model testable. {@link #parkUntil} does not wait — it simply
 * moves the reading to the deadline, which is what waiting <em>means</em> when nobody is really
 * waiting, and it records how long the wait would have been so a test can assert that repayment
 * shortened it.
 *
 * <p>Scripting an overrun is then just {@link #advance}: called from inside a shred's segment, it says
 * "this segment took longer than its logical duration", which is precisely what an overrun is.
 */
final class ManualWall implements Wall {

    private long nanos;
    private final List<Long> waits = new ArrayList<>();

    @Override
    public long nanos() {
        return nanos;
    }

    @Override
    public void parkUntil(long deadlineNanos) {
        waits.add(Math.max(0, deadlineNanos - nanos));
        if (deadlineNanos > nanos) {
            nanos = deadlineNanos;
        }
    }

    /** Burn wall-clock time without logical time passing: an overrun. */
    void advance(Dur d) {
        nanos += d.nanos();
    }

    /** How long each park would have blocked for, in order. */
    List<Long> waits() {
        return List.copyOf(waits);
    }

    List<Dur> waitsAsDurs() {
        return waits.stream().map(Dur::new).toList();
    }
}
