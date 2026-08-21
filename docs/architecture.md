# Kronometer — architecture

> A ChucK-inspired, strongly-timed event and animation controller for the JVM and GraalVM
> native-image. This document is the design; the README is the tour.

## 1. Thesis: two worlds, one graph

Kronometer runs two things at once, with opposite rules.

|  | The **effectful** world | The **predictable** world |
|---|---|---|
| What lives here | shreds, `Cell` writes, I/O, GUI mutation | pure functions of time — curves, easing, interpolation, anything derived from them |
| Ordering | a total order, one baton, strictly at `now` | none needed |
| When it runs | exactly at its moment | **as far ahead of `now` as it is knowable** |
| Parallelism | one at a time, by construction | embarrassingly parallel |

> **The baton guards side effects, not computation.**

The thing that makes this one framework rather than two is that **you do not declare which world a
value is in — the dependency graph decides.** A value derived only from time and constants is
predictable. A value derived from live pointer input is not. A value derived from *both* is
predictable exactly as far ahead as its least-knowable input, and the kernel computes that distance
for you and calls it the value's **horizon**.

Precomputation then fills the gap between `now` and the horizon. Audio and animation are pure math,
so their horizons are long — often *the entire remaining future* of an animation, which for a 200 ms
tween at 60 Hz is twelve samples you can compute in one go and never think about again. Reading a
precomputed value at `now` is a buffer index: no computation, no allocation, no jitter.

And that is what makes §10 true: **you can block on the timeline.** A blocking call costs zero
logical time and does not perturb output, because output for the next *H* milliseconds was computed
before you blocked. The lookahead buffer is the jitter budget, and it is a number you can read.

If ChucK is where the timing model comes from, the graph is Vue's: sources, derived values,
memoization, automatic invalidation, glitch-free reads. The novelty is the third axis — **time** is a
first-class dependency, and depending on it is what makes a value knowable early rather than merely
cached late.

## 2. The invariant

> **Logical time advances only when no shred is runnable.**

A *shred* is a unit of strongly-timed execution. It runs, says "let 250 milliseconds pass", and
picks up where it left off. Between two such statements it executes in **zero logical time**, no
matter how long the CPU actually took. The kernel never moves `now` forward while any shred still
has work at the current moment.

This governs the effectful world. The predictable world is outside it entirely — pure evaluation has
no observable order, so the kernel is free to run it early, late, or on eight threads at once.

Two consequences:

1. **Timing is exact, not approximate.** `advance(ms(250))` lands on *exactly* 250 ms of logical
   time later. Error never accumulates, because each advance is computed from the shred's logical
   `now`, never from a wall-clock reading.
2. **Shreds cannot race each other.** One shred holds the baton for its whole zero-time segment, so
   a segment is atomic with respect to every other shred. Two shreds mutating the same model at "the
   same time" is a defined, ordered thing, not a data race.

## 3. Shreds are virtual threads

A shred must suspend mid-function and resume later with its stack intact. JDK 25 makes that free: a
shred **is** a virtual thread, and `advance()` is a park. Ten thousand concurrent animations is ten
thousand parked virtual threads — a few megabytes, not ten thousand OS threads.

`ScopedValue` (finalized in JDK 25) carries the current shred, so the time intrinsics are plain
statics with no argument threading:

```java
import static sibarum.kronometer.Time.*;
import static sibarum.kronometer.Dur.*;

spork(() -> {
    for (int i = 0; i < 4; i++) {
        fire();
        advance(ms(250));        // exactly 250 ms of logical time, four times
    }
});
```

Neither is a dependency — both are JDK features, so the kernel keeps the house rule of **zero runtime
dependencies, no reflection, native-image clean**.

### 3.1 One kernel decision, and one deployment flag

Measured in M0 ([benchmarks/baton.md](benchmarks/baton.md)); together worth **32×** on the handoff,
taking it from 15 497 ns to 489 ns (577 ns native-image).

1. **The kernel loop runs on a virtual thread** — a genuine kernel decision, worth 10×. A platform
   kernel thread makes every handoff cross between the OS scheduler and the virtual-thread scheduler,
   waking an idle ForkJoinPool carrier in one direction and a parked platform thread in the other:
   two OS thread wakeups per baton pass. On a virtual thread the whole exchange stays inside the pool.
   *Never cross the scheduler boundary on the hot path.*
2. **Pinning the carrier pool to one thread** is worth another 3×, because the baton serializes
   everything anyway and extra carriers buy only cross-core wakeups and cache traffic. But this
   **cannot be a kernel decision** — corrected during M1, where the attempt to make it one ran into
   the fact that `jdk.virtualThreadScheduler.parallelism` is a global JVM property and JDK 25 has no
   public per-thread scheduler. A library that pinned the host application's entire virtual-thread
   scheduler would be a bad guest. So it is an application-level flag, worth setting in a desktop app
   that owns its JVM, and correctness never depends on it:

   ```
   -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1
   ```

   M1's kernel measures 342 ns per handoff with it and 511 ns without — comfortably inside budget
   either way.

The second point still has a consequence that reaches into §8 and §10 wherever it *is* set, and it is
the sharp edge of this design:

> **The precompute pool and `offload()` must run on their own executors, never the kernel's carrier.**

One carrier means one runnable virtual thread. Pure evaluation scheduled onto that carrier would not
merely be slow, it would deadlock against the serialization that makes the baton fast. The separation
of the predictable world from the effectful one is therefore not only conceptual (§1) — it is a
thread-pool boundary the implementation has to enforce.

A negative result worth keeping: spin-then-park handoff looks 5× faster in a two-party ping-pong and
is an illusion, because a realistically-parked shred pays its wakeup regardless. On a single carrier
it is 12× *worse*, since a spinning virtual thread holds the carrier its counterparty needs in order
to answer.

## 4. The kernel

One **kernel thread** owns logical time and hands a baton to exactly one shred at a time. It holds
`now`, a **timeline** priority queue of `(wakeMoment, priority, sequence, shred)`, the **clock** (§5), the
**rate domains** (§6), and the **graph** (§7) with its precompute pool (§8).

```
while (alive):
    if timeline is empty: park until a spork or an external post arrives
    t = timeline.peek().moment
    clock.awaitUntil(t)                  # realtime: sleeps to the deadline. virtual: returns now.
    now = t
    while timeline.peek().moment == now:
        entry = timeline.poll()
        hand the baton to entry.shred    # blocks here until the shred yields or ends
    publish the graph version for `now`  # §7.4
```

### Ordering rules (normative)

Part of the contract, not an implementation detail — these are what make a run reproducible:

1. Shreds waking at the same `Moment` run in **sequence-number order**: oldest scheduling decision
   first.
2. `spork` returns to the parent immediately; the child is enqueued at the **current** moment with a
   fresh sequence number, so it starts later in the *same* step, after the parent's segment
   finishes. Segments stay atomic.
3. Work enqueued *during* a step at the current moment runs in that step, in enqueue order. A shred
   that repeatedly enqueues zero-delay work stalls logical time — legal, and §11 explains how you
   find out.
4. When shreds from different **rate domains** wake at the same moment, domains break the tie in
   declared **priority** order — lower first, so physics before graphics. The comparator is
   `(moment, priority, sequence)`, and rule 1 is exactly that with the middle term constant, since
   every shred outside a domain shares priority 0.
5. `kron.post(Runnable)` from *outside* enqueues at "the next moment the kernel observes" —
   deliberately vague under the realtime clock, an error under the virtual clock unless a moment is
   given (§5.3).

### Cancellation

`shred.cancel()` removes it from the timeline and, if parked, resumes it just long enough to throw
`ShredCancelled` at its yield point. `finally` blocks run normally, on the timeline, at the current
moment. A cancelled shred cannot advance time again, so cleanup is inherently bounded. Shreds form a
tree; cancelling a parent cancels its children unless they were sporked `Detach.YES`.

**Children unwind before their parent.** Cancellation walks the tree depth-first, enqueueing each
shred as it goes, so the deeper the shred the lower its sequence number and the earlier its cleanup
runs. This is the same order as nested try-with-resources, and it is the order a parent needs if its
cleanup is to see its children already finished. It falls out of rule 1 rather than being a special
case, which is the good kind of guarantee.

### Bounded runs

`run()` goes until nothing is scheduled. `runUntil(limit)` is a **window** over the timeline: it runs
what falls inside, advances `now` to the limit, and returns with the rest still queued, so a caller
can step a simulation window by window.

The distinction matters for the stall check. An empty window is a legitimate outcome — nothing was
scheduled in that particular stretch — so only an *unbounded* run may conclude that an empty timeline
with live shreds means nothing can ever happen. Conflating the two makes `runUntil` useless for
stepping, which is how M1 found it.

## 5. The clock SPI

The kernel does not know what a second is — it asks a `Clock`:

```java
public interface Clock {
    /** Block until logical `target` may be entered. Returns wall-clock nanos, or -1 when virtual. */
    long awaitUntil(long targetNanos) throws InterruptedException;
    boolean isVirtual();
}
```

That one seam is what lets the *same* code run live and under test.

### 5.1 `Clock.realtime()` and the slip model

Paced against `System.nanoTime()`: park to the deadline, with a spin at the tail for sub-millisecond
accuracy.

**That spin tail turns out to be the single most consequential number in realtime pacing**, and M2
measured it ([benchmarks/slip.md](benchmarks/slip.md)). `LockSupport.parkNanos` is only as precise as
the platform timer — around a millisecond on Windows — so a tail *shorter* than the overshoot is never
reached: the park sails past it and every frame arrives late by the difference. At the 500 µs tail this
design originally specified, 60 Hz median jitter was 402 µs and a 1 kHz run accumulated 11.4 ms of
real slip, meaning **the pacing itself was manufacturing the debt**. At 1.5 ms — the default — the
median is 11 µs and the 1 kHz slip is zero. Precision-critical work should raise it
(`Wall.system(ms(3))` buys a 6× better p99 for twice the CPU); battery-critical work should lower it
and accept the jitter.

When the machine cannot keep up there is exactly one honest description of what happens, and it is
worth writing as an equation rather than a policy:

```
wall(m) = m + slip
```

**Slip is a debt.** An underrun forces it up — the work did not finish in time, so the schedule moves
later, and no alternative preserves the output. It comes down only when the machine gets ahead again,
and nothing guarantees that it will. So the design question is not *how do we avoid slip* but **how
the debt is settled**, and the three obvious policies turn out to be three answers to that, with a
fourth that continuous media actually needs:

| Policy | Settles the debt by | Costs | Right for |
|---|---|---|---|
| `SLIP` *(default for continuous domains)* | **holding it and repaying gradually**, at a rate bounded by perceptibility | latency | audio, and anything whose continuity matters more than its phase |
| `CATCH_UP` | **paying it** — running flat out until wall and logical time meet | a load spike immediately after a stall, which is a good way to cause the next one | simulation, sequencing, discrete work |
| `SKIP` | **forgiving it** — jumping logical time forward and dropping the moments in the gap | a discontinuity | graphics, where a dropped frame beats a late one |
| `STRETCH` | **ignoring it** — the offset grows freely and logical time simply runs slow | any relationship to the wall | debugging, breakpoints, stepping |

`kron.slip()` reads the current debt, as `kron.slack()` reads the remaining lookahead (§10). Together
they are the health of the system: **slack is how much future you have, slip is how far behind you
are.**

#### Slipping is cheap; skipping is not

This falls out of §8, and it is the strongest argument for `SLIP` as the default. Precomputed samples
are indexed by **logical** moment, and slip changes only the logical→wall mapping — so slipping
presents the same buffer slightly later and recomputes nothing. Skipping discards every sample in the
gap and forces a re-render at the new `now`: more work, at the exact moment there was none to spare.

#### The tension, stated plainly

> **`SKIP` trades continuity for latency. `SLIP` trades latency for continuity.**

Choose per domain, by what the domain drives. Audio must not skip — a dropped block is a click — so
it slips. A pointer-following animation must not slip, because **slip on an input-driven signal *is*
input lag**, and lag is the thing users feel — so it skips. That is why settlement policy lives on
the rate domain (§6) and not on the kernel.

#### Repayment is bounded by perception, not by CPU

Repaying as fast as possible *is* `CATCH_UP`, and for continuous media that is its own artifact.
`SLIP` repays within a declared bound — `audio.repay(rate(0.005))`, a ≤0.5 % time-scale change that
is inaudible; `frames.repay(atMost(ms(2)))`, a couple of milliseconds shaved per frame that is
invisible. The debt drains over hundreds of frames instead of one.

#### When the debt cannot be repaid

"Hope there is an opportunity to catch up later" is the honest description, and the framework has to
have an answer for when there isn't one:

- `maxSlip(Dur)` bounds the debt. Crossing it is a **hard resync** — an explicit, reported
  discontinuity, taken deliberately and once, rather than unbounded creeping lag nobody declared.
- Sustained slip means the machine is simply too slow for the declared load, and no settlement policy
  fixes that. The remedy is to **reduce the rate rather than accumulate lag** — `Rate.degrade` (§6).

`kron.onOverrun(handler)` reports every one of these transitions. Overrun is observable, never a
silent stutter.

### 5.2 `Clock.driven()`

Externally stepped: something calls `kron.tick(nanos)` and the kernel runs one batch per call. Two
modes, because the consumer owns this clock (§13):

| Mode | `tick(nanos)` | Consequence |
|---|---|---|
| `Driven.INLINE` *(default for GUIs)* | **Returns with the batch complete**: every effect scheduled up to `nanos` has run. | Effects run in phase with the frame about to be submitted, with no per-frame latency. The caller inherits the segment budget (§11) — a shred that will not yield now stalls the render loop, which is exactly where you want to notice it. |
| `Driven.HANDOFF` | Signals the kernel and returns immediately. Ticks arriving mid-batch coalesce into the newest deadline. | Shred code never delays the render loop; costs a frame of latency and the in-phase guarantee. |

**A correction from M2.** This said that `INLINE` makes the *calling thread* the kernel thread. It
cannot. A render thread is a platform thread, and §3.1 measured a 10× penalty for a platform kernel
thread — 1 493 ns becomes 15 497 ns per handoff, because each one then crosses between the OS
scheduler and the virtual-thread scheduler. A hundred shreds in a frame would be 1.5 ms of pure
scheduling. So `INLINE` keeps the guarantee that matters — synchronous completion — and delivers it by
handing the batch to a persistent virtual kernel thread and blocking until it finishes: one thread
round-trip per *frame* rather than one per *handoff*, about 0.08 % of a 60 Hz frame instead of 9 %.
The contract was the valuable part of the design; the thread identity was the expensive part.

### 5.3 `Clock.virtual()`

`awaitUntil` returns immediately: logical time **jumps** to the next scheduled moment. A ten-minute
scenario runs in microseconds, identically every time.

The virtual clock is stricter on purpose — anything genuinely nondeterministic must be **modelled**,
not observed. `kron.post(Runnable)` without a moment throws; blocking work must declare a logical
duration (§10). If a scenario runs under the virtual clock, it is deterministic. Enforced, not hoped
for.

## 6. Rate domains — frame rate is plural

An animation framework with one frame rate is a toy. Kronometer has **rate domains**: independent
sampling grids over the same timeline, each with its own rate, lookahead horizon, and priority.

```java
Rate physics  = kron.fixed(hz(50)).maxCatchUp(5).priority(0);  // exactly 20 ms, always
Rate frames   = kron.dynamic().priority(1);                    // whatever the display gives
Rate audio    = kron.fixed(hz(48_000)).block(256).lookahead(ms(100));
```

**Fixed** domains guarantee an exact grid and an exact step count — `dt` is a constant, which is the
only way integrated physics and integrated smoothing (§9.3) are reproducible. Falling behind
produces extra steps, clamped by `maxCatchUp` so a stall cannot spiral.

**Dynamic** domains step when their driver ticks, with a varying `dt`. Vsync, on-demand redraw, a
device that changes refresh rate mid-flight.

**Sampling across domains** is the part everyone hand-rolls, so it is first-class. A graphics frame
lands between two physics steps; rendering the older step stutters and rendering the newer one is
time travel. The answer is to interpolate, and because the graph knows both the domain grid and the
value's `Interp`, it does it for you:

```java
Signal<Transform> shown = physics.interpolate(body.transform);   // sampled at the reader's moment
frames.each(dt -> node.transform(shown.get()));                  // smooth at any refresh rate
```

A domain is also the unit of **lookahead**: audio wants 100 ms of rendered future, graphics wants two
frames, physics wants none. Setting them independently is the whole point.

### 6.0 What a domain owns — a correction from M3

This section used to say a domain was also the unit of **slip settlement**: audio slipping while
graphics skipped. **It cannot be, and implementing it showed why.**

> **Slip is a property of the timeline, and there is only one timeline.**

`wall(m) = m + slip` has one `m`. Giving each domain its own slip would give each its own logical
clock, at which point the domains no longer share a timeline — and cross-domain interpolation (§6.2),
which is the entire reason they were on one timeline, stops being well defined. Worse, the thing the
design was worried about becomes *possible*: A/V desync is precisely what independent offsets are.

What a domain genuinely owns is its **catch-up policy** — when logical time has jumped past some of
its grid lines, how many to replay and how many to drop. That is the same choice expressed where it
actually lives:

| Per-domain setting | The settlement policy it corresponds to |
|---|---|
| `maxCatchUp(0)` | `SKIP` — drop everything owed, resume on the next grid line |
| `maxCatchUp(n)` | bounded catch-up, and the spiral-of-death clamp |
| `maxCatchUp(Integer.MAX_VALUE)` | `CATCH_UP` — run every step owed |

When it replays, a domain runs the **most recent** steps it owes and discards the stale ones: a
simulation wants to be current, not complete.

For the same reason **there is no `couple()`**. The design called for one to stop audio and graphics
drifting apart. With a single clock they cannot drift apart, so coupling is not a feature to add — it
is a problem this architecture does not have. `RateDomainTest.domainsShareOneTimeline` asserts that
directly.

### 6.1 Degradation — the only real fix for sustained slip

Slip that never drains is not a scheduling problem, it is a capacity problem, and the honest response
is to ask for less rather than to fall further behind:

```java
frames.degrade(hz(144), hz(72), hz(48));   // step down while slip persists, back up as it drains
physics.degrade(hz(100), hz(50));          // halving keeps the grid commensurate — no resampling
audio.degrade(NEVER);                      // a rate change here is a pitch change
```

Degradation is declared, hysteretic (it will not oscillate at the threshold), and reported. Halving
is the good case for a fixed domain, because a commensurate grid keeps cross-domain interpolation
(§6) exact.

### 6.2 Cross-domain sampling

A graphics frame lands between two physics steps. Rendering the older one stutters and rendering the
newer one is time travel, so the answer is to interpolate — and because the domain knows its own grid
and its own `Interp`, it does it for you:

```java
Sampled<Double> shown = physics.sample(body::x, Interp.DOUBLE);
frames.each(step -> node.x(shown.at(step.at())));   // smooth at any refresh rate
```

**The one-step lag is real and is stated rather than hidden.** Interpolation can only look backwards:
at render moment `t` the step after the latest one has not happened, so blending towards it would
mean inventing it. `Sampled` therefore renders the value as it was at `t - period`. That is a full
step of latency, and it is the price of smoothness — the same trade the fixed-timestep-with-
interpolation pattern has always made. `latest()` is the escape hatch for a caller who would rather
have the latency than the smoothness.

Alpha is clamped: past the newest step the value holds rather than extrapolating into a future nobody
has computed.

### 6.3 Tempo: nesting, localization, and scaling

Everything nests, everything is local, and everything can be scaled — including time. But **real time
is never bent**: the root frame stays locked to the wall, and what scaling changes is the *actual rate*
at which a region's declared work meets it.

A **`Tempo`** is a local time context: a parent, an origin, and a **scale** — how fast local time runs
relative to its parent. Scales compose down the tree, so a 1:2 region inside a 2:1 region runs at 1:1.

```java
Tempo world   = kron.tempo();                  // root: scale 1:1, locked to the wall
Tempo bullet  = world.child(Ratio.of(1, 4));   // quarter speed
Rate  physics = bullet.fixed(hz(50));          // declared 50 Hz; *actual* 12.5 Hz against the wall
```

#### The scale is an exact ratio, never a double

This is the whole reason `Ratio` exists rather than a `double`, and the argument is the one that
produced `Metro`: **a nested chain of floating-point scales accumulates error, and accumulated error in
a time grid is drift.**

Worse than drift, for the eventual purpose. Kronometer is meant to generate music the way ChucK does,
by nesting integer ratios — 3:2 inside 4:3 inside 7:4. With exact rationals, the grid lines of two
sibling tempos **coincide exactly** at their common multiple, which is the difference between a
polyrhythm locking and a polyrhythm smearing. With doubles they coincide approximately, which is to say
they don't.

So the arithmetic stays in integers: a scale is a pair of `long`s, composition is exact rational
multiplication (reduced by gcd), and a grid line is computed as
`origin + n · period · q / p` with `Math.multiplyExact` — **from the origin, never from the last
line**, so the single unavoidable rounding per line is bounded at one nanosecond and never accumulates.
This is the same discipline as §5.4, one level up.

It also makes commensurability *decidable* rather than hoped for: the framework can say exactly when
two rhythmic layers realign, which is a useful musical question and a useful precomputation hint (a
repeating structure has a known period). The M3 note that "halving is the good case" for a `degrade`
ladder becomes a special case of it.

**A scale may be irrational, and that is still fine** — but only if it is stored *symbolically*. If a
scale is some transform of a ratio rather than the ratio itself (see §15.6), the tempo keeps the
integers and the transform, and evaluates to nanoseconds only when converting a specific grid line from
the origin. What must never happen is pre-multiplying it into a `double` and nesting that.

#### Flatten for time, keep the tree for phase

Rational multiplication is commutative and associative, so for *timing* a nested chain can be collapsed
to one effective scale, and is.

That collapse is **not** valid for anything hypercomplex hanging off the same tree. The intended musical
use drives Cayley–Dickson phase objects from the ratio hierarchy, and past level 2 those products are
non-commutative and then non-associative — quaternions, then octonions. A structure whose composition
is non-associative cannot be flattened without changing what it means. So the tempo tree is retained as
a tree, and only the timing projection through it is collapsed.

The canonical case falls out correctly without special-casing. A dynamic `frames` domain is driven by
ticks, so it is wall-locked and unaffected; a fixed physics domain inside a 0.25× tempo steps four
times less often against the wall while each step still advances 20 ms of local simulation time. The
picture keeps rendering at 60 fps and the world moves at quarter speed. That is slow motion, and it is
just arithmetic.

**Moments stay global; durations are local.** This is the one decision that keeps the whole thing
tractable. If `Moment` were frame-relative, moments from different frames would be silently
incomparable — a bare nanosecond count with no way to know which frame it belonged to, which is a bug
generator. So `Time.now()` is always a global moment, and a `Tempo` converts *declared durations and
grids* into global time. `advance(ms(250))` inside a 0.25× tempo advances global time by one second,
and that is the only place the scale is applied.

**Slip stays at the root.** A nested tempo cannot slip independently, for exactly the reason a domain
cannot (§6.0): that would be a second clock. Scaling is a reparameterization of one timeline, not
another timeline. This is the same finding, generalized, and it is what "real time is always kept
synchronized" means mechanically.

#### Why this had to be settled before M5

Because **a tempo is a node in the dependency graph, and its scale has a horizon.**

| Scale is… | The local→global map is… | Consequence for prediction |
|---|---|---|
| a constant | an exact affine map, knowable forever | precomputation unaffected; convert and carry on |
| changed discretely at moment *T* | affine, but only until *T* | everything predicted past *T* in that subtree is retracted — the §7.2 machinery, no new mechanism |
| itself animated by a `Curve` | the *integral* of a pure function of time — so still pure, still predictable | predictable exactly as far as the scale curve's own horizon |
| following live input (a scrub slider) | knowable only at `now` | the whole subtree's horizon collapses to `now`, and nothing in it is precomputed |

That last row is the honest one, and it is right: you cannot render ahead through a time warp somebody
is currently dragging. And the third row is the pleasing one — an animated slow-motion ramp is a
`Curve`, so it stays predictable, and the region inside it stays precomputable.

So the horizon rule of §7.1 gains a term:

```
horizon(s in tempo T) = min( horizon(s) mapped through T,
                             horizon(T.scale),
                             horizon(T.parent) )
```

An animated scale makes the map non-affine, which is a real implementation cost. The plan is to
**sample the scale on the tempo's own grid and integrate piecewise-linearly**, giving an exact affine
map per interval — cheap, exact within a segment, and no worse quantized than everything downstream
already is.

## 7. The signal graph

Vue's model, with time as a dependency.

| Kind | Role | Future |
|---|---|---|
| `Time` | the built-in source | perfectly known |
| `Cell<T>` | a mutable source, written from the effectful world | unknown past its scheduled writes |
| `Curve<T>` | a pure function of time: `T at(Moment)` | fully known over its extent |
| derived — `kron.computed(…)` | a function of other signals | as known as its least-known input |
| `Effect` — `kron.effect(…)` | a side-effecting sink | n/a; runs on the timeline at `now` |

`Cell`, `Curve` and derived all implement `Signal<T>`: `T at(Moment)` and `T get()` (= `at(now)`).
Dependencies are tracked by *reading* — `get()` inside a `computed` or `effect` registers the edge,
exactly as in Vue. No manual wiring, no annotations, no reflection.

### 7.1 Horizon — the number that runs everything

**One number was doing two jobs — corrected in M4.** The original formula conflated "how far ahead is
this value *determined*" with "how far ahead does it still *change*", and they are neither the same
number nor propagated the same way.

```java
// Determined until — what prediction may rely on.  Minimum over sources.
Moment horizon(Signal<?> s):
    Time            -> FOREVER
    Curve           -> FOREVER
    Cell   held     -> FOREVER          // predicted constant; see below
           driven   -> FOREVER          // the curve, then its final value
           live     -> now              // declared volatile: the only real blocker
           following-> the source's
    Tempo           -> horizon of its own scale, and of its parent's  (§6.3)

// Varying until — where sampling can stop and store one constant.  Maximum over sources.
Moment varyingUntil(Signal<?> s):
    Cell   driven   -> where the curve ends
           held/live-> now
    derived         -> the longest-lived of its inputs
```

The asymmetry is the substance: **a value is determined only as far as its least-known input, but it
keeps changing as long as its longest-lived input does.** Prediction needs the first to know how far it
may compute; it needs the second to know when to stop sampling and store a single value instead of a
thousand identical ones.

**Why a merely-held `Cell` is predicted as constant.** This section used to specify `now` — the
conservative reading, since nobody can know whether a cell is about to be written. Implementing
invalidation showed the conservatism is both unnecessary and ruinous: most cells are held constants, so
`now` would mean nothing downstream of any cell is ever precomputed, and the whole prediction subsystem
would idle. Optimism is *sound*, not a gamble, because **effects never run ahead of `now`; only values
do.** A write retracts every prediction after its own moment, and that happens before any effect could
have acted on one — so being wrong costs recomputation, never correctness. `Cell.live()` is the explicit
opt-out for a value fed from outside at unpredictable times: a promise about volatility, not about the
value.

A `Cell` written only by live input has `horizon == now`: unpredictable, evaluate at `now` only. The
*same* cell, once something schedules its future, becomes predictable up to where that schedule ends:

```java
Cell<Float> lift = kron.cell(0f);
Signal<Float> shadow = kron.computed(() -> dp(2 + 6 * lift.get()));
kron.effect(() -> card.elevation(shadow.get()));

lift.follow(mouse.pressure);                       // horizon = now — nothing is precomputed
lift.drive(Curve.tween(0f, 1f, ms(200), OUT_CUBIC));  // horizon = now + 200 ms — all 12 frames
                                                      // of `shadow` are computed immediately
```

Nothing about `shadow` or the effect changed. The graph reclassified itself because its input did.
**That is the mechanism the whole design rests on**, and it is why the pure and effectful worlds can
be mixed per-component without the user separating them by hand.

### 7.2 Invalidation

A prediction is a promise about the future, so anything that changes the future retracts it: an
unscheduled `Cell` write, a driving curve cancelled or retriggered, a domain rate change. The kernel
discards cached values strictly *after* the offending moment and re-renders. This is a DAW
invalidating its render-ahead buffer on an edit, and it is exactly the machinery `Animator` needs to
retrigger an animation from its current interpolated value rather than snapping.

### 7.3 Cost, and when not to predict

Precomputing 100 ms and invalidating at 5 ms throws away 95 ms of work. So prediction is a per-signal
policy — `EAGER` (default for `Curve` and anything with a long horizon), `LAZY` (compute one step
ahead), `NEVER` — and the kernel demotes a signal to `LAZY` automatically when its measured
invalidation rate makes eager work a net loss. Memory is `horizon × rate × width`, bounded and
reported per domain.

### 7.4 Glitch-freedom

All reads within one moment see one consistent version of the graph. This falls out of the model
rather than needing a scheduler: effects run on the baton, and the graph is evaluated *at a moment*,
so there is no window in which half the graph has updated. No intermediate states, no diamond
double-evaluation.

## 8. Precomputation

For each domain, a pool of worker threads evaluates predictable signals at that domain's sample
points, from `now` forward to `min(now + lookahead, horizon)`, into a per-signal ring buffer. It
needs no baton, no locks and no ordering, because the work is pure — that is the entire justification,
and it is why purity is a load-bearing property rather than a style preference.

**This pool is its own executor with real parallelism, and it is never the kernel's carrier** (§3.1).
The kernel runs on a single pinned carrier precisely because the baton serializes it; precomputation
is the opposite kind of work and wants every remaining core.

Consequences worth stating plainly:

- **A finite, near horizon is computed whole.** A 200 ms tween at 60 Hz is twelve samples; compute
  them once and the animation costs nothing again. Past `varyingUntil` (§7.1) the tail collapses to a
  single stored constant rather than a thousand identical samples.
- **Reading at `now` is a buffer index.** No allocation, no math, no lock.
- **The horizon is the jitter budget**, which is §10.

### 8.1 Refill in bursts, not dribbles — measured in M5

The single most important implementation fact about this section, and it was not obvious from the
design ([benchmarks/precompute.md](benchmarks/precompute.md)):

> A sliding window in steady state needs **one new sample per step**, whoever computes it.

So topping the buffer up by one sample at the end of every step is *precisely* the work lazy evaluation
would have done — one sample at a time, on the timeline — with a buffer bolted on for decoration. The
first working implementation did exactly that and measured, to within noise, **exactly no improvement**.

The fill therefore waits until the buffer is half empty and then refills the whole window in one
parallel batch. Most steps become a pure buffer read; the arithmetic happens in one go. That is the only
shape in which "embarrassingly parallel" cashes out, and it is what an audio engine does, for the same
reason. Measured at 100 ms of lookahead over 48 kHz: **2.3×**.

Two corollaries fall out, and both are limits rather than details:

- **Lookahead is what buys the speedup.** A two-frame window has nothing to burst — half of two is one —
  so it measures at parity. Prediction is *free* there, not useful. A domain that declares no lookahead
  should expect no gain.
- **Small batches must not go near the pool.** A platform-thread round trip is ~14 µs (§3.1); a sample is
  a few. Dispatching a one-sample top-up made prediction three times slower than not predicting, so
  batches below a measured threshold stay on the timeline.

## 9. Interpolation

`Ease` shapes a normalized `float`; `Interp<T>` blends two values of a type. Both are pure, so
everything built from them is predictable by construction.

### 9.1 Easing

`Ease` is a `FloatUnaryOperator` — the standard library (`LINEAR`, `IN_OUT_CUBIC`, …) plus
`Ease.bezier(x1, y1, x2, y2)`, `Ease.steps(n)`, and `Ease.of(f -> …)` for anything you want.

### 9.2 Interpolating a type

`Interp<T>` is `T at(T a, T b, float t)`. Scalars, vectors and colors are the easy cases (with the
usual caution that color wants a perceptual space, not raw sRGB).

**Rotation is not the easy case, and a lerp is wrong.** Naive interpolation on Euler angles gimbals,
on wrapped angles takes the long way round through 359°, and on quaternion components changes
angular velocity mid-arc. So rotation ships as first-class interpolators — `Interp.SLERP` for
quaternions, `Interp.ANGLE` taking the shortest arc with wrap — rather than being left to the caller
to get wrong.

### 9.3 Two kinds of smoothing, and why the distinction is structural

This is the one place where §1's classification has teeth:

| | **Closed-form** | **Integrated** |
|---|---|---|
| Examples | tween + ease; an analytic damped spring toward a **fixed** target | a spring chasing a **live** target; exponential smoothing of live input |
| Definition | `value = f(t)` | `value = f(previous value, dt)` |
| Horizon | the end of the motion — fully predictable | `now` — depends on an unknown input *and* on its own past |
| Precomputed | yes, usually in one shot | no |
| Requires | nothing | a **fixed-rate domain** |

That last row is the payoff. An integrated smoother stepped at a variable `dt` is framerate-dependent
— the classic bug where a UI feels different at 60 Hz and 144 Hz — so Kronometer **rejects binding an
integrated smoother to a dynamic domain**. Put it on a fixed domain and let the graphics domain
interpolate it (§6). The type system knows the difference because the graph does.

## 10. Blocking on the timeline

Blocking calls are allowed on the timeline. They cost **zero logical time** — logical time is not
wall-clock time, and a shred that blocks for 40 ms still occupies one moment.

What blocking actually costs is *wall-clock slack*, and the lookahead buffer is exactly that:

> **You may block for as long as you have precomputed future.** Block for 40 ms with 100 ms of
> rendered audio ahead of you and nothing is heard. Block for 40 ms with one frame of lookahead and
> you drop two frames.

`kron.slack()` returns that number — the wall-clock time until the nearest domain's buffer runs dry —
so the budget is readable rather than folklore. The rule that follows is narrow and checkable:
**block only in shreds whose outputs are not inputs to the current lookahead window.** Lookahead
cannot absorb a stall that is upstream of what it is rendering; if you block while holding the pen,
the buffer is already empty.

Overrun the budget and the shortfall becomes **slip** (§5.1). A buffer cannot invent time, so the
schedule moves later by exactly what you overspent, and the domain's settlement policy decides
whether that is repaid, forgiven or held. The two numbers are one system: `slack()` is the budget,
`slip()` is what it cost when you exceeded it.

`offload(work)` remains available for work that is genuinely unbounded — file I/O, network, image
decode — moving it to an ordinary executor (**its own**, never the kernel's single carrier — §3.1) and
delivering completion as a timeline event. Under the
virtual clock it must declare a logical duration (`offload(work, s(2))`), because an external
completion time is not a reproducible input.

## 11. Diagnostics

The failure modes of this design are specific, so the kernel names all of them:

- **Underrun** — a domain's buffer ran dry. Reported with the domain, the shortfall, and the shred
  that was holding the baton. This is the one that matters; it is what a click or a stutter *is*.
- **Slip** — `kron.slip()` per domain, and its trend, which is the number that actually predicts
  trouble: a slip that drains is a hiccup, a slip that plateaus is a capacity problem, and a slip
  that climbs is a system heading for a hard resync. Degradation (§6.1) and resync are both reported
  as transitions, so the log says *when the machine stopped keeping up*, not merely that it did.
- **Segment overrun** — `kron.segmentBudget(ms(50))`, a wall-clock budget for one zero-time segment.
  Distinct from underrun: this fires even when lookahead absorbed it, so you learn before your users
  do.
- **Prediction waste** — precomputed samples discarded per second, per signal. The signal to move a
  policy to `LAZY`, and the input the automatic demotion in §7.3 uses.
- **Horizon map** — `kron.horizons()`, every signal and how far ahead it is knowable. The direct
  answer to "why is this not being precomputed", which is otherwise an invisible property.
- **`Trace`** — `(moment, shredId, kind)` for every yield, spork, trigger and cancellation. Under the
  virtual clock two runs of a scenario produce identical traces, so a test asserts on the whole
  schedule rather than on sampled state.

## 12. Animation, end to end

```java
Rate frames = kron.dynamic();
Cell<Float> open = kron.cell(0f);

Signal<Length> height  = kron.computed(() -> Length.dp(0 + 240 * open.get()));
Signal<Color>  tint    = kron.computed(() -> Color.mix(BASE, ACCENT, open.get(), OKLAB));
kron.effect(frames, () -> { panel.height(height.get()); panel.background(tint.get()); });

// expanding: one line, and the next 12 frames of both derived signals exist before the first draws
open.drive(Curve.tween(0f, 1f, ms(200), OUT_CUBIC));

// interrupt it halfway — invalidation retracts the predicted future, and the reversal
// starts from the current interpolated value rather than snapping
open.drive(Curve.tween(open.get(), 0f, ms(120), OUT_QUAD));
```

The procedural form is still there when sequencing reads better as code, because a shred keeps its
call stack across time:

```java
spork(() -> {
    tween(ms(200), OUT_CUBIC, f -> card.elevation(dp(2 + 6 * f)));   // returns when done
    advance(ms(50));
    tween(ms(400), IN_OUT_SINE, f -> card.opacity(f));
});
```

`Timeline.sequence/parallel/stagger` composes declaratively; `Animator` keys running animations so a
retrigger cancels and continues rather than snapping.

## 13. Where it sits in the stack

**Kronometer is a substrate.** It is a peer of atchung and tactroller; vexelray-gui sits on top:

```
                      vexelray-gui                        ← depends on kronometer
                     /      │       \                       (never the reverse)
              vexelray  tactroller  atchung
                                       │
    ┌──────────────────────────────────┘
kronometer-atchung  ──  bridge (peer-to-peer, like atchung-elektroq)
        │
kronometer-core  ──  kernel, graph, precompute. zero dependencies, none of them ecosystem.
        │
kronometer-anim  ──  easing and interpolation libraries. depends only on core.
```

> **`kronometer-core` depends on nothing in the ecosystem, and no Kronometer module depends on
> anything layered above it.** An adapter lives in the *consumer's* repo, or — for a true peer — in a
> Kronometer bridge module.

| Module | Provides |
|---|---|
| `kronometer-core` | Kernel and time: `Kron`, `Shred`, `Time`, `Dur`, `Moment`, `Trigger`, `Clock`, `Overrun`, `Rate`, `Trace`. Graph and prediction: `Signal`, `Cell`, `Curve`, `Effect`, horizons, precompute pool. The `Ease` and `Interp` *interfaces*. Pure Java, no dependencies. |
| `kronometer-anim` | The libraries: `Ease` curves, `Turn` (angles in turns, shortest-arc), `Hyper` (the Cayley–Dickson tower with dimension-generic slerp), `Tween`, `Motion`, `Animator`, `Smooth`. Colour interpolation deliberately absent — it needs the consumer's colour type, so it belongs in the adapter. |
| `kronometer-atchung` | Await an Atchung `Topic` as a yield point; publish on the timeline; drive a `Pump`; carry elektro-Q messages onto it. Peer-to-peer, mirroring `atchung-elektroq`. |
| `kronometer-demo` | The showcase — headless, so it stays free of a GUI dependency. |

### Integration notes

- **VexelRay-GUI depends on Kronometer, not the reverse.** There is deliberately no
  `kronometer-vexelray` module, and removing it cost nothing — every seam is already generic.
  `Clock.driven()` is stepped by an opaque `long nanos`; `Interp<T>` and `Ease` are functional
  interfaces, so `Interp<Color>` and `Interp<Length>` are a few lines *over there*; an effect body is
  ordinary code, so `() -> node.elevation(height.get())` needs no adapter, `Node` handles being
  write-only and thread-safe already. That repo constructs the `Kron`, ticks it per frame in
  `INLINE`, exposes it as `gui.kron()`, and closes it with the window.
- **Atchung** is a peer with zero dependencies that will not grow one on Kronometer, so the bridge
  lives here. A shred awaiting a `Topic` resumes at the logical moment the publish was observed; a
  `Topic` can also **drive a `Cell`**, which is the natural way live input enters the graph (with
  `horizon == now`, as it should be). `State<T>` remains the right tool for "what is true now" —
  Kronometer times *when* it changes.
- **Tactroller** is the same shape as vexelray-gui, so by the layering rule the scripted-scenario
  harness belongs in **that** repo depending on `kronometer-core`. See §15.4.

## 14. Native-image

Plain Java: no reflection, no proxies, no service loading, no runtime scanning. Virtual threads and
`ScopedValue` are supported by GraalVM native-image, and dependency tracking is by read-registration
rather than annotation scanning, so the graph needs no metadata either. As elsewhere, the `native`
profile is opt-in so ordinary builds stay fast.

## 15. Open questions

1. ~~Package / groupId~~ — **resolved: `sibarum.kronometer`**, matching atchung and tactroller.
   (vexelray-gui's `dev.vexelray.gui` appears to be unintentional; renaming it is a separate job in
   that repo.)
2. ~~Naming~~ — **resolved in M1: ChucK's event ships as `Trigger`** (`fire()`, `broadcast()`,
   `await(t)`), leaving `Signal` for the reactive supertype in M4, where the whole industry now
   expects that name. The one place Kronometer's ChucK lineage is deliberately obscured.
3. **How far does prediction see through a `Cell`?** Today: to the end of writes already *scheduled*.
   A stronger version would let a `Cell` declare a **model** of its future — "this is a spring that
   will settle in ~180 ms" — making even input-driven values predictable with a confidence bound, and
   speculatively rendered. That is a research direction, not v1, but the horizon API should not
   foreclose it.
4. **Which repo owns the Tactroller scenario harness?** The layering rule says tactroller, depending
   on `kronometer-core`. That adds a dependency to a repo that already ships, so it is your call.
5. ~~Should `Rate` domains nest or be time-scaled?~~ — **resolved: yes to all of it.** Everything
   nests, everything is local, everything scales, including time itself; real time stays synchronized
   and actual rates get adjusted. Designed as `Tempo` in §6.3, with the horizon consequence worked
   out there: a tempo is a graph node, and an animated scale stays predictable while a
   live-driven one collapses its subtree to `now`.
6. ~~What does `tan(p/q)` parameterize?~~ — **resolved: angles are in turns, so it is just a slope.**
   `p/q` is rise over run; the corresponding angle is `arctan(p/q)` measured in turns. Two consequences,
   both good:
   - **The time path stays exactly rational end to end.** The scale *is* the slope `p/q`, so nothing
     irrational enters the grid arithmetic, and §6.3 needs no symbolic-transform escape hatch after all.
   - **Phase in turns can be held exactly.** A turn wraps at 1, not at an irrational 2π, so a phase is a
     rational (or exact fixed-point) fraction and wrapping is taking the fractional part — no rounding
     at the wrap, which is where radian-based phase accumulators bleed precision.

   Niven's theorem now cuts the harmless way: the *angle* being irrational is fine, because only the
   slope has to be exact.
7. **Cayley–Dickson phase as a signal value type.** Not a core concern — hypercomplex numbers are a
   value type for the graph and an `Interp` implementation, so they belong in `kronometer-anim` or a
   later `kronometer-music`. Two things reach back into the design, though: `Interp` should be shaped so
   quaternion `SLERP` is the *level-2 special case* of a general normalized hypercomplex interpolation
   rather than a hard-coded one (M6), and nesting depth in the tempo tree plausibly maps onto
   Cayley–Dickson level — the other reason §6.3 keeps the tree rather than flattening it.
8. **Audio ticks, and whether `samp` should be the base unit.** Music generation means a real audio
   domain: `hz(48_000)` with block processing, and a `lookahead` that makes the precompute pool earn its
   keep. Nanoseconds are not exactly divisible by common sample rates, so a sample-accurate grid wants
   `samp` as its base tick rather than `ns`. This is why `Dur` is a record over one field instead of a
   bare `long` alias — a scale can be added — but it is deferred until the audio path is real, and
   §6.3's rational tempo arithmetic is the thing that makes it tractable when it arrives.
