package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.kronometer.Dur.hz;
import static sibarum.kronometer.Dur.min;
import static sibarum.kronometer.Dur.ms;

/**
 * Rate domains: independent sampling grids over one logical timeline.
 */
class RateDomainTest {

    private final List<String> log = new ArrayList<>();

    @Test
    @DisplayName("a fixed domain runs an exact number of steps over a long run")
    void fixedDomainStepCountIsExact() {
        try (Kron kron = Kron.virtual()) {
            Rate physics = kron.fixed(hz(50));
            AtomicLong steps = new AtomicLong();
            physics.each(step -> steps.incrementAndGet());

            kron.runUntil(Moment.ORIGIN.plus(min(10)));

            // 10 minutes at 50 Hz is 30 000 steps. Not "about" 30 000 — exactly, because the grid is
            // computed from the origin and logical time has no error to accumulate.
            assertEquals(30_000, steps.get());
            assertEquals(30_000, physics.steps());
        }
    }

    @Test
    @DisplayName("Rule 4: domains waking at the same moment run in priority order, not spork order")
    void rule4_domainPriorityBeatsSporkOrder() {
        try (Kron kron = Kron.virtual()) {
            // graphics is sporked *first*, so it has the lower sequence number and rule 1 alone would
            // run it first. Priority is what overrides that.
            Rate graphics = kron.fixed("graphics", ms(10)).priority(1);
            Rate physics = kron.fixed("physics", ms(10)).priority(0);

            graphics.each(step -> log.add("graphics@" + step.at()));
            physics.each(step -> log.add("physics@" + step.at()));

            kron.runUntil(Moment.ORIGIN.plus(ms(20)));
        }
        assertEquals(List.of(
                "physics@@10ms", "graphics@@10ms",
                "physics@@20ms", "graphics@@20ms"), log);
    }

    @Test
    @DisplayName("maxCatchUp replays the most recent missed steps and drops the stale ones")
    void maxCatchUpBoundsTheReplay() {
        List<Step> steps = new ArrayList<>();
        ManualWall wall = new ManualWall();
        try (Kron kron = Kron.of(Clock.realtime(wall).settlement(Settlement.SKIP))) {
            Rate physics = kron.fixed(ms(10)).maxCatchUp(2);
            physics.each(step -> {
                steps.add(step);
                if (step.index() == 2) {
                    wall.advance(ms(55));       // a stall spanning five and a half grid lines
                }
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(80)));
        }

        // Grid lines 4 and 5 are dropped; 6 and 7 are replayed back to back at zero logical cost.
        // Running the *most recent* owed steps and discarding the stale ones is the right choice: a
        // simulation wants to be current, not complete.
        assertEquals(List.of(1L, 2L, 3L, 6L, 7L, 8L), steps.stream().map(Step::index).toList());
        assertEquals(List.of(0L, 0L, 0L, 2L, 0L, 0L), steps.stream().map(Step::skipped).toList());
    }

    @Test
    @DisplayName("maxCatchUp(0) drops everything owed — SKIP, for one domain")
    void maxCatchUpZeroDropsEverything() {
        List<Step> steps = new ArrayList<>();
        ManualWall wall = new ManualWall();
        try (Kron kron = Kron.of(Clock.realtime(wall).settlement(Settlement.SKIP))) {
            Rate physics = kron.fixed(ms(10)).maxCatchUp(0);
            physics.each(step -> {
                steps.add(step);
                if (step.index() == 2) {
                    wall.advance(ms(55));
                }
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(80)));
        }
        assertEquals(List.of(1L, 2L, 3L, 8L), steps.stream().map(Step::index).toList());
        assertEquals(4L, steps.get(3).skipped());
    }

    @Test
    @DisplayName("a dynamic domain steps once per tick, with the tick's own dt")
    void dynamicDomainFollowsTheTickStream() {
        List<Step> steps = new ArrayList<>();
        try (Kron kron = Kron.driven()) {
            kron.dynamic("frames").each(steps::add);

            kron.tick(ms(16).nanos());
            kron.tick(ms(33).nanos());
            kron.tick(ms(50).nanos());
        }
        assertEquals(List.of(ms(16), ms(17), ms(17)), steps.stream().map(Step::dt).toList());
        assertEquals(List.of(ms(16), ms(33), ms(50)),
                steps.stream().map(s -> s.at().since(Moment.ORIGIN)).toList());
    }

    @Test
    @DisplayName("a dynamic domain steps after everything else in its window")
    void dynamicDomainStepsLast() {
        try (Kron kron = Kron.driven()) {
            kron.fixed("physics", ms(10)).priority(0).each(s -> log.add("physics@" + s.at()));
            kron.dynamic("frames").priority(1).each(s -> log.add("frame@" + s.at()));

            kron.tick(ms(25).nanos());
        }
        // The render pass sees what the simulation produced in the same window, not what it produced
        // in the previous one.
        assertEquals(List.of("physics@@10ms", "physics@@20ms", "frame@@25ms"), log);
    }

    @Test
    @DisplayName("slack becomes the real budget once a domain declares a period")
    void slackKnowsTheNextGridLine() {
        List<Dur> slack = new ArrayList<>();
        ManualWall wall = new ManualWall();
        try (Kron kron = Kron.of(Clock.realtime(wall))) {
            Rate frames = kron.fixed(ms(10));
            frames.each(step -> {
                if (step.index() <= 3) {
                    slack.add(kron.slack());
                }
            });
            kron.runUntil(Moment.ORIGIN.plus(ms(50)));
        }
        // This is what M2 could not answer: a lone periodic shred read FOREVER, because it had not yet
        // said when it wanted waking. A domain says so up front.
        assertEquals(List.of(ms(10), ms(10), ms(10)), slack);
    }

    @Test
    @DisplayName("domains cannot diverge, because there is only one clock to diverge from")
    void domainsShareOneTimeline() {
        ManualWall wall = new ManualWall();
        List<String> observed = new ArrayList<>();
        try (Kron kron = Kron.of(Clock.realtime(wall).repayment(Repayment.none()))) {
            Rate fast = kron.fixed("fast", ms(10)).priority(0);
            Rate slow = kron.fixed("slow", ms(30)).priority(1);

            fast.each(step -> {
                if (step.index() == 2) {
                    wall.advance(ms(45));       // stall one domain's segment
                }
            });
            slow.each(step -> observed.add(step.at() + "/slip=" + kron.slip()));

            kron.runUntil(Moment.ORIGIN.plus(ms(90)));
        }
        // The stall is carried by the timeline, so both domains see the same slip and the same
        // moments. Independent per-domain slip would be A/V desync; one clock makes it unreachable.
        assertEquals(List.of("@30ms/slip=35ms", "@60ms/slip=35ms", "@90ms/slip=35ms"), observed);
    }

    @Test
    @DisplayName("a domain must be configured before it starts")
    void configurationAfterStartIsRefused() {
        try (Kron kron = Kron.virtual()) {
            Rate rate = kron.fixed(ms(10));
            rate.each(step -> { });
            assertThrows(IllegalStateException.class, () -> rate.priority(5));
            // each() is deliberately *not* in this list — see multipleHandlersRunInRegistrationOrder.
            assertThrows(IllegalStateException.class, () -> rate.maxCatchUp(1));
            assertThrows(IllegalStateException.class, () -> rate.lookahead(ms(1)));
        }
    }

    @Test
    @DisplayName("a domain runs every registered handler, in registration order")
    void multipleHandlersRunInRegistrationOrder() {
        try (Kron kron = Kron.virtual()) {
            Rate frames = kron.fixed(ms(10));
            // Registration order is the whole ordering contract for handlers on one domain, and it is
            // what makes input composable: a bridge that delivers bus events registers first, so the
            // effects that read them see this step's input rather than the previous step's (M7).
            frames.each(step -> log.add("drain@" + step.at()));
            frames.each(step -> log.add("render@" + step.at()));

            kron.runUntil(Moment.ORIGIN.plus(ms(20)));
        }
        assertEquals(List.of(
                "drain@@10ms", "render@@10ms", "drain@@20ms", "render@@20ms"), log);
    }

    @Test
    @DisplayName("degrade needs a fixed grid to degrade")
    void degradeRequiresAFixedDomain() {
        try (Kron kron = Kron.driven()) {
            assertThrows(IllegalStateException.class, () -> kron.dynamic().degrade(ms(10), ms(20)));
        }
    }
}
