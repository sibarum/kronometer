# M9 — gliding tempo

**Question.** Under a constant tempo a grid line is one exact ratio applied to one `long`. Under a glide it is a
composed map: the parent's map, then the glide's integral, and in both directions a fix-up that checks the answer
against the exact forward map. A linear glide does that in `BigInteger`; an exponential one with `expm1` and
`log1p`. At audio rates a fixed domain places one grid line per sample. Is that affordable?

**Gate, set before measuring.** Under a glide, one conversion must cost under 10 % of a 48 kHz sample period
(2 083 ns), and a whole 48 kHz step in a gliding tempo must cost under 2× the same step in a constant tempo.

**Answer.** Both pass, after three changes the first measurement forced and two bugs in the harness.

Measured on the JVM (Java HotSpot, Oracle GraalVM 25), Windows 11, Intel Core Ultra 7 255HX. `TempoBench`:
conversions are the min and p50 of ten 150 ms iterations after five warmup iterations; steps are the best of five
one-second runs after three, divided by the steps actually taken, with the four step cases run round-robin (see
the second harness bug).

## Results

| | `globalAt` p50 | `elapsedAt` p50 | ns per 48 kHz step | vs constant |
|---|---:|---:|---:|---:|
| constant 1:1 (reference only) | — | — | 926 | — |
| **constant 3:2** (the baseline) | 7 | 2 | 1 021 | 1× |
| **linear glide** 1 → 3:2 | 158 | 80 | 1 799 | **1.76×** |
| **exponential glide** 1 → 3:2 | 19 | 7 | 1 262 | **1.24×** |

A linear glide's conversion is about 8 % of a sample period; an exponential one's is about 1 %.

With the kernel's carrier pinned to one thread, as M0 recommends (`-Djdk.virtualThreadScheduler.parallelism=1
-Djdk.virtualThreadScheduler.maxPoolSize=1`), every step is about 2.5× cheaper and the linear glide's *ratio*
rises, because its extra conversions are a fixed ~375 ns on a smaller base: 1:1 434, 3:2 447, linear 823
(**1.84×**), exponential 489 (1.09×). Still inside the gate, by less.

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

## A second harness bug: whichever case ran first was cheap

The first version of this page reported a constant 3:2 step at about twice a 1:1 step (1 016 against 509 ns), and
the same probe against the commit before M9 gave 664 ns for 1:1, 958 for 3:2 and 1 348 for 7:5. It looked like a
cost of any scaled tempo. **It was not the tempo; it was the order.** The bench measured 1:1 first, to
completion, then 3:2, then the glides.

On this JVM (GraalVM's JIT, default virtual-thread scheduler, so the kernel and its shreds float across 20
carriers), the first several wall seconds of baton handoffs run at about 500 ns per step and then settle at about
1 200–1 400 ns, whatever the tempo. Idle gaps of four seconds between runs do not bring the fast window back, so
it is not thermal or turbo budget; it is JVM state. Whichever case is measured first gets that window to itself.
Each row is a fresh JVM, one wall second of a 48 kHz domain per run, best of five after three:

| order run in one JVM | first | second | third |
|---|---:|---:|---:|
| 1:1, then 3:2 | 1:1 **548** | 3:2 1 287 | |
| 3:2, then 1:1 | 3:2 **553** | 1:1 1 214 | |
| 7:5, 1:1, 7:5 | 7:5 **578** | 1:1 1 262 | 7:5 1 279 |
| 1:1 three times | 1:1 **537** | 1:1 1 394 | 1:1 1 455 |

The kernel does the same work per step in every tempo. A trace of 10 ms shows one resume and one await per step
for 1:1, 3:2 and 7:5 alike (481, 721 and 673 of each, for 480, 720 and 672 steps), so a moment that is not a round
number costs no extra wake-up. Run round-robin, with the order rotated every round so that every case sees both
sides of the change, the three cost the same:

| best of rounds 5–12, round-robin | 1:1 | 3:2 | 7:5 |
|---|---:|---:|---:|
| default JVM | 1 221 | 1 262 | 1 262 |
| pinned carrier, C2 (`-XX:-UseJVMCICompiler`) | 349 | 357 | 362 |

With C2 instead of Graal there is no fast window at all: unpinned it is a steady 1 100–1 200 ns from the second
run on. `TempoBench` now runs its four step cases round-robin. Before and after, on this machine:

| ns per 48 kHz step | 1:1 | 3:2 | 3:2 ÷ 1:1 |
|---|---:|---:|---:|
| before, one case after another (this page's first table) | 509 | 1 016 | 2.00 |
| after, round-robin, run 1 | 926 | 1 021 | 1.10 |
| after, round-robin, run 2 | 1 317 | 1 340 | 1.02 |
| after, round-robin, pinned carrier | 434 | 447 | 1.03 |

The glide ratios barely move (1.75× before; 1.54×, 1.76× and 1.84× in the three runs after), because the glides
and 3:2 were always measured after the switch. The number that was wrong was the 1:1 reference.

**Why Graal's fast window ends is not explained here.** The JIT log shows OSR recompilations of `Kron.loop`,
`Rate`'s driver loop and `ForkJoinPool.runWorker` (one of them an uncommon trap) around the switch, which fits,
but nothing was isolated. It matters beyond this bench: a figure measured in the first seconds of a GraalVM JVM
with an unpinned carrier may be the fast window, so compare cases round-robin, never one after another.

Run-to-run noise on the step figures is still large, about ±30 % between separate runs. The ratios in the first
table come from one run, where all four cases share the same conditions. Pinned, the linear glide reads 1.84×,
inside the gate but not by a wide margin, so a bad run could cross it.

**Not measured:** native-image. M0 found the two within about 20 % of each other for a baton handoff (489 ns on the
JVM, 577 ns native), but this benchmark has not been run there.

## Reproduce

```bash
mvn -q -B install -DskipTests
java -cp "kronometer-core/target/classes;kronometer-bench/target/classes;$(cygpath -m ~)/.m2/repository/sibarum/atchung/atchung-probe/1.1-SNAPSHOT/atchung-probe-1.1-SNAPSHOT.jar" sibarum.kronometer.bench.TempoBench
```

From Git Bash, set `MSYS_NO_PATHCONV=1` for the `java` line, or the `;`-separated class path is rewritten and the
probe classes go missing. Not for `mvn`, whose launcher then cannot find itself. Write the repository path in
Windows form, as `cygpath -m` does above: `$HOME` alone is `/c/Users/...`, which Java cannot open.

For the pinned figures, add `-Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1`
to the `java` line.
