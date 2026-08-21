# Kronometer

**A strongly-timed event and animation controller for the JVM and GraalVM native-image — ChucK's
clock, Vue's graph.**

Time is a value you *advance*, not a callback you wait for. A `Shred` is ordinary Java that survives
across time — it runs, says "let 250 milliseconds pass", and picks up where it left off with its
stack intact. Between two such statements it executes in **zero logical time**, so timing is exact
rather than approximate, and no two shreds can race.

```java
import static sibarum.kronometer.Time.*;
import static sibarum.kronometer.Dur.*;

try (Kron kron = Kron.realtime()) {
    kron.spork(() -> {
        for (int i = 0; i < 4; i++) {
            fire();
            advance(ms(250));       // exactly 250 ms later. every time. no drift.
        }
    });
    kron.run();
}
```

## Two worlds, one graph

Kronometer runs two things at once, with opposite rules:

|  | **Effectful** | **Predictable** |
|---|---|---|
| What | shreds, I/O, GUI mutation | pure functions of time — curves, easing, interpolation |
| Order | a total order, one baton, strictly at `now` | none needed |
| When | exactly at its moment | **as far ahead of `now` as it is knowable** |

> **The baton guards side effects, not computation.**

You never declare which world a value is in — **the dependency graph decides**. A value derived only
from time and constants is predictable. One derived from live pointer input is not. One derived from
both is predictable exactly as far ahead as its least-knowable input, and the kernel computes that
distance and calls it the value's **horizon**.

```java
Cell<Double> lift = kron.cell(0.0);
Signal<Double> sh = kron.computed(() -> 2 + 6 * lift.get());
kron.effect(frames, () -> card.elevation(dp(sh.get())));

lift.live();                                    // volatile: nothing may be precomputed
lift.drive(Curve.ramp(0.0, 1.0, ms(200)));      // determined forever, varying for 200 ms —
                                                // every frame of `sh` computable on the spot
```

Nothing about `sh` or the effect changed. The graph reclassified itself because its input did. Audio
and animation are pure math, so their horizons are long — often *the entire remaining future* of a
motion, computed once and never thought about again. Reading at `now` is a buffer index: no
allocation, no math, no jitter.

Two numbers, not one, because they propagate opposite ways: **`horizon()`** is how far the value is
*determined* — the minimum over its inputs, and what prediction may rely on — while
**`varyingUntil()`** is how far it still *changes*, the maximum over its inputs, and where sampling can
stop and store one constant. A 200 ms animation is determined forever and varying for 200 ms.

> **Anything pure should be a `Signal`, not a `Shred`.** A shred costs a baton handoff — 577 ns
> measured under native-image, so about 2 900 of them fit in a tenth of a 60 Hz frame
> ([the numbers](docs/benchmarks/baton.md)). A tween is a function of time, not a thread: put it in
> the graph and precomputation evaluates it ahead, off the baton, for free. Ten thousand
> simultaneous animations cost zero handoffs.

## You can block on the timeline

A blocking call costs **zero logical time**, and it does not perturb output either — because output
for the next *H* milliseconds was computed before you blocked.

> **You may block for as long as you have precomputed future.** 40 ms with 100 ms of rendered audio
> ahead of you is inaudible. 40 ms with one frame of lookahead drops two frames.

`kron.slack()` is that number, so the budget is readable rather than folklore. Overrun it and the
shortfall becomes **slip** — a buffer can't invent time. `offload(work)` is still there for genuinely
unbounded I/O.

## Frame rate is plural

**Rate domains** are independent sampling grids over one timeline — Unity's split between physics and
graphics rates, generalized and first-class:

```java
Rate physics = kron.fixed(hz(50)).maxCatchUp(5);          // exact 20 ms grid, always
Rate frames  = kron.dynamic();                            // whatever the display gives
Rate audio   = kron.fixed(hz(48_000)).lookahead(ms(100));  // 100 ms of rendered future
```

Fixed domains guarantee an exact `dt`, which is the only way integrated physics and smoothing are
reproducible; dynamic domains step when their driver ticks. Sampling *across* domains — the graphics
frame that lands between two physics steps — is handled rather than hand-rolled:

```java
Sampled<Double> shown = physics.sample(body::x, Interp.DOUBLE);
frames.each(step -> node.x(shown.at(step.at())));         // smooth at any refresh rate
```

That costs exactly one step of latency, and Kronometer says so rather than hiding it: interpolation
can only look backwards, so a frame at `t` shows the value at `t - period`. `latest()` is there for a
caller who would rather have the latency than the smoothness.

Each domain sets its own lookahead — audio wants 100 ms, graphics two frames, physics none — and its
own **catch-up policy**. `maxCatchUp(0)` drops everything owed after a jump, unbounded replays it all,
and anything between is the spiral-of-death clamp. What a domain deliberately does *not* own is a slip
policy: `wall(m) = m + slip` has one `m`, so one timeline has one slip, and domains therefore cannot
drift apart from each other at all.

## Nested, localized, scalable time

Everything nests, everything is local, and everything can be scaled — including time. Real time is
never bent: the root is wall-locked, and what a scale changes is the *actual rate* at which a region's
declared work meets it.

```java
Tempo bullet  = kron.tempo().child(Ratio.of(1, 4));   // quarter speed
Rate  physics = bullet.fixed(hz(50));                 // declared 50 Hz; actual 12.5 Hz
```

The canonical case needs no special-casing: a dynamic domain is tick-driven and therefore wall-locked,
so the picture keeps rendering at 60 fps while the simulation inside the slow tempo steps four times
less often. A curve driven in that tempo stretches with it, without the animation mentioning slow
motion. **Moments stay global; durations are local** — otherwise moments from different frames would be
silently incomparable.

Scales are exact **`Ratio`**s, never doubles, because the eventual purpose is nesting musical ratios —
3:2 inside 4:3 inside 7:4 — and with floating point the grid lines of sibling tempos coincide only
approximately, which is a polyrhythm smearing rather than locking. Read as a slope: `Ratio.of(3, 2)` is
rise over run, and its angle is `arctan(3/2)` in **turns**, which wrap at 1 rather than at an irrational
2π and so can be held exactly.

## Interpolation that knows what it is

`Ease` shapes a float; `Interp<T>` blends a type. Both pure, so anything built from them is
predictable by construction, and every ease lands on **exactly** 0 and 1 rather than a thousandth
short — an animation that stops at 0.999 of its target leaves a shadow that never quite settles.

Rotation ships correct, and generalized: `Hyper` is the **Cayley–Dickson tower** — real, complex,
quaternion, octonion — with one recursive product, so `Hyper.SLERP` is dimension-generic and quaternion
slerp is simply its level-2 case. Angles are `Turn`s, measured in **turns rather than radians**, because
a turn wraps at 1: reducing a phase is taking a fractional part, so a thousand accumulated eighth-turns
land on exactly zero where radians would drift. `Turn.SHORTEST` goes the short way round the wrap
instead of leaving you to discover that a lerp on angles takes the long way.

And the distinction that has teeth: a **closed-form** motion (`value = f(t)` — a tween, an analytic
spring toward a fixed target) is fully predictable and precomputed in one shot. An **integrated** one
(`value = f(previous, dt)` — a spring chasing live input) has a horizon of `now` and is
framerate-dependent unless `dt` is constant, so Kronometer **rejects binding it to a dynamic
domain**. Put it on a fixed domain and let graphics interpolate it.

## Two clocks, one kernel

The kernel does not know what a second is — it asks a `Clock`. That seam is why the same code runs
live and under test:

```java
Kron live = Kron.realtime();   // paced against nanoTime, with an explicit overrun policy
Kron test = Kron.virtual();    // jumps to the next moment: 10 logical minutes in µs
Kron gui  = Kron.driven();     // stepped once per presented frame by the render loop
```

Under `Kron.virtual()` a run is **deterministic** — enforced, not hoped for: anything genuinely
nondeterministic must declare its logical arrival time or be rejected. A ten-minute Tactroller GUI
scenario becomes a microsecond unit test whose `Trace` is the assertion target.

`Kron.driven()` defaults to `INLINE`, which guarantees that `kron.tick(nanos)` **returns with the
batch complete** — effects have run before the frame is submitted, nothing drawn out of phase with
what was computed. It delivers that by handing the batch to a persistent virtual kernel thread and
blocking, rather than by running on the caller: a render thread is a platform thread, and a platform
kernel thread costs 10× per handoff ([why](docs/benchmarks/baton.md)).

## Slip: what happens when you lose

When the machine cannot keep up, there is one honest description, and it is an equation:

```
wall(m) = m + slip
```

**Slip is a debt.** An underrun forces it up — the work didn't finish, so the schedule moves later,
and no alternative preserves the output. It comes down only if the machine gets ahead again, and
nothing guarantees it will. So the question isn't how to avoid slip; it's **how the debt is settled**:

| Policy | Settles by | Costs | For |
|---|---|---|---|
| `SLIP` *(default, continuous)* | holding it, repaying within a perceptibility bound | latency | audio, anything where continuity beats phase |
| `CATCH_UP` | paying it — running flat out until caught up | a load spike right after a stall | simulation, sequencing |
| `SKIP` | forgiving it — jumping forward, dropping the gap | a discontinuity | graphics, where a dropped frame beats a late one |
| `STRETCH` | ignoring it — logical time just runs slow | any relation to the wall | debugging |

> **`SKIP` trades continuity for latency. `SLIP` trades latency for continuity.**

Pick per domain, by what it drives. Audio must not skip — a dropped block is a click. A
pointer-following animation must not slip, because **slip on an input-driven signal *is* input lag**.

Slipping is also *cheap*: precomputed samples are indexed by **logical** moment, so slip only shifts
the logical→wall mapping and recomputes nothing. Skipping throws the gap away and re-renders at the
new `now` — more work, exactly when there was none to spare.

And for when the debt can't be repaid, because "hope to catch up later" needs a fallback:
`maxSlip(Dur)` bounds it and crossing it is a **hard resync** — one deliberate, reported
discontinuity instead of unbounded creeping lag. Sustained slip is a capacity problem, not a
scheduling one, so the real remedy is `frames.degrade(hz(144), hz(72), hz(48))`: ask for less rather
than fall further behind.

## Where it sits

A **substrate**, alongside two others, under the GUI at the top of the stack — each its own repo:

| Repo | Role | Layer |
| --- | --- | --- |
| **[vexelray-gui](../vexelray-gui)** | Pixels: the retained-mode SDF GUI over Vulkan | consumer |
| **kronometer** | **Time: when things happen, in what order, and how they move between states** | substrate |
| **[atchung](../atchung)** | Messages: the in-VM bus, and elektro-Q across processes and the wire | substrate |
| **[tactroller](../tactroller)** | Input: keyboard/pointer/clipboard middleware | substrate |

Dependencies point **down** and only down: vexelray-gui depends on Kronometer, never the reverse, and
`kronometer-core` depends on nothing in the ecosystem at all.

| Module | What it provides |
|---|---|
| `kronometer-core` | Kernel and time: `Kron`, `Shred`, `Time`, `Dur`, `Moment`, `Trigger`, `Metro`, `Clock`, `Settlement`, `Overrun`, `Rate`, `Step`, `Sampled`, `Trace`. Nested time: `Tempo`, `Ratio`. Graph and horizons: `Signal`, `Cell`, `Curve`, `Effect`. Precompute pool in M5. The `Interp` interface. Pure Java, no dependencies. |
| `kronometer-anim` | The libraries: `Ease` curves, `Turn` (shortest-arc angles in turns), `Hyper` (the Cayley–Dickson tower, with dimension-generic slerp), `Tween`, `Motion`, `Animator`, and both smoother families. Depends only on core. |
| `kronometer-atchung` | Await an Atchung `Topic` as a yield point, or let one drive a `Cell`; publish on the timeline; carry elektro-Q messages onto it. |
| `kronometer-demo` | The showcase — headless, so it stays free of a GUI dependency. |

There is deliberately no `kronometer-vexelray` module: every seam is already generic, so the GUI repo
constructs the `Kron`, ticks it per frame, and exposes it.

## Requirements

- **JDK 25+** (enforced by `maven-enforcer-plugin`) — virtual threads carry shreds, `ScopedValue`
  carries the current one. A **GraalVM** JDK only for native builds.
- **Maven 3.9+**.
- No runtime dependencies, native-image clean: no reflection, no proxies, no runtime scanning —
  dependency tracking is by read-registration, so the graph needs no metadata either. The `native`
  profile is opt-in so ordinary builds stay fast.

## Build

```bash
mvn verify     # compile + all tests across every module
mvn install    # + install 1.0-SNAPSHOT into your local repo
```

## Status

Design stage. [docs/architecture.md](docs/architecture.md) is the source of truth — the kernel, the
ordering rules, the signal graph and horizon model, precomputation, rate domains, interpolation,
diagnostics, and the open questions. No code yet.
