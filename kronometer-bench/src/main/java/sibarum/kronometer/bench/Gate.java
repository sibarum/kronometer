package sibarum.kronometer.bench;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

/**
 * A reusable single-permit handoff: {@link #open()} passes the baton, {@link #await()} takes it.
 *
 * <p>Two implementations, because which one the kernel should use is exactly the question M0 exists
 * to answer. Both assume the strict alternation a baton protocol gives them — one waiter, and never
 * two {@code open()}s outstanding — which is what lets {@link ParkGate} be this small.
 */
sealed interface Gate {

    void open();

    void await() throws InterruptedException;

    enum Kind { PARK, SEMAPHORE, SPIN_PARK }

    static Gate of(Kind kind) {
        return switch (kind) {
            case PARK -> new ParkGate();
            case SEMAPHORE -> new SemaphoreGate();
            case SPIN_PARK -> new SpinParkGate();
        };
    }

    /**
     * Dekker-style handoff over {@link LockSupport}. {@code open()} writes {@code open} then reads
     * {@code waiter}; {@code await()} writes {@code waiter} then reads {@code open}. Both fields are
     * volatile, so at least one side observes the other and a wakeup cannot be lost.
     *
     * <p>Ignores interruption on purpose: the benchmark never interrupts, and handling it here would
     * measure the handling rather than the handoff.
     */
    final class ParkGate implements Gate {
        private volatile Thread waiter;
        private volatile boolean open;

        @Override
        public void open() {
            open = true;
            Thread w = waiter;
            if (w != null) {
                LockSupport.unpark(w);
            }
        }

        @Override
        public void await() {
            waiter = Thread.currentThread();
            while (!open) {
                LockSupport.park();
            }
            open = false;
            waiter = null;
        }
    }

    /**
     * Spin briefly before parking. In a baton protocol the other side answers almost immediately, so
     * a short spin can skip the OS wakeup entirely — the trick low-latency audio has always used.
     *
     * <p>The catch, and it is a sharp one: a spinning <em>virtual</em> thread holds its carrier, so if
     * both parties share one carrier the spin cannot possibly be satisfied and is pure waste until it
     * falls back to parking. Fast when the two sides sit on different carriers, a tax when they do
     * not — which is exactly the tradeoff this benchmark is here to price.
     */
    final class SpinParkGate implements Gate {
        private static final int SPINS = 256;

        private volatile Thread waiter;
        private volatile boolean open;

        @Override
        public void open() {
            open = true;
            Thread w = waiter;
            if (w != null) {
                LockSupport.unpark(w);
            }
        }

        @Override
        public void await() {
            for (int i = 0; i < SPINS; i++) {
                if (open) {
                    open = false;
                    return;
                }
                Thread.onSpinWait();
            }
            waiter = Thread.currentThread();
            while (!open) {
                LockSupport.park();
            }
            open = false;
            waiter = null;
        }
    }

    /** The obvious implementation, and the one to beat. */
    final class SemaphoreGate implements Gate {
        private final Semaphore permits = new Semaphore(0);

        @Override
        public void open() {
            permits.release();
        }

        @Override
        public void await() throws InterruptedException {
            permits.acquire();
        }
    }
}
