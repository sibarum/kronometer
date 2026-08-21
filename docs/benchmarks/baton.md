# M0 — the baton spike

**Question.** Kronometer's effectful model is one kernel thread handing a baton to one shred at a
time — a park/unpark pair per shred per moment. Architecture §2–§4 assume that is cheap. Is it?

**Gate.** Above ~5 µs per handoff we would have had to redesign, most likely by batching
non-conflicting segments off the baton.

**Answer.** 577 ns per handoff under native-image, 489 ns on the JVM — about 9× inside the gate. The
design stands. But the naive configuration measured **15 497 ns**, so getting there took two
decisions that are now part of the kernel's definition rather than tuning knobs.

Measured on Oracle GraalVM 25.0.3, Windows 11, 20 cores. Harness: `kronometer-bench`, no JMH, so the
same classes run on the JVM and as a native image. Figures are ns per handoff, `min` of ten 150 ms
iterations after five warmup iterations, from the frame simulation at n=1000 (a timeline priority
queue plus a real baton pass — the realistic shape).

## The two decisions

| Configuration | ns/handoff | vs. naive |
|---|---:|---:|
| Platform kernel thread, default carriers, `PARK` | 15 497 | 1× |
| **Virtual** kernel thread, default carriers, `PARK` | 1 493 | 10× |
| **Virtual** kernel thread, **1 carrier**, `PARK` | **489** | **32×** |
| Virtual kernel thread, 1 carrier, `SPIN_PARK` | 6 076 | 0.4× |

### 1. The kernel loop runs on a virtual thread

This is the big one: 15 497 → 1 493 ns, a 10× swing, for a change that looks cosmetic.

A platform kernel thread means every single handoff crosses between the OS scheduler and the
virtual-thread scheduler. The kernel unparks a virtual thread, which submits a task to the
ForkJoinPool, which must wake an idle carrier — a full OS thread wakeup — which then mounts the
shred; the shred replies by unparking the kernel's platform thread, a second OS wakeup. Two kernel
transitions per baton pass, and on Windows an OS thread wakeup is roughly 4 µs on its own.

Put the kernel loop on a virtual thread and the whole exchange stays inside the ForkJoinPool.

> **The kernel thread's type is not an implementation detail. Never cross the scheduler boundary on
> the hot path.**

### 2. One carrier

Another 3×, 1 493 → 489 ns, and the reasoning is the invariant itself: **the baton already
serializes everything, so additional carriers cannot add parallelism — only cross-core wakeups and
cache traffic.** Pinning to a single carrier keeps the handoff on one hot core.

```
-Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1
```

This has consequences beyond speed, recorded in §3 of the architecture: the precompute pool and
`offload()` must run on **their own** executors, never the kernel's carrier, or they will deadlock
against the very serialization that makes this fast.

### 3. Spin-then-park is a trap

`SPIN_PARK` measures **96 ns** in ping-pong and looks like a 5× win. It is an artifact. In ping-pong
the same shred is re-woken immediately, so it is still inside its spin window and both sides stay
hot. With any realistic number of shreds, the one being woken has been parked for the whole of the
previous moment, so its wakeup costs a full park/unpark regardless — only the kernel's side benefits,
and the measured gain at n≥10 collapses to nothing.

On a single carrier it is actively catastrophic — **6 076 ns, 12× worse** — because a spinning
virtual thread holds its carrier, and with one carrier the party it is waiting for cannot possibly be
mounted to answer. The spin is guaranteed to be wasted, every time.

Kept in the harness as a documented negative result, because it is exactly the optimization someone
will propose again in six months.

### 4. `PARK` vs `SEMAPHORE`

Within noise of each other (`SEMAPHORE` 1 312 vs `PARK` 1 241 at n=1000, default carriers).
`ParkGate` wins on being allocation-free and having no lock to contend, so the kernel uses it, but
this was not the decisive axis — the configuration above was.

## What it costs, and what to spend it on

At n=1000, native-image: 577 ns per handoff, of which 106 ns is the timeline priority queue and the
rest is the baton.

| | shreds per 60 Hz frame |
|---|---:|
| 10 % of the frame budget | **~2 900** |
| 100 % of the frame budget | ~28 900 |

**The published guidance follows from this: anything pure should be a `Signal`, not a `Shred`.** A
tween is a function of time, not a thread — it belongs in the graph (§7), where precomputation (§8)
evaluates it ahead on a worker pool at zero handoff cost. Ten thousand simultaneous animations are
free. Ten thousand *effectful* shreds in one moment would cost 5.8 ms, a third of a frame.

That is a comfortable budget for what shreds are actually for, and it is a real ceiling worth knowing
before someone models every particle as one.

## Native-image vs JVM

| | JVM | native | delta |
|---|---:|---:|---:|
| Handoff, n=1000 | 489 ns | 577 ns | +18 % |
| Timeline queue alone | 58 ns | 106 ns | +83 % |
| Ping-pong round trip | 372 ns | 387 ns | +4 % |

No surprises: the handoff itself is nearly identical, and the gap is almost entirely the priority
queue, where the JVM's profile-guided inlining wins and native-image was built without `--pgo` or
`-march=native`. Both are well inside the gate. Build time 23 s.

## One thing to watch

The tail is tight through p99 — 680 ns native, 482 ns on the JVM — but the **maximum** round trip
over 200 000 samples was 117 µs native and 239 µs on the JVM. That is seven frames' worth of outlier,
almost certainly a GC pause or an OS scheduling event rather than the baton.

It is not a problem for M0 and does not change any decision, but it is precisely the phenomenon the
slip model (§5.1) exists to absorb, so it is worth re-measuring at M2 with the settlement policies in
place — and worth remembering that a p99 figure would have hidden it entirely.

## Reproducing

```bash
mvn -q -B compile
java -cp kronometer-bench/target/classes \
     -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1 \
     sibarum.kronometer.bench.BatonBench --kernel=virtual
```

Flags: `--kernel=virtual|platform`, `--gate=PARK|SEMAPHORE|SPIN_PARK`, `--ping-only`, `--fast`.

Native image (note the absolute paths — `-o` resolves against the temp directory otherwise):

```bash
"$JAVA_HOME/bin/native-image.cmd" --no-fallback -cp kronometer-bench/target/classes -o "$(pwd -W)/kronometer-bench/target/baton-bench" sibarum.kronometer.bench.BatonBench
```

On Windows the build may fail once with *"Unable to run …Directives.exe to compute offsets"* — that is
antivirus real-time scanning holding the helper binaries it generates, not a code problem. Re-running
succeeds, because the files have been scanned by then.
