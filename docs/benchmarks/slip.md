# M2 — realtime pacing, and what M0's outlier actually was

**Question.** M0 measured a tight baton — p99 of 680 ns — with one maximum round trip of 117 µs over
200 000 samples, and deferred the question to M2 on the grounds that the slip model exists to absorb
exactly such things. So: what is that outlier, and does it matter?

**Answer.** It does not matter, because it is not the limiting factor and never was. **Pacing jitter
from the platform timer is four to ten times larger than the worst thing the baton ever did.** The
question was aimed at the wrong subsystem, which is a useful thing to learn about one's own
instincts.

Measured on Oracle GraalVM 25.0.3, Windows 11, 20 cores, default carriers. `SlipBench` runs a paced
`Metro` and reports two things that are easy to conflate:

- **Jitter** — how far each frame's wall arrival strays from one period after the last. Transient.
- **Slip** — how far the schedule as a whole has fallen behind. Carried.

A jitter spike the kernel recovers from never becomes slip. One it cannot recover from does. Only the
second is a failure of pacing.

## The spin tail is the whole story

`SystemWall.parkUntil` parks until shortly before the deadline, then spins the rest — because
`LockSupport.parkNanos` is only as precise as the platform timer, and on Windows that is around a
millisecond. A tail *shorter* than the overshoot is never reached at all: the park blows straight past
it and the frame arrives late by the difference.

| spin tail | 60 Hz p50 | 60 Hz p90 | 60 Hz p99 | 1 kHz p50 | 1 kHz slip after 5 000 frames |
|---|---:|---:|---:|---:|---:|
| 500 µs | 402 µs | 1 112 µs | 1 804 µs | 676 µs | **11.4 ms** (792 late frames) |
| **1.5 ms** *(default)* | **11 µs** | 413 µs | 661 µs | **200 ns** | 0 |
| 3 ms | 5.7 µs | 32 µs | 113 µs | 200 ns | 0 |

Three things worth reading off that table:

1. **The first row was the original default, and it was wrong by 40×.** 500 µs is less than Windows'
   park overshoot, so essentially every frame missed. At 1 kHz — where the period is itself shorter
   than the timer granularity — that turned into 11.4 ms of *real accumulated slip*: the pacing was
   the cause of the debt, not the workload.
2. **1.5 ms fixes the median completely** (402 µs → 11 µs at 60 Hz, 676 µs → 200 ns at 1 kHz) and
   eliminates the 1 kHz slip. It costs about 9 % of one core at 60 Hz.
3. **3 ms buys the tail, not the median** — p99 improves 6× (661 → 113 µs) while p50 barely moves —
   and costs 18 % of a core. That is the right trade for audio and the wrong one for a battery.

1.5 ms is the default because it is where the median stops being the problem. Precision-critical work
should raise it explicitly:

```java
Clock.realtime(Wall.system(Dur.ms(3)))
```

## So what was the 117 µs?

Beneath the noise floor of the thing it was competing with. At the default tail, 60 Hz p90 jitter is
413 µs — three and a half times M0's worst-ever baton event — and even at a 3 ms tail the p99 is
113 µs, right at it. The baton is not what makes a realtime run miss its deadline; the platform timer
is.

The honest generalisation: **M0 measured the part that was easy to measure.** A 200 000-sample
distribution of a microbenchmark looked authoritative, and it was — about the wrong quantity. Nothing
in it was wrong; it simply was not the budget that binds.

## Settlement, end to end

At the default tail, over 300 frames at 60 Hz and 5 000 at 1 kHz, an unloaded machine produces one
late frame and one repayment, and finishes with zero slip under every policy. The scripted-overrun
tests in `SlipModelTest` are where the policies are actually pinned down — exactly, with no wall clock
involved at all — and this run only confirms that the real wall behaves like the scripted one.

## Reproducing

```bash
mvn -q -B install -DskipTests
java -cp "kronometer-core/target/classes;kronometer-bench/target/classes" sibarum.kronometer.bench.SlipBench
```

`--spin=<microseconds>` overrides the spin tail, which is how the table above was produced.
