# M9 — gliding tempo

**Question.** Under a constant tempo a grid line is one exact ratio applied to one `long`. Under a glide it is a
composed map: the parent's map, then the glide's integral, and in both directions a fix-up that checks the answer
against the exact forward map. A linear glide does that in `BigInteger`; an exponential one with `expm1` and
`log1p`. At audio rates a fixed domain places one grid line per sample. Is that affordable?

**Gate, set before measuring.** Under a glide, one conversion must cost under 10 % of a 48 kHz sample period
(2 083 ns), and a whole 48 kHz step in a gliding tempo must cost under 2× the same step in a constant tempo.

**Answer.** Both pass, after three changes the first measurement forced and one bug in the harness.

Measured on the JVM (Java HotSpot, Oracle GraalVM 25), Windows 11. `TempoBench`: conversions are the min and p50
of ten 150 ms iterations after five warmup iterations; steps are the best of five one-second runs after three,
divided by the steps actually taken.

## Results

| | `globalAt` p50 | `elapsedAt` p50 | ns per 48 kHz step | vs constant |
|---|---:|---:|---:|---:|
| constant 1:1 (reference only) | — | — | 509 | — |
| **constant 3:2** (the baseline) | 7 | 2 | 1 016 | 1× |
| **linear glide** 1 → 3:2 | 165 | 75 | 1 775 | **1.75×** |
| **exponential glide** 1 → 3:2 | 19 | 7 | 1 407 | **1.38×** |

A linear glide's conversion is about 8 % of a sample period; an exponential one's is about 1 %.

## What the first measurement said, and what changed

The first run failed the step gate for linear glides at 3.44×, with `globalAt` at 560 ns. Three changes,
none of which touches exactness, because the fix-up still checks every answer against the exact forward map:

1. **The inverse's candidate moved from `BigInteger` to `double`.** It took a `BigInteger` square root and
   divisions on numbers of about 120 bits, only to produce a *candidate* that the fix-up checks anyway. The
   quadratic formula in `double`, in its cancellation-free form, lands within a nanosecond (`GlideTest` asserts
   it) at a quarter of the cost: 560 → 165 ns.
2. **A step stopped converting its grid line twice.** `Rate` worked the line out to wait for it and again to
   build the `Step`. It now keeps the first.
3. **The composed piece's last value is cached.** `globalAt` decides which piece a target falls in by comparing
   it with the local time just before the knot. That value is fixed when the plan is built, but it was being
   recomputed through the whole composed map on every call.

## A harness bug worth recording

The first version divided every run by 48 000 steps. A 3:2 tempo takes **72 000** steps in a wall second, so the
baseline looked cheaper than it was by exactly the tempo's ratio, and the glides looked 1.5× worse than they
were. It is now divided by the steps actually taken. The lesson is M0's again: a number can be precise and
measure the wrong thing.

## Not explained, and not M9's

A constant 3:2 step costs about twice a 1:1 step, and that was already true before M9. The same probe, run
against the commit before any of this work, gave 664 ns for 1:1, 958 ns for 3:2 and 1 348 ns for 7:5. The
`Ratio` arithmetic itself is a few nanoseconds (the table above), so the gap is somewhere else in the kernel's
path for a step whose moment is not a round number. It is worth finding, but it is a property of any scaled tempo,
not of glides.

Run-to-run noise on the step figures is large, about ±30 % between separate runs of the same probe. The ratios in
the table come from one run, where all four cases share the same conditions. 1.75× is inside the gate but not by
a wide margin, so a bad run could cross it.

**Not measured:** native-image. M0 found the two within about 20 % of each other for a baton handoff (489 ns on the
JVM, 577 ns native), but this benchmark has not been run there.

## Reproduce

```bash
mvn -q -B install -DskipTests
java -cp "kronometer-core/target/classes;kronometer-bench/target/classes;$HOME/.m2/repository/sibarum/atchung/atchung-probe/1.1-SNAPSHOT/atchung-probe-1.1-SNAPSHOT.jar" sibarum.kronometer.bench.TempoBench
```

From Git Bash, set `MSYS_NO_PATHCONV=1` first, or the `;`-separated class path is rewritten and the probe classes
go missing.
