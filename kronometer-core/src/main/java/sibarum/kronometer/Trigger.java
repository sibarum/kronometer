package sibarum.kronometer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Something a shred can wait for. ChucK's event.
 *
 * <p>Woken shreds resume <em>at the moment of the firing</em>, in wait order, which is what makes a
 * trigger a scheduling primitive rather than a notification: no logical time passes between the
 * firing and the waking.
 *
 * <p>Named {@code Trigger} rather than {@code Signal} because {@code Signal} is spent on the reactive
 * supertype in M4, where the industry now expects it. This is the one place Kronometer's ChucK
 * lineage is obscured; see {@code docs/architecture.md} §15.2.
 */
public final class Trigger {

    private final Kron kron;
    private final String name;
    private final Deque<Shred> waiters = new ArrayDeque<>();

    Trigger(Kron kron, String name) {
        this.kron = kron;
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** Wake the longest-waiting shred, if any. */
    public void fire() {
        kron.requireOnTimeline("fire()");
        while (!waiters.isEmpty()) {
            Shred s = waiters.poll();
            if (s.isWaitingOn(this)) {
                s.wakeFromTrigger();
                return;
            }
        }
    }

    /** Wake every waiting shred, in wait order. */
    public void broadcast() {
        kron.requireOnTimeline("broadcast()");
        wakeAll();
    }

    /**
     * Broadcast without the on-timeline check, for the kernel itself.
     *
     * <p>A shred ending fires its {@code done()} trigger from its own thread after the scoped binding
     * has already unwound, so {@code CURRENT} is no longer bound — even though the kernel is blocked
     * on the baton and mutual exclusion still holds.
     */
    void wakeAll() {
        Shred s;
        while ((s = waiters.poll()) != null) {
            if (s.isWaitingOn(this)) {
                s.wakeFromTrigger();
            }
        }
    }

    /** How many shreds are waiting. Stale entries are pruned lazily, so this is an upper bound. */
    public int waiting() {
        return (int) waiters.stream().filter(s -> s.isWaitingOn(this)).count();
    }

    void enrol(Shred s) {
        waiters.add(s);
    }

    @Override
    public String toString() {
        return "Trigger(" + name + ")";
    }
}
