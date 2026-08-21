# Kronometer — roadmap

Sequencing principle: **build everything the virtual clock can test before anything it cannot.**
Determinism before pacing, correctness before speed. Milestones M1 and M4 have no wall-clock
dependency at all, which means no flaky tests and no sleeps in CI for the two hardest parts of the
design.

| # | Milestone | Proves | Est. |
|---|---|---|---|
| ~~**M0**~~ | ~~Baton spike + repo skeleton~~ | **done** — 577 ns/handoff native, 9× inside the gate. [Results](benchmarks/baton.md) | — |
| ~~**M1**~~ | ~~Kernel, virtual clock only~~ | **done** — 35 tests green, ten logical minutes in 12.3 ms | — |
| ~~**M2**~~ | ~~Realtime + driven clocks, slip~~ | **done** — 55 tests green; pacing jitter cut 40×. [Results](benchmarks/slip.md) | — |
| ~~**M3**~~ | ~~Rate domains~~ | **done** — 73 tests green; `couple()` deleted, and the design corrected | — |
| ~~**M4**~~ | ~~The signal graph~~ | **done** — 98 tests green; the horizon split in two | — |
| ~~**M5**~~ | ~~Precomputation~~ | **done** — 105 tests green; 2.3× on a wide window, and two real bugs caught by the property test | — |
| **M6** | `kronometer-anim` | easing, interpolation, the closed-form/integrated split (§9) | ~1 week |
| **M7** | `kronometer-atchung` + headless demo | live input enters the graph at `horizon == now` | ~4 days |
| **M8** | First real consumer | vexelray-gui adopts it; tactroller harness lands | — |

Cross-cutting rules, every milestone:

- Tests run under `Clock.virtual()` and contain **no sleeps**. Anything that must touch the wall clock
  takes an injectable nanosecond source instead (see M2).
- [architecture.md](architecture.md) is the source of truth and gets corrected wherever the
  implementation disagreed with it. A milestone is not done while the doc still describes something
  that isn't true.
- No module acquires a dependency it does not need. `kronometer-core` acquires none, ever.

---

## M0 — Baton spike ✔ done

Full results: [benchmarks/baton.md](benchmarks/baton.md). Deliverables: aggregator POM,
`kronometer-core` with the house build config, `kronometer-bench`.

**577 ns per handoff under native-image** (489 ns JVM), against a 5 µs gate. The design stands and §2–§4
need no rework. Two findings were promoted from tuning into architecture (§3.1), because the naive
configuration measured 15 497 ns and only these two decisions closed the 32× gap:

1. **The kernel loop runs on a virtual thread**, so a handoff never crosses between the OS scheduler
   and the virtual-thread scheduler. Worth 10×.
2. **The carrier pool is pinned to one thread**, since the baton serializes everything anyway and
   extra carriers buy only cross-core wakeups. Worth another 3× — and it forces the precompute pool
   and `offload()` onto their own executors, or they deadlock against the kernel's single carrier.

A negative result kept in the harness: spin-then-park looks 5× faster in ping-pong, is an artifact,
and on one carrier is 12× *worse*.

**Published budget:** ~2 900 effectful shreds per 60 Hz frame at 10 % of the frame budget. Hence the
guidance — *anything pure should be a `Signal`, not a `Shred`.*

One thing carried forward: p99 is tight (680 ns) but the max round trip over 200 000 samples was
117 µs. Almost certainly GC or OS scheduling rather than the baton, and exactly what the slip model
exists to absorb — **re-measure at M2** with settlement policies in place.

*Deviation from plan:* no JMH. The harness is hand-rolled and dependency-free so the same classes run
on the JVM and as a native image, which is the comparison that mattered; the cost is a duty of care
(auto-calibration, warmup, distributions rather than means, a subtracted baseline) discharged in
`Bench`.

## M1 — The kernel, virtual clock only ✔ done

`Dur`, `Moment`, `Kron`, `Shred`, `Time`, `Trigger`, `Metro`, `Clock.virtual()`, `Trace`, `Detach`,
`Failures`. 35 tests, all green, none of which sleeps.

Exit criteria, all met:

| Criterion | Result |
|---|---|
| Each §4 ordering rule has a test that fails if broken | rules 1, 2, 3, 5 in `OrderingRulesTest` (rule 4 is M3, with rate domains) |
| 1 000 runs produce byte-identical traces | `DeterminismTest`, over a scenario with nested sporks, a trigger, a losing timeout and a cancellation |
| `finally` runs on the timeline at the cancellation moment | `CancellationTest` |
| A cancelled shred cannot advance again | ✔ — cleanup is bounded by construction |
| Parent cancellation reaches children; `Detach.YES` survives | ✔ |
| Ten logical minutes under 100 ms | **12.3 ms** pinned, 18.4 ms unpinned (342 / 511 ns per handoff) |
| `sync()` and `Metro` drift-free over a million periods | ✔, asserted as exact equality — no epsilon, because logical time has no error to tolerate |

### What the implementation taught us

Three corrections went back into the architecture, which is the point of writing the doc first and
then disagreeing with it:

1. **Carrier pinning cannot be a kernel decision** (§3.1). `jdk.virtualThreadScheduler.parallelism` is
   a global JVM property with no public per-thread equivalent in JDK 25, so a library that set it
   would hijack the host application's entire virtual-thread scheduler. Demoted from architecture to
   a documented deployment flag; correctness never depends on it. The M0 write-up overstated this.
2. **`runUntil(limit)` is a window, not a bounded `run()`** (§4). An empty window is a legitimate
   outcome, so only an unbounded run may report a stall — otherwise stepping a simulation window by
   window is impossible. Found by a test that expected the wrong thing for the right reason.
3. **Children unwind before their parent** (§4). Falls out of rule 1 rather than needing a special
   case, and matches nested try-with-resources. Now a documented guarantee.

### Design notes worth keeping

- **Retracted entries never move logical time.** A deadline superseded by a trigger stays in the
  queue, stamped with a suspension token that no longer matches; the kernel discards it *without*
  setting `now`. Skipping it naively would drag time to a moment at which nothing happens.
  `TriggerTest.retractedDeadlineDoesNotAdvanceTime` pins this down.
- **The stall is a named error, not a silent exit.** Live shreds with an empty timeline under the
  virtual clock is always a bug, and `TimelineStalled` names which shreds are stuck.
- **`join()` from inside a shred is refused**, because it would hold the baton and stop logical time
  for everyone. `await(shred.done())` is the same wait expressed as a yield point.

*Resolved:* §15.2 — ChucK's event ships as `Trigger`, leaving `Signal` for the reactive supertype in
M4.

## M2 — Realtime and driven clocks, and slip ✔ done

`Wall`, `Realtime`, `Driven`, `Settlement`, `Repayment`, `Overrun`, plus `slip()`/`slack()` on `Kron`.
55 tests; only three of them touch a real clock, and those assert almost nothing.

The trick worked: **the slip model is pure arithmetic**, so an injectable `Wall` turns the whole
settlement model into exact unit tests. `ManualWall.parkUntil` does not wait, it moves its own reading
to the deadline — and scripting an overrun is then just calling `advance()` inside a shred's segment.

Exit criteria, all met:

| Criterion | Result |
|---|---|
| Each settlement policy has a scripted-overrun slip curve | `SlipModelTest`, asserting exact millisecond curves |
| Repayment respects its bound, never overshoots negative | ✔, including a case that would overshoot without the clamp |
| `maxSlip` produces exactly one resync, not a storm | ✔ — one-shot by construction, since slip is zero afterwards |
| `INLINE` returns with the batch complete | ✔ (see the correction below) |

### What the implementation taught us

1. **Three of the four settlement policies are one mechanism.** `CATCH_UP` is `SLIP` with an unbounded
   repayment rate and `STRETCH` is `SLIP` with none — only `SKIP` is structurally different, because
   it forgives the debt instead of repaying it. Two tests now assert that equivalence directly rather
   than merely documenting it.
2. **`INLINE` cannot run on the caller's thread** (§4.2). A render thread is a platform thread, and
   M0's 10× penalty applies to *every handoff*. Reimplemented as a synchronous batch on a persistent
   virtual kernel thread: same contract, one round-trip per frame instead of one per handoff.
3. **`SKIP` only means anything for origin-relative scheduling.** `Metro` and `sync` compute the next
   wake from a fixed origin, so a jump genuinely skips work; `advance` computes it from the shred's own
   `now`, so a skipped shred just finds itself behind again — which is `CATCH_UP` by another name. Now
   documented on the enum, and it is an argument for writing periodic work with `Metro`.
4. **The batch-completion signal needed tickets, not a gate.** In `HANDOFF` nobody consumes the
   permit, so a later synchronous batch would take the stale one and return before its own work ran.
   Found while writing the coalescing test.
5. **The 500 µs spin tail was wrong by 40×** — see [benchmarks/slip.md](benchmarks/slip.md). It was
   shorter than Windows' `parkNanos` overshoot, so it was never reached and the pacing itself
   manufactured slip. 1.5 ms is the measured default.

### Known gap, deferred deliberately

- **`slack()` is degenerate until M3.** It reports the wall time before the next *declared* deadline,
  and a shred declares its next wake only at the end of its segment — so a lone periodic shred reads
  `FOREVER`. That is the truth, not a bug, but it is not yet the useful number: rate domains declare a
  period up front, which makes the next grid line knowable before the segment runs. Tested as-is, both
  ways.
- **`Rate.degrade` hysteresis** belongs to rate domains and moves to M3 with them.
- **The kernel does not idle-park.** Under the realtime clock, `run()` still returns when nothing is
  scheduled rather than waiting for an external `post`. Driven mode covers the GUI case, so this is
  deferred to M3 rather than half-built.

## M3 — Rate domains ✔ done

`Rate`, `Step`, `Sampled`, `Interp`, plus domain priority in the timeline comparator. 73 tests.

Exit criteria, all met:

| Criterion | Result |
|---|---|
| Exact step counts over a long virtual run | 30 000 steps in ten logical minutes at 50 Hz — exactly, not approximately |
| Spiral-of-death clamp holds under a scripted stall | `maxCatchUp` bounds the replay; asserted step-by-step |
| Domain priority resolves same-moment ties (rule 4) | ✔, with the lower-priority domain deliberately sporked *first* so sequence order alone would fail |
| Coupled domains never diverge | ✔ — and they cannot, see below |

### What the implementation taught us

**The big one: a domain cannot own a settlement policy.** §6 said audio could slip while graphics
skipped. It cannot — `wall(m) = m + slip` has one `m`, so per-domain slip means per-domain logical
clocks, and then the domains are not on one timeline any more. Cross-domain interpolation, the whole
reason they share a timeline, stops being well defined, and A/V desync becomes reachable rather than
impossible.

What a domain *does* own is its **catch-up policy**, which turns out to express the same choice in the
place it actually lives: `maxCatchUp(0)` is `SKIP` for one domain, unbounded is `CATCH_UP`, and
anything between is the spiral-of-death clamp. When it replays it runs the **most recent** steps owed
and drops the stale ones — a simulation wants to be current, not complete.

**`couple()` is deleted, not implemented.** It existed to stop audio and graphics drifting apart; with
one clock they cannot drift apart, so it was a feature answering a problem this architecture does not
have. Better to delete an API than ship a no-op.

Smaller findings:

- **`Rate.steps()` was lying by one.** The grid index is incremented before the yield, so a parked
  domain is already one ahead; reporting that as "steps run" overcounts forever. Now counts completed
  steps.
- **`slack()` is now the number it was supposed to be.** M2 could only report `FOREVER` for a lone
  periodic shred, because a shred declares its next wake at the *end* of its segment. A domain
  declares a period up front, so the next grid line is knowable *before* the segment runs.
- **Cross-domain sampling costs exactly one step of latency**, and `Sampled` says so rather than
  hiding it: interpolation can only look backwards, so a frame at `t` shows the value at `t - period`.
  `latest()` is the escape hatch. Alpha is clamped, so past the newest step the value holds instead of
  extrapolating into a future nobody computed.

### Still deferred

- **The kernel does not idle-park** — under the realtime clock, `run()` still returns when nothing is
  scheduled rather than waiting for an external `post`. Less pressing now: a domain keeps the timeline
  populated forever, and driven mode covers the GUI case. Rolls forward to M7 with the atchung bridge,
  where an externally-fed timeline is the actual use case.

## M4 — The signal graph

`Signal`, `Cell`, `Curve`, `computed`, `effect`. Read-registration dependency tracking, per-moment
versioning for glitch-freedom, and **horizon computation and propagation**. Evaluation stays lazy at
`now` — no precomputation yet. Get the graph right before making it fast.

**Plus `Tempo` (§6.3)**, now that §15.5 is resolved, because a tempo is a graph node and its horizon is
part of the horizon model rather than a later addition. Split by difficulty:

- **In M4:** `Ratio`, the `Tempo` tree, constant scales, and discretely-changed scales. The local→global
  map is affine, so conversion is exact integer arithmetic, and a scale change is an invalidation the
  §7.2 machinery already handles.

  **`Ratio` is not a convenience.** Scales are exact integer ratios because the eventual purpose is
  nesting musical ratios — 3:2 inside 4:3 inside 7:4 — and with doubles the grid lines of sibling
  tempos coincide only approximately, which is a polyrhythm smearing instead of locking. Same drift
  argument as `Metro`, one level up. Exit criteria to add: a deeply nested chain of ratios lands
  *exactly* on its common multiple, and reordering a chain of scales produces bit-identical grids.
- **In M5:** animated scales, where the map becomes an integral rather than an affine transform. That
  is where the piecewise-linear integration over the tempo's own grid belongs, alongside the precompute
  pool that needs it.

### ✔ Done — 98 tests

`Ratio`, `Tempo`, `Signal`, `Cell`, `Curve`, `Derived`, `Effect`, `Graph`, plus `Rate` refitted so a
period is declared in its tempo's local time.

| Exit criterion | Result |
|---|---|
| Horizon propagates through diamonds and deep chains | ✔ — and it turned out to be *two* rules going opposite ways (below) |
| `live` vs `drive` reclassifies a subgraph with no downstream change | ✔, asserted on an untouched derived signal |
| An unscheduled `Cell` write invalidates strictly *after* its moment | ✔ |
| No signal evaluated twice in one moment; no half-updated graph | ✔ — a four-reader diamond evaluates its apex once |
| A nested chain of ratios lands exactly on its common multiple | ✔ — 3:2 and 4:3 grids meet at 72 ms on the nanosecond |
| Reordering a chain of scales gives bit-identical grids | ✔ |

### What the implementation taught us

**The horizon was one number doing two jobs.** Writing the tests exposed it: a cell driven by a 200 ms
curve is *determined* forever — the curve covers the next 200 ms and its final value holds after — but
it only *varies* for 200 ms. The design had these as the same number, and they aren't. Worse, they
propagate **in opposite directions**: determination is the *minimum* over inputs, variation is the
*maximum*. Split into `horizon()` and `varyingUntil()`; §7.1 rewritten.

**A held `Cell` had to become optimistic.** §7.1 specified `now` for a cell with no scheduled writes.
That is conservative and ruinous — most cells are held constants, so it would mean nothing downstream of
any cell is ever precomputed and M5 would have nothing to do. Optimism is sound rather than a gamble
because **effects never run ahead of `now`; only values do**, so a wrong prediction is always retracted
before anything acts on it. `Cell.live()` is the explicit opt-out, and it is now the *only* thing that
genuinely blocks prediction — which is a much sharper statement of the thesis than "cells are unknowable".

**A curve is unanchored, and its elapsed time is local.** So driving a 200 ms curve inside a 1:4 tempo
stretches it to 800 ms of wall time with nothing in the animation mentioning slow motion. That is the
payoff of §6.3 being a property of a *region* rather than of each call site, and it is one line of test
to prove.

**Reactive effects need re-run deduplication.** An effect that writes a cell invalidates itself; without
one-pending-re-run-per-effect a cascade would not converge within a moment.

### Deferred to M5, deliberately

- **Animated tempo scales**, where the local→global map stops being affine. `Tempo.horizon()` returns
  `FOREVER` unconditionally today, which is correct for constant and discretely-changed scales and will
  need the integral once a scale can itself be a signal.
- **Concurrent evaluation.** Dependency *discovery* currently happens on the timeline, single-threaded.
  When the precompute pool evaluates on worker threads it must do so with an already-known dependency
  set rather than discovering one — worth writing down now, because getting it wrong is a data race in
  the one place this design promised there could not be one.

## M5 — Precomputation ✔ done

`Predict`, `Prediction`, `Predictor`, plus thread-confined evaluation contexts and `Derived.prime`.
105 tests. Full measurements: [benchmarks/precompute.md](benchmarks/precompute.md).

| Exit criterion | Result |
|---|---|
| Prediction is observationally invisible | ✔ — differential test over 200 seeds × 3 policies, comparing against the lazy path as reference |
| An invalidation discards the future and only the future | ✔ |
| A volatile signal is not predicted at all | ✔ — nothing filled |
| The constant tail collapses to one sample | ✔ — a 500 ms window over a 20 ms curve fills <15 samples, not 50 |
| Waste is metered and drives automatic demotion | ✔, hysteretic |
| Concurrent evaluation is safe | ✔ — evaluation state moved to thread-confined frames, as M4 flagged |

### The property test earned its keep immediately — two real bugs

1. **The constant tail was not truncated on invalidation.** A tail is a half-open interval
   `[index, ∞)`, and I only dropped it when its *start* was after the invalidation moment. A tail
   beginning before and extending past kept serving stale values indefinitely. Caught on seed 2 of the
   differential test, which is exactly the kind of bug no hand-written example would have found.
2. **`Cell.set()` silently un-declared volatility.** `live()` is a promise about *how a cell behaves*,
   but `set()` reset the mode to `HELD` — so an input adapter writing on every event made its cell
   *predictable* on the first event. Precisely backwards.

### The performance story took four attempts, and the last one is the finding

> A sliding window in steady state needs **one new sample per step**, whoever computes it.

So topping the buffer up by one sample per step is exactly the work lazy evaluation would have done,
with a buffer bolted on for decoration — and it measured as exactly that: no improvement. Refilling in
**bursts** when the buffer is half empty is what makes the parallelism real: **2.3×** at 100 ms of
lookahead over 48 kHz. A two-frame window still measures at parity, and that is the honest answer —
lookahead is what buys the speedup, so a domain declaring none should expect none.

Two prerequisites had to be fixed before any of that was visible: the fill re-walked the whole window
every step (4 800 map probes per step for an audio window), and dispatching a single sample to the pool
cost ~14 µs against a ~3 µs sample. Both made prediction *slower* than not predicting.

And the harness itself was wrong three times. Its first version reported prediction as 70 % slower on
numbers that were entirely JIT warmup — 18 000 ns/step against a real per-step cost of 520–822 ns.
**Same failure mode as M0**: a plausible distribution measuring the wrong quantity. The fix both times
was to isolate the layer instead of reasoning about the aggregate. A by-layer breakdown confirmed the
kernel had not regressed at all (520 ns, against M1's 511 ns) and put prediction's own overhead at
~180 ns per step.

### Deliberately narrowed, and why

- **The fill is a burst with the timeline paused**, not a background thread. Filling concurrently would
  mean workers reading `Cell` state while a shred mutates it, which needs every readable field safely
  published — a much bigger correctness surface than this milestone should open. The cost is that the
  burst's wall time is charged to the current segment, so it spends `slack()` and can become slip,
  which the existing instruments already measure.
- **Dynamic domains cannot be predicted**, and this is a limit in principle rather than in code: their
  sample points are whatever the display decides, so there is no grid to fill ahead. A dynamic consumer
  reads predicted values from a fixed domain through `sample()` — the machinery M3 already built.
- **Animated tempo scales** still deferred; `Tempo.horizon()` is unconditionally `FOREVER`, correct for
  constant and discretely-changed scales. Moves to M6 with the interpolation library that needs it.

## M5 — original plan

The per-domain worker pool, per-signal ring buffers, `EAGER`/`LAZY`/`NEVER`, invalidation-after-moment,
waste metering and automatic demotion.

The correctness criterion is a single property, and it is a strong one:

> **Precomputation is an optimization and must be observationally invisible.** Any scenario, run with
> prediction on and off under the virtual clock, must produce identical output and identical traces.

That is a property test over generated graphs, not a handful of examples, and it is what makes the
whole prediction subsystem safe to trust.

*Decide before starting:* §15.5, whether rate domains nest or are time-scalable. It is small in the
grid math and large in the horizon math, so it is cheap now and a rewrite afterwards.

## M6 — `kronometer-anim`

The `Ease` library, `Interp` implementations including `SLERP` and shortest-arc `ANGLE` and a
perceptual color space, `Tween`, `Timeline`, `Animator`, and both smoother families with the
fixed-domain constraint enforced at construction.

Exit criteria: eases hit exactly 0 and 1 at the endpoints; slerp holds constant angular velocity;
angle interpolation takes the short way round across the wrap; binding an integrated smoother to a
dynamic domain fails loudly; a retriggered animation continues from its current interpolated value
rather than snapping.

## M7 — `kronometer-atchung`, and a headless demo

`Topic` as a yield point, `Topic` driving a `Cell` (the natural way live input enters the graph, at
`horizon == now`), publishing on the timeline, kernel-driven `Pump`, elektro-Q messages as timeline
events.

The demo stays headless — it writes a trace and a CSV you can plot — so the reactor never grows a GUI
dependency.

## M8 — First real consumer

vexelray-gui constructs a `Kron`, ticks it per frame in `INLINE`, exposes `gui.kron()`, and moves one
real interaction onto the graph. That is the milestone that tells us whether the seams are actually
as generic as §13 claims.

The tactroller scenario harness lands in the same phase, in whichever repo §15.4 resolves to.

---

## Open questions, with deadlines

| Question | Decide before |
|---|---|
| §15.2 — `Trigger` vs keeping ChucK's `Signal` name | **M1** (API name) |
| ~~§15.5 — nested / time-scaled rate domains~~ | **resolved** — yes to all of it; designed as `Tempo` (§6.3) |
| §15.4 — which repo owns the tactroller harness | **M8** |
| §15.3 — `Cell` future models / speculative prediction | after M5; research, not v1 |
