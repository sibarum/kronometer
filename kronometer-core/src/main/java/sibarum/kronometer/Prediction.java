package sibarum.kronometer;

import java.util.HashMap;
import java.util.Map;

/**
 * One signal's precomputed future on one domain's grid, plus the accounting that decides whether the
 * precomputing was worth it.
 *
 * <p>Samples are keyed by grid index rather than by moment, because a fixed domain's grid line
 * <em>is</em> an index — which is what makes reading at {@code now} an index lookup rather than a
 * search. Dynamic domains cannot be predicted at all: their sample points are whatever the display
 * decides, so there is no grid to fill in advance (see {@link Rate#predict}).
 *
 * <h2>The constant tail</h2>
 *
 * Past {@link Signal#varyingUntil()} the value stops changing, so storing a thousand identical samples
 * would be pure waste. Everything beyond that index resolves to one stored constant. This is the whole
 * reason M4 split {@code varyingUntil} out of {@code horizon}: the first number says how far it is
 * legal to fill, the second says how far it is <em>useful</em> to.
 */
public final class Prediction<T> {

    private final Signal<T> signal;
    private final Rate domain;

    private final Map<Long, T> samples = new HashMap<>();
    private long constantFromIndex = Long.MAX_VALUE;
    private long highestFilled = -1;
    private T constantValue;

    private Predict policy;
    private long filled;
    private long discarded;
    private long hits;
    private long misses;
    private boolean demoted;

    Prediction(Signal<T> signal, Rate domain, Predict policy) {
        this.signal = signal;
        this.domain = domain;
        this.policy = policy;
    }

    // ------------------------------------------------------------------ API

    public Signal<T> signal() {
        return signal;
    }

    public Rate domain() {
        return domain;
    }

    public Predict policy() {
        return policy;
    }

    /** Whether the kernel lowered the policy because the measured waste made eager filling a loss. */
    public boolean isDemoted() {
        return demoted;
    }

    /** Samples computed ahead. */
    public long filled() {
        return filled;
    }

    /** Samples thrown away by an invalidation before anyone read them. */
    public long discarded() {
        return discarded;
    }

    /** Reads served from the buffer. */
    public long hits() {
        return hits;
    }

    /** Reads that had to evaluate because nothing was buffered. */
    public long misses() {
        return misses;
    }

    /** Discarded over filled — the number that decides whether eager prediction is paying for itself. */
    public double waste() {
        return filled == 0 ? 0 : discarded / (double) filled;
    }

    public int buffered() {
        return samples.size();
    }

    @Override
    public String toString() {
        return "Prediction(" + signal + " on " + domain.name() + ", " + policy
                + ", buffered=" + samples.size() + ", waste=" + String.format("%.0f%%", waste() * 100)
                + ")";
    }

    // -------------------------------------------------------------- internals

    void store(long index, T value) {
        samples.put(index, value);
        filled++;
        highestFilled = Math.max(highestFilled, index);
    }

    void storeConstantFrom(long index, T value) {
        constantFromIndex = index;
        constantValue = value;
        filled++;
        highestFilled = Math.max(highestFilled, index);
    }

    /**
     * The frontier of the filled window, so a top-up starts where the last one stopped.
     *
     * <p>Without this the fill re-walked the whole window on every step asking "is this one already
     * there?" — 4 800 map probes per step for a 100 ms audio window, which measured as 11 ms of pure
     * bookkeeping over a 480-step run and made prediction slower than not predicting.
     */
    long highestFilled() {
        return highestFilled;
    }

    /** Once a constant tail is stored, the window is full until something invalidates it. */
    boolean isComplete() {
        return constantFromIndex != Long.MAX_VALUE;
    }

    boolean has(long index) {
        return samples.containsKey(index) || index >= constantFromIndex;
    }

    T take(long index) {
        if (index >= constantFromIndex) {
            hits++;
            return constantValue;
        }
        T value = samples.remove(index);
        if (value == null) {
            misses++;
            return null;
        }
        hits++;
        return value;
    }

    void recordMiss() {
        misses++;
    }

    /**
     * Hand this moment's buffered value to the signal's own memo.
     *
     * <p>The value enters by the same door evaluation would have used, which is what makes
     * precomputation observationally invisible: a reader calling {@code get()} gets an index lookup and
     * no way to tell. Only a {@link Derived} has a memo worth priming — a {@link Cell} is already a
     * field read.
     */
    @SuppressWarnings("unchecked")
    void primeInto(long index, Moment at, long version) {
        if (!has(index)) {
            recordMiss();
            return;
        }
        T value = take(index);
        if (value != null && signal instanceof Derived<?>) {
            ((Derived<T>) signal).prime(at, version, value);
        }
    }

    /**
     * Drop everything predicted for a moment strictly after {@code at}.
     *
     * <p>Strictly after: a sample already delivered for an earlier grid line was correct when it was
     * delivered. Only the future is retracted.
     */
    void discardAfter(Moment at) {
        samples.keySet().removeIf(index -> {
            boolean stale = domain.gridLineAt(index).isAfter(at);
            if (stale) {
                discarded++;
            }
            return stale;
        });
        // The tail is half-open — [index, forever) — so any invalidation at all cuts into it, whatever
        // index it starts at. Dropping it only when its *start* was later left a tail that began before
        // the change serving stale values indefinitely, which is what the differential property test
        // caught on its third seed. Moments before `at` were already consumed, so losing them costs
        // nothing.
        if (constantFromIndex != Long.MAX_VALUE) {
            constantFromIndex = Long.MAX_VALUE;
            constantValue = null;
            discarded++;
        }
        highestFilled = samples.keySet().stream().mapToLong(Long::longValue).max().orElse(-1);
    }

    /**
     * Lower the policy if the waste says so, or restore it if the signal has settled down.
     *
     * <p>Hysteretic, like {@link Rate#degrade}: quick to demote, slow to restore, so a signal on the
     * threshold does not flap between policies.
     */
    void reconsider(double demoteAbove, double restoreBelow, long minimumSamples) {
        if (policy == Predict.NEVER || filled < minimumSamples) {
            return;
        }
        double waste = waste();
        if (!demoted && waste > demoteAbove) {
            policy = Predict.LAZY;
            demoted = true;
        } else if (demoted && waste < restoreBelow) {
            policy = Predict.EAGER;
            demoted = false;
        }
    }

    void resetAccounting() {
        filled = 0;
        discarded = 0;
        hits = 0;
        misses = 0;
    }
}
