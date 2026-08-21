package sibarum.kronometer;

import java.util.concurrent.locks.LockSupport;

/**
 * The baton: a reusable single-permit handoff between the kernel and one shred.
 *
 * <p>Dekker-style over {@link LockSupport}. {@code open()} writes {@code open} then reads
 * {@code waiter}; {@code await()} writes {@code waiter} then reads {@code open}. Both fields are
 * volatile, so at least one side observes the other and a wakeup cannot be lost — in particular
 * {@code open()} may safely arrive before the other side has reached {@code await()}, which is what
 * lets a freshly sporked shred be handed the baton without a start-up handshake.
 *
 * <p>Assumes the strict alternation a baton protocol gives it: one waiter, never two opens
 * outstanding. That assumption is what keeps it this small, and it is why this type is not public.
 *
 * <p>M0 measured this at 489 ns per handoff on the JVM and 577 ns under native-image, against
 * {@link java.util.concurrent.Semaphore} at 1 312 ns and a spin-then-park variant that is 12× worse
 * on a single carrier. See {@code docs/benchmarks/baton.md}.
 */
final class Gate {

    private volatile Thread waiter;
    private volatile boolean open;

    /** Hand the baton over. Safe to call before the other side is waiting. */
    void open() {
        open = true;
        Thread w = waiter;
        if (w != null) {
            LockSupport.unpark(w);
        }
    }

    /**
     * Take the baton, blocking until it is offered.
     *
     * <p>Deliberately does not consult the interrupt flag: the kernel never interrupts a shred, it
     * cancels it, which is delivered on the timeline as a {@link Failures.ShredCancelled} at the
     * yield point. Honouring interruption here would introduce a second, racier cancellation path.
     */
    void await() {
        waiter = Thread.currentThread();
        while (!open) {
            LockSupport.park();
        }
        open = false;
        waiter = null;
    }
}
