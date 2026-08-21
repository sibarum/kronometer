package sibarum.kronometer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A clock paced against a wall, carrying the slip model.
 *
 * <pre>{@code
 * Realtime clock = Clock.realtime()
 *         .settlement(Settlement.SLIP)
 *         .repayment(Repayment.rate(0.005))   // 0.5 % time-scale change: inaudible
 *         .maxSlip(Dur.ms(250));              // beyond this, one deliberate discontinuity
 * clock.onOverrun(System.out::println);
 *
 * try (Kron kron = Kron.of(clock)) { ... }
 * }</pre>
 *
 * <p>The whole model is one equation — {@code wall(m) = m + slip} — and everything here is the
 * arithmetic of keeping that true when the machine cannot. Deliberately free of any real timing:
 * with a scripted {@link Wall}, every path through it is a deterministic test.
 */
public final class Realtime implements Clock {

    private final Wall wall;

    private Settlement settlement = Settlement.SLIP;
    private Repayment repayment = Repayment.rate(0.005);
    private long maxSlipNanos = Long.MAX_VALUE;
    private Consumer<Overrun> listener = overrun -> { };

    /** Wall reading at logical origin. Fixed on the first wait, so construction cost is not counted. */
    private long epoch = Long.MIN_VALUE;
    private long slipNanos;
    private long previousTarget;

    Realtime(Wall wall) {
        this.wall = Objects.requireNonNull(wall, "wall");
    }

    // ---------------------------------------------------------- configuration

    public Realtime settlement(Settlement settlement) {
        this.settlement = Objects.requireNonNull(settlement, "settlement");
        return this;
    }

    /**
     * The repayment bound for {@link Settlement#SLIP} (and for the headroom {@link Settlement#SKIP}
     * finds). {@link Settlement#CATCH_UP} and {@link Settlement#STRETCH} define their own — unbounded
     * and zero — which is precisely what makes them the same mechanism as {@code SLIP}.
     */
    public Realtime repayment(Repayment repayment) {
        this.repayment = Objects.requireNonNull(repayment, "repayment");
        return this;
    }

    /**
     * The most debt to carry before writing it off in one reported discontinuity.
     *
     * <p>"Hope there is an opportunity to catch up later" is the honest description of slip, and this
     * is the answer for when there isn't one. Unbounded by default, because the right ceiling is a
     * property of the application, not of the kernel.
     */
    public Realtime maxSlip(Dur cap) {
        if (cap.isNegative()) {
            throw new IllegalArgumentException("maxSlip cannot be negative: " + cap);
        }
        this.maxSlipNanos = cap.nanos();
        return this;
    }

    // ----------------------------------------------------------------- clock

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    public Dur slip() {
        return new Dur(slipNanos);
    }

    @Override
    public Dur slackUntil(long logicalNanos) {
        if (epoch == Long.MIN_VALUE) {
            return Dur.FOREVER;
        }
        return new Dur(dueAt(logicalNanos) - wall.nanos());
    }

    @Override
    public void onOverrun(Consumer<Overrun> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public long awaitUntil(long targetNanos) throws InterruptedException {
        if (epoch == Long.MIN_VALUE) {
            epoch = wall.nanos();
            previousTarget = targetNanos;
        }
        long step = Math.max(0, targetNanos - previousTarget);
        previousTarget = targetNanos;

        long due = dueAt(targetNanos);
        long shortfall = wall.nanos() - due;

        if (shortfall > 0) {
            return settleLate(targetNanos, shortfall);
        }
        // Early. Headroom is exactly the chance to repay, and the only chance there is.
        repayInto(targetNanos, step, -shortfall);
        wall.parkUntil(dueAt(targetNanos));
        return targetNanos;
    }

    // --------------------------------------------------------------- internals

    private long dueAt(long logicalNanos) {
        return epoch + logicalNanos + slipNanos;
    }

    /**
     * The deadline is already behind us. Three policies grow the debt and differ only in how they
     * repay it later; {@code SKIP} instead forgives it and moves logical time.
     */
    private long settleLate(long targetNanos, long shortfall) {
        if (settlement == Settlement.SKIP) {
            long entered = targetNanos + shortfall;      // logical jumps; slip is untouched
            report(Overrun.Kind.SKIPPED, entered, shortfall);
            previousTarget = entered;
            return entered;
        }
        slipNanos += shortfall;
        if (slipNanos > maxSlipNanos) {
            return resync(targetNanos);
        }
        report(Overrun.Kind.LATE, targetNanos, shortfall);
        return targetNanos;
    }

    /**
     * Write the debt off in one deliberate jump. One-shot by construction: slip is zero afterwards,
     * so a single crossing cannot produce a storm of resyncs.
     */
    private long resync(long targetNanos) {
        long writtenOff = slipNanos;
        long entered = targetNanos + writtenOff;
        slipNanos = 0;
        previousTarget = entered;
        report(Overrun.Kind.RESYNC, entered, new Dur(writtenOff));
        return entered;
    }

    private void repayInto(long targetNanos, long stepNanos, long headroom) {
        if (slipNanos == 0) {
            return;
        }
        long allowance = repaymentFor().allowanceNanos(stepNanos);
        long repaid = Math.min(Math.min(slipNanos, allowance), headroom);
        if (repaid > 0) {
            slipNanos -= repaid;
            report(Overrun.Kind.REPAID, targetNanos, repaid);
        }
    }

    /**
     * The unification: {@code CATCH_UP} is {@code SLIP} with an unbounded repayment rate, and
     * {@code STRETCH} is {@code SLIP} with none. Only {@code SKIP} is a different mechanism.
     */
    private Repayment repaymentFor() {
        return switch (settlement) {
            case CATCH_UP -> Repayment.unbounded();
            case STRETCH -> Repayment.none();
            case SLIP, SKIP -> repayment;
        };
    }

    private void report(Overrun.Kind kind, long logicalNanos, long amountNanos) {
        report(kind, logicalNanos, new Dur(amountNanos));
    }

    private void report(Overrun.Kind kind, long logicalNanos, Dur amount) {
        listener.accept(new Overrun(
                kind, new Moment(logicalNanos), amount, new Dur(slipNanos), settlement));
    }

    @Override
    public String toString() {
        return "Clock.realtime(" + settlement + ", slip=" + slip() + ")";
    }
}
