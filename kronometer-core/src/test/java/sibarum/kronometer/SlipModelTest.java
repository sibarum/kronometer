package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Time.now;

/**
 * The slip model, as arithmetic.
 *
 * <p>None of this sleeps. {@code wall(m) = m + slip} is pure bookkeeping, so with a
 * {@link ManualWall} every path through it — debt accrual, bounded repayment, forgiveness, write-off —
 * is a deterministic unit test with a scripted sequence of overruns. The two tests that touch a real
 * wall live in {@link RealtimeSmokeTest} and assert almost nothing.
 */
class SlipModelTest {

    /**
     * A ten-frame run on a 10 ms grid, with one frame that overruns by {@code stall}.
     *
     * @return the slip after every frame, in order
     */
    private static List<Dur> slipCurve(Settlement settlement, Repayment repayment, Dur maxSlip,
                                       Dur stall, List<Overrun> events, ManualWall wall,
                                       List<Moment> visited) {
        Realtime clock = Clock.realtime(wall).settlement(settlement).repayment(repayment);
        if (maxSlip != null) {
            clock.maxSlip(maxSlip);
        }
        clock.onOverrun(events::add);

        List<Dur> curve = new ArrayList<>();
        try (Kron kron = Kron.of(clock)) {
            kron.spork("frames", () -> {
                Metro metro = Metro.of(ms(10));
                for (int frame = 0; frame < 10; frame++) {
                    metro.tick();
                    visited.add(now());
                    curve.add(clock.slip());
                    if (frame == 1) {
                        wall.advance(stall);       // this segment took far longer than 10 ms
                    }
                }
            });
            kron.run();
        }
        return curve;
    }

    private static List<Dur> slipCurve(Settlement settlement, Repayment repayment, Dur stall) {
        return slipCurve(settlement, repayment, null, stall, new ArrayList<>(), new ManualWall(),
                new ArrayList<>());
    }

    @Test
    @DisplayName("SLIP holds the debt and repays it within its bound, never overshooting")
    void slipRepaysGradually() {
        List<Dur> curve = slipCurve(Settlement.SLIP, Repayment.atMost(ms(2)), ms(25));

        // Frame 2 discovers a 15 ms shortfall; from then on 2 ms of debt clears per frame, and it
        // stops at exactly zero rather than going negative.
        assertEquals(List.of(
                ms(0), ms(0), ms(15), ms(13), ms(11), ms(9), ms(7), ms(5), ms(3), ms(1)), curve);
    }

    @Test
    @DisplayName("repayment never drives slip negative")
    void repaymentCannotOvershoot() {
        List<Dur> curve = slipCurve(Settlement.SLIP, Repayment.atMost(ms(4)), ms(15));
        assertEquals(List.of(
                ms(0), ms(0), ms(5), ms(1), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0)), curve);
        for (Dur slip : curve) {
            assertTrue(slip.nanos() >= 0, "slip went negative: " + slip);
        }
    }

    @Test
    @DisplayName("CATCH_UP is SLIP with unbounded repayment — the same mechanism, not a separate one")
    void catchUpIsSlipWithUnboundedRepayment() {
        List<Dur> viaPolicy = slipCurve(Settlement.CATCH_UP, Repayment.atMost(ms(2)), ms(25));
        List<Dur> viaRepayment = slipCurve(Settlement.SLIP, Repayment.unbounded(), ms(25));

        assertEquals(viaPolicy, viaRepayment);
        // 15 ms of debt, 10 ms of headroom per frame: cleared in two frames, flat out.
        assertEquals(List.of(
                ms(0), ms(0), ms(15), ms(5), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0)), viaPolicy);
    }

    @Test
    @DisplayName("STRETCH is SLIP with repayment disabled — the debt simply stands")
    void stretchIsSlipWithNoRepayment() {
        List<Dur> viaPolicy = slipCurve(Settlement.STRETCH, Repayment.atMost(ms(2)), ms(25));
        List<Dur> viaRepayment = slipCurve(Settlement.SLIP, Repayment.none(), ms(25));

        assertEquals(viaPolicy, viaRepayment);
        assertEquals(List.of(
                ms(0), ms(0), ms(15), ms(15), ms(15), ms(15), ms(15), ms(15), ms(15), ms(15)),
                viaPolicy);
    }

    @Test
    @DisplayName("SKIP forgives the debt and moves logical time instead")
    void skipMovesTimeNotDebt() {
        List<Overrun> events = new ArrayList<>();
        List<Moment> visited = new ArrayList<>();
        List<Dur> curve = slipCurve(Settlement.SKIP, Repayment.none(), null, ms(25), events,
                new ManualWall(), visited);

        // Slip never moves: SKIP does not carry a debt at all.
        assertEquals(List.of(ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0)),
                curve);

        // Logical time jumps from the 30 ms it asked for to the 45 ms the wall had reached, and the
        // grid resumes from there. The 40 ms boundary is *skipped*, not replayed — which is only true
        // because Metro schedules from an origin rather than from its own last wake.
        assertEquals(List.of(
                ms(10), ms(20), ms(45), ms(50), ms(60), ms(70), ms(80), ms(90), ms(100), ms(110)),
                visited.stream().map(m -> m.since(Moment.ORIGIN)).toList());

        assertEquals(1, events.stream().filter(e -> e.kind() == Overrun.Kind.SKIPPED).count());
    }

    @Test
    @DisplayName("maxSlip writes the debt off in exactly one reported resync, not a storm")
    void maxSlipResyncsOnce() {
        List<Overrun> events = new ArrayList<>();
        List<Moment> visited = new ArrayList<>();
        List<Dur> curve = slipCurve(Settlement.SLIP, Repayment.none(), ms(20), ms(50), events,
                new ManualWall(), visited);

        List<Overrun> resyncs = events.stream()
                .filter(e -> e.kind() == Overrun.Kind.RESYNC)
                .toList();
        assertEquals(1, resyncs.size(), "one crossing must produce one resync: " + events);
        assertEquals(ms(40), resyncs.get(0).amount(), "the whole debt is written off");
        assertEquals(ms(0), resyncs.get(0).slipAfter());

        // Zero afterwards, so the threshold cannot re-trigger without a fresh stall.
        assertEquals(List.of(ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0), ms(0)),
                curve);
    }

    @Test
    @DisplayName("a missed deadline is reported with its shortfall")
    void lateIsReported() {
        List<Overrun> events = new ArrayList<>();
        slipCurve(Settlement.SLIP, Repayment.none(), null, ms(25), events, new ManualWall(),
                new ArrayList<>());

        List<Overrun> late = events.stream().filter(e -> e.kind() == Overrun.Kind.LATE).toList();
        assertEquals(1, late.size());
        assertEquals(ms(15), late.get(0).amount());
        assertEquals(ms(15), late.get(0).slipAfter());
        assertEquals(Settlement.SLIP, late.get(0).settlement());
    }

    @Test
    @DisplayName("repayment shortens the wait it is paid out of")
    void repaymentComesOutOfHeadroom() {
        ManualWall wall = new ManualWall();
        slipCurve(Settlement.SLIP, Repayment.atMost(ms(2)), null, ms(25), new ArrayList<>(), wall,
                new ArrayList<>());

        // waits[0] is moment 0 — the spork itself, which is due immediately. Then the first two frames
        // wait a full 10 ms, and after the stall each frame waits 8 ms instead: the missing 2 ms is
        // the debt being paid down. That is what "repaying out of headroom" means.
        List<Dur> waits = wall.waitsAsDurs();
        assertEquals(Dur.ZERO, waits.get(0));
        assertEquals(ms(10), waits.get(1));
        assertEquals(ms(10), waits.get(2));
        assertEquals(ms(8), waits.get(3));
        assertEquals(ms(8), waits.get(4));
    }

    @Test
    @DisplayName("slack is the wall-clock budget before the next deadline")
    void slackReportsTheBudget() {
        ManualWall wall = new ManualWall();
        List<Dur> slack = new ArrayList<>();
        try (Kron kron = Kron.of(Clock.realtime(wall))) {
            kron.spork("watched", () -> {
                Metro metro = Metro.of(ms(10));            // 10, 20, 30 …
                for (int i = 0; i < 3; i++) {
                    metro.tick();
                    slack.add(kron.slack());
                }
            });
            kron.spork("interleaved", () -> {
                Time.advance(ms(5));
                Metro metro = Metro.of(ms(10));            // 15, 25, 35 …
                for (int i = 0; i < 4; i++) {
                    metro.tick();
                }
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(60)));
        }
        // While `watched` runs at 10 ms, the next thing due is `interleaved` at 15 ms: 5 ms of budget.
        assertEquals(List.of(ms(5), ms(5), ms(5)), slack);
    }

    @Test
    @DisplayName("slack is FOREVER when nothing has declared a next deadline")
    void slackIsUnknownWithoutADeclaredDeadline() {
        ManualWall wall = new ManualWall();
        List<Dur> slack = new ArrayList<>();
        try (Kron kron = Kron.of(Clock.realtime(wall))) {
            kron.spork(() -> {
                Metro metro = Metro.of(ms(10));
                for (int i = 0; i < 3; i++) {
                    metro.tick();
                    slack.add(kron.slack());
                }
            });
            kron.run();
        }
        // A lone periodic shred declares its next wake only at the *end* of its segment, so while the
        // segment runs the kernel genuinely does not know when it next intends to be woken. Reporting
        // FOREVER is the truth; rate domains (M3) declare a period up front and fix it.
        assertEquals(List.of(Dur.FOREVER, Dur.FOREVER, Dur.FOREVER), slack);
    }

    @Test
    @DisplayName("an unpaced clock has no slip and unbounded slack")
    void virtualClockHasNoSlip() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> Time.advance(ms(1)));
            kron.run();
            assertEquals(Dur.ZERO, kron.slip());
            assertEquals(Dur.FOREVER, kron.slack());
        }
    }
}
