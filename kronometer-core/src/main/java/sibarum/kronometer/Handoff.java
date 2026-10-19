package sibarum.kronometer;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A fixed domain's steps, run on a thread that is not the timeline's.
 *
 * <p>Some work cannot run on the baton because it belongs to one thread: a GPU queue is the usual case, which
 * only the render thread may submit to. The domain still decides <em>when</em> a step is due — its grid, its
 * tempo, its catch-up policy — but the step itself has to be done elsewhere. So the domain's handler only
 * records the step, and the owning thread {@link #drain drains} what was recorded, once a frame, and does the
 * work:
 *
 * <pre>{@code
 * Rate physics = kron.tempo().child(speed).fixed(hz(60)).maxCatchUp(4);
 * Handoff steps = physics.handoff();
 *
 * // on the render thread, after kron.tick():
 * steps.drain(step -> world.step(step.dt()));
 * renderer.draw(world, steps.phase(kron.now()));   // blend the last two steps that ran
 * }</pre>
 *
 * <h2>Where a moment sits between steps</h2>
 *
 * {@link Sampled} answers this for a value the host can blend. State that lives somewhere the host does not hold
 * it, such as buffers on a GPU, is blended where it lives, and all the host can supply is how far along:
 * {@link #phase} is that fraction, between the last two steps that were <em>run</em>, with the same one-step lag
 * {@code Sampled} has. It counts steps that ran, not steps that were due, so a step recorded and not yet drained,
 * or one dropped while {@link #hold held}, never moves it.
 *
 * <h2>Holding</h2>
 *
 * A held handoff takes its handler off the domain, so the work stops, {@link #phase} settles at 1 and the picture
 * rests on the last step that ran. A domain with nothing else on it parks, so a pause costs no wakes. On release
 * the handler goes back, and any step due before the release is dropped: a domain catching up after the pause would
 * otherwise replay it. A pause is not a tempo of zero, which a {@link Ratio} cannot be.
 *
 * <p>The handler runs on the timeline and everything else on the owning thread. The queue between them is
 * thread-safe, so the two never need to agree on more than that.
 */
public final class Handoff implements AutoCloseable {

    private final Rate domain;
    private final Consumer<Step> recorder = this::record;
    private final ConcurrentLinkedQueue<Due> due = new ConcurrentLinkedQueue<>();
    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean held;
    private volatile boolean closed;
    private volatile Moment releasedAt;

    // Owning thread only: the last step that ran, and how much global time it covers.
    private Moment lastRanAt;
    private Dur lastSpan = Dur.ZERO;
    private long ran;

    Handoff(Rate domain) {
        if (domain.kind() != Rate.Kind.FIXED) {
            throw new IllegalArgumentException("a handoff is for a fixed domain, whose grid says when a step is due;"
                    + " " + domain.name() + " is " + domain.kind());
        }
        this.domain = domain;
        domain.each(recorder);
    }

    /** On the timeline: a step is due. Kept for the owning thread, unless it is from before a release. */
    private void record(Step step) {
        Moment released = releasedAt;
        if (released != null && step.at().isBefore(released)) {
            dropped.incrementAndGet();
            return;
        }
        // The span is taken here, on the timeline, because it needs the tempo's map; the owning thread only reads it.
        due.add(new Due(step, domain.wallSpan(step)));
    }

    /** A step that came due, with the global time it covers. See {@link Rate#wallSpan}. */
    private record Due(Step step, Dur span) { }

    /**
     * Runs every step that has come due since the last drain, in order, on the calling thread.
     *
     * @return how many ran
     */
    public int drain(Consumer<Step> work) {
        Objects.requireNonNull(work, "work");
        int count = 0;
        for (Due next = due.poll(); next != null; next = due.poll()) {
            work.accept(next.step());
            lastRanAt = next.step().at();
            lastSpan = next.span();
            ran++;
            count++;
        }
        return count;
    }

    /**
     * How far {@code at} is from the step before the last one that ran to the last one, from 0 to 1: the blend
     * that shows the state as it was one step before {@code at}, as {@link Sampled#at} does. 1 before anything has
     * run, so a caller blending toward the newest state shows it.
     */
    public float phase(Moment at) {
        if (lastRanAt == null || lastSpan.isZero()) {
            return 1f;
        }
        float alpha = (float) (at.since(lastRanAt).nanos() / (double) lastSpan.nanos());
        return Math.clamp(alpha, 0f, 1f);
    }

    /**
     * Stops the work, or starts it again; see the class. Held, nothing comes due, and what had come due and was not
     * drained is dropped. Released, any step due before the release is dropped, which a domain catching up would
     * otherwise replay. On the timeline, or the host's thread between ticks, as {@link Rate#each} is.
     */
    public void hold(boolean hold) {
        if (hold == held || closed) {
            return;
        }
        held = hold;
        if (hold) {
            // Off the domain, so it parks if nothing else is on it: a paused simulation costs no wakes.
            domain.remove(recorder);
            dropped.addAndGet(due.size());
            due.clear();
        } else {
            releasedAt = domain.kron().now();
            domain.each(recorder);
        }
    }

    /** Whether the work is stopped; see {@link #hold}. */
    public boolean held() {
        return held;
    }

    /** Throws away what has come due and not been drained: a reset, after which the old steps mean nothing. */
    public void discard() {
        dropped.addAndGet(due.size());
        due.clear();
    }

    /** Steps recorded and not yet drained. */
    public int pending() {
        return due.size();
    }

    /** Steps that have run, by {@link #drain}. Owning thread. */
    public long ran() {
        return ran;
    }

    /** Steps that came due and were dropped: while held, from before a release, or by {@link #discard}. */
    public long dropped() {
        return dropped.get();
    }

    /** The domain whose steps these are. */
    public Rate domain() {
        return domain;
    }

    /** Stops recording: the handler comes off the domain, which parks if it has no other. */
    @Override
    public void close() {
        closed = true;
        domain.remove(recorder);
        due.clear();
    }
}
