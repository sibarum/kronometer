package sibarum.kronometer;

/**
 * Whether a sporked shred is bound to the lifetime of the shred that sporked it.
 *
 * <p>Shreds form a tree, and cancelling a parent cancels its children — that is what makes a shred's
 * cleanup complete rather than leaving orphans advancing time behind it. {@link #YES} opts out, for
 * the genuinely independent case.
 */
public enum Detach {

    /** Cancelled along with the parent. The default, and almost always what you want. */
    NO,

    /** Survives the parent's cancellation. */
    YES
}
