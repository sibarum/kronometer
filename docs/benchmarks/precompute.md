# M5 — precomputation

**Question.** §1 claims pure evaluation is embarrassingly parallel and that reading at `now` becomes a
buffer index. Does evaluating ahead actually buy anything, and where?

**Answer.** **2.3× on a wide window; nothing at all on a narrow one.** And the second half of that
sentence took three wrong measurements to establish, which is the more useful part of the write-up.

Oracle GraalVM 25.0.3, Windows 11, 20 cores. `PredictBench`, best of five after three warm runs, over a
signal costing ~3 µs to evaluate.

| | ns/step, lazy | ns/step, predicted | |
|---|---:|---:|---|
| **Wide window** — 100 ms lookahead at 48 kHz | 5 552 | **2 376** | 2.3× |
| **Narrow window** — two frames at 60 Hz | 6 022 | 6 102 | — |

## Why the narrow window gains nothing, and why that is correct

A sliding window in steady state needs **one new sample per step**, whoever computes it. Topping the
buffer up by one sample at the end of every step is therefore *precisely* the work lazy evaluation
would have done, one sample at a time, on the timeline — with a buffer bolted on for decoration. The
first working version of this did exactly that and measured, to within noise, exactly nothing.

The fix is to refill in **bursts** rather than dribbles: wait until the buffer is half empty, then fill
the whole window in one parallel batch. Most steps then become a pure buffer read and the arithmetic
happens in one go, which is the only shape in which "embarrassingly parallel" cashes out. It is also
what an audio engine does, for the same reason, which is a good sign the shape is right rather than
convenient.

A two-frame window has nothing to burst: half of two is one. So it stays at parity, and parity is the
honest answer — prediction is free there, not useful. Lookahead is what buys the speedup, and a domain
that declares none should not expect any.

## Two other things that had to be fixed before any of this showed up

- **The fill re-walked the whole window every step**, asking "is this one already buffered?" — 4 800 map
  probes per step for a 100 ms audio window, or 11 ms of pure bookkeeping over a 480-step run. That
  alone made prediction *slower* than not predicting. Fixed by tracking the fill frontier.
- **Dispatching one sample to the pool costs more than computing it.** A platform-thread round trip is
  ~14 µs on this machine (M0 measured it); the sample is ~3 µs. Routing a one-sample top-up through the
  pool made prediction three times slower. Batches below sixteen samples now stay on the timeline.

## The measurement that was wrong three times

Worth recording, because the failure mode is the same one M0 fell into and it is apparently not a lesson
one learns permanently.

The first harness ran 480 steps with a single warm pass and reported prediction as **70 % slower**. The
numbers looked plausible — 17 615 ns/step lazy against 18 972 predicted — and they were pure JIT warmup
and setup cost. A separate breakdown of per-step cost by layer settled it:

| | ns/step |
|---|---:|
| Bare shred + `Metro` (the M1 shape) | 520 |
| `Rate` domain, empty handler | 630 |
| `Rate` + effect reading a `computed` | 642 |
| `Rate` + effect + EAGER prediction | 822 |

520 ns matches M1's 511 ns exactly, so the kernel had not regressed at all — the harness was measuring
warmup. Prediction's own bookkeeping costs ~180 ns per step, which is the real overhead figure.

Along the way, a direct concurrency probe confirmed the pool genuinely reaches 19-way parallelism with
all worker threads engaged, and an isolation test showed the pool gives only 2.7× on 480 tasks of 3 µs
each — dispatch overhead, not the pool's fault, and the reason the batch threshold exists.

**The moral, twice learned:** a distribution can look authoritative and still be measuring the wrong
quantity. M0 measured the baton when the platform timer was the binding constraint; this measured
warmup when the question was steady-state throughput. Both times the fix was to isolate the layer
rather than to reason about the aggregate.

## Reproducing

```bash
mvn -q -B install -DskipTests
java -cp "kronometer-core/target/classes;kronometer-bench/target/classes" sibarum.kronometer.bench.PredictBench
```
