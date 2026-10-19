package sibarum.kronometer;

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
 *
 * <p>The waiters are an intrusive list threaded through the shreds themselves, which a shred can be on
 * only one of at a time. A timed wait that times out, or is cancelled, unlinks itself in constant time
 * when it resumes, so nothing stale is left behind. A queue pruned lazily leaked one entry per timed-out
 * wait: a fixed-rate domain waits on its {@code retimed} trigger with a timeout once per step, so a
 * constant tempo grew the queue by one reference per audio sample, forever.
 */
public final class Trigger {

    private final Kron kron;
    private final String name;
    private Shred head;
    private Shred tail;
    private int size;

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
        Shred s = head;
        if (s != null) {
            unlink(s);
            s.wakeFromTrigger();
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
        while ((s = head) != null) {
            unlink(s);
            s.wakeFromTrigger();
        }
    }

    /** How many shreds are waiting. */
    public int waiting() {
        return size;
    }

    /** Append {@code s}, which must not be on any trigger's list. */
    void enrol(Shred s) {
        s.waitPrev = tail;
        s.waitNext = null;
        if (tail == null) {
            head = s;
        } else {
            tail.waitNext = s;
        }
        tail = s;
        size++;
    }

    /** Take {@code s} off the list, which it must be on. */
    void unlink(Shred s) {
        Shred prev = s.waitPrev;
        Shred next = s.waitNext;
        if (prev == null) {
            head = next;
        } else {
            prev.waitNext = next;
        }
        if (next == null) {
            tail = prev;
        } else {
            next.waitPrev = prev;
        }
        s.waitPrev = null;
        s.waitNext = null;
        size--;
    }

    @Override
    public String toString() {
        return "Trigger(" + name + ")";
    }
}
