package sibarum.kronometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.kronometer.Dur.hz;
import static sibarum.kronometer.Dur.ms;
import static sibarum.kronometer.Dur.ns;
import static sibarum.kronometer.Dur.s;
import static sibarum.kronometer.Dur.us;
import static sibarum.kronometer.Time.advance;
import static sibarum.kronometer.Time.now;
import static sibarum.kronometer.Time.sync;
import static sibarum.kronometer.Time.until;

/**
 * Exactness is the whole claim. These assert on equality, never on tolerance — logical time has no
 * error to tolerate, and a test written with an epsilon would quietly permit the drift this design
 * exists to eliminate.
 */
class TimeIntrinsicsTest {

    @Test
    @DisplayName("a hundred thousand advances land exactly, with no accumulated error")
    void advancesDoNotAccumulateError() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                for (int i = 0; i < 100_000; i++) {
                    advance(us(333));       // deliberately not a round number of milliseconds
                }
            });
            kron.run();
            assertEquals(new Moment(100_000L * 333_000L), kron.now());
        }
    }

    @Test
    @DisplayName("a million Metro ticks land exactly a million periods after the origin")
    void metroIsDriftFreeOverAMillionPeriods() {
        try (Kron kron = Kron.virtual()) {
            AtomicLong skipped = new AtomicLong();
            kron.spork(() -> {
                Metro metro = Metro.hz(60);
                for (int i = 0; i < 1_000_000; i++) {
                    skipped.addAndGet(metro.tick());
                }
                assertEquals(metro.origin().plus(metro.period().times(1_000_000L)), now());
            });
            kron.run();
            assertEquals(0, skipped.get(), "the virtual clock cannot fall behind, so nothing is skipped");
            assertEquals(new Moment(1_000_000L * Dur.hz(60).nanos()), kron.now());
        }
    }

    @Test
    @DisplayName("sync quantizes to the grid, from wherever it is called")
    void syncQuantizesToTheGrid() {
        try (Kron kron = Kron.virtual()) {
            AtomicReference<String> landings = new AtomicReference<>("");
            kron.spork(() -> {
                advance(ms(137));
                sync(ms(100));
                landings.set(now().toString());
                sync(ms(100));
                landings.set(landings.get() + " " + now());
            });
            kron.run();
            assertEquals("@200ms @300ms", landings.get());
        }
    }

    @Test
    @DisplayName("sync on a grid line advances to the next one, so a sync loop cannot spin")
    void syncIsStrictlyForward() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> {
                for (int i = 0; i < 5; i++) {
                    sync(ms(10));
                }
            });
            kron.run();
            assertEquals(Moment.ORIGIN.plus(ms(50)), kron.now());
        }
    }

    @Test
    @DisplayName("advance(ZERO) yields without letting time pass")
    void zeroAdvanceIsAYield() {
        try (Kron kron = Kron.virtual()) {
            kron.spork(() -> advance(Dur.ZERO));
            kron.run();
            assertEquals(Moment.ORIGIN, kron.now());
        }
    }

    @Test
    @DisplayName("time only moves forward")
    void timeCannotGoBackwards() {
        try (Kron kron = Kron.virtual()) {
            AtomicReference<Class<?>> negative = new AtomicReference<>();
            AtomicReference<Class<?>> past = new AtomicReference<>();
            kron.spork(() -> {
                advance(ms(10));
                try {
                    advance(ms(-1));
                } catch (RuntimeException e) {
                    negative.set(e.getClass());
                }
                try {
                    until(Moment.ORIGIN);
                } catch (RuntimeException e) {
                    past.set(e.getClass());
                }
            });
            kron.run();
            assertEquals(IllegalArgumentException.class, negative.get());
            assertEquals(IllegalArgumentException.class, past.get());
        }
    }

    @Test
    @DisplayName("the intrinsics refuse to work off the timeline")
    void intrinsicsRequireAShred() {
        assertThrows(Failures.NotOnTimeline.class, Time::now);
        assertThrows(Failures.NotOnTimeline.class, () -> advance(ms(1)));
        assertThrows(Failures.NotOnTimeline.class, Time::self);
    }

    @Test
    @DisplayName("join() from inside a shred is refused — it would hold the baton")
    void joinFromOnTimelineIsRefused() {
        try (Kron kron = Kron.virtual()) {
            AtomicReference<Class<?>> thrown = new AtomicReference<>();
            kron.spork("parent", () -> {
                Shred child = Time.spork("child", () -> advance(ms(1)));
                try {
                    child.join();
                } catch (Exception e) {
                    thrown.set(e.getClass());
                }
            });
            kron.run();
            assertEquals(Failures.NotOnTimeline.class, thrown.get());
        }
    }

    @Test
    @DisplayName("await(shred.done()) is how one shred waits for another")
    void doneTriggerJoinsOnTheTimeline() {
        try (Kron kron = Kron.virtual()) {
            AtomicReference<Moment> resumedAt = new AtomicReference<>();
            kron.spork("parent", () -> {
                Shred child = Time.spork("child", () -> advance(ms(40)));
                Time.await(child.done());
                resumedAt.set(now());
            });
            kron.run();
            assertEquals(Moment.ORIGIN.plus(ms(40)), resumedAt.get());
        }
    }

    @Test
    @DisplayName("Dur arithmetic and rendering")
    void durValueSemantics() {
        assertEquals(ns(1_500_000), ms(1).plus(us(500)));
        assertEquals(ms(2), ms(1).times(2));
        assertEquals(ns(16_666_667), hz(60));
        assertEquals(3, s(1).dividedBy(ms(300)));
        assertEquals("1.5ms", ns(1_500_000).toString());
        assertEquals("-250ms", ms(-250).toString());
        assertEquals("0s", Dur.ZERO.toString());
        assertEquals(Dur.ZERO, ms(5).minus(ms(5)));
        assertEquals(ms(-5), ms(5).minus(ms(10)));
    }

    @Test
    @DisplayName("Moment arithmetic")
    void momentValueSemantics() {
        Moment at = Moment.ORIGIN.plus(s(2));
        assertEquals(ms(1_500), at.since(Moment.ORIGIN.plus(ms(500))));
        assertEquals(ms(-500), Moment.ORIGIN.plus(ms(500)).since(Moment.ORIGIN.plus(s(1))));
        assertEquals("@2s", at.toString());
        assertEquals(true, Moment.ORIGIN.isBefore(at));
    }
}
