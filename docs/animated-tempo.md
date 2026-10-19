# Animated tempo scales: plan

A `Tempo` whose speed changes over time. Its local clock becomes the **integral** of that speed rather than a
ratio times elapsed time, and `Tempo.horizon()` stops returning `FOREVER` unconditionally. Deferred from M4, M5
and M6 (see the roadmap); this is its own milestone, because it changes how `Tempo` converts time rather than
adding to it.

The consumer that wants it first is faizuray: a phase clock and a tempo clock as two sibling tempos, with
time-stretch and pitch-bend done by gliding one against the other. Nothing here knows that. It is clock
arithmetic, and it has to be correct for physics and animation as well as for audio.

## What exists today

- `Tempo` holds a constant `Ratio` scale, caches the product of its ancestors' scales as `effective`, and
  converts with one affine map from a rebased origin: `elapsedAt`, `globalAt`, `toLocal`, `toGlobal`.
- `rescale(Ratio)` is a discrete change. It rebases the subtree at `now`, so local time stays continuous, and
  retracts every prediction with `graph.invalidate(at)`.
- Three callers depend on the map. `Cell.drive` anchors a curve in local time and works out its end as a
  global moment. `Rate.gridLine(n)` is `tempo.globalAt(originLocal + n·period)`. `Rate.gridIndexAtOrBefore`
  is the inverse, `floor((elapsedAt(at) − originLocal) / period)`.
- `Tempo.horizon()` returns `FOREVER`, and **nothing calls it**. The term that §6.3 adds to the horizon rule
  has never been wired in.

## Step 0 — a bug to fix first

`Cell.drive` computes `curveEnd` once, through the tempo's map at that moment. A later `rescale` does not
recompute it. Take `TempoTest.rescaleInvalidates`: a 100 ms ramp, halved after 50 ms, really ends at 150 ms,
but the cell still reports `varyingUntil() == 100 ms`. `Predictor` stores a constant tail from
`varyingUntil` on, so a predicting domain would show the ramp frozen from 100 ms to 150 ms. Meanwhile
`get()` on the timeline is correct, because it goes through `valueFromCurve`.

**Fixed.** `TempoTest.predictionFollowsARescale` showed it was worse than reading the code suggested: the
predicted ramp froze at its value at the stale end and held it for good, so it never reached 1.0. Now
`Cell.curveEnd()` derives the end from the tempo on each call, `tempo.globalAt(curveStartLocal + extent)`.
Animated scales would have made the stale value wrong all the time rather than only after a rescale.

## Design

### 1. The scale is a piecewise speed profile, and every piece ends on an exact `Ratio`

A tempo holds a sequence of **segments**, each in its parent's time. A segment is one of:

| Segment | Speed over the segment | Local elapsed (the integral) | Inverse | Exact? |
|---|---|---|---|---|
| `Hold(r)` | constant `r` | `r·t`, affine | affine | yes, as today |
| `Linear(r0 → r1, T)` | straight line in speed | `r0·t + (r1 − r0)·t² / 2T` | root of a quadratic | yes, to the nanosecond, via integer arithmetic |
| `Exponential(r0 → r1, T)` | constant ratio per unit time, which is how pitch and tempo are heard | `r0·T·(ρ^(t/T) − 1) / ln ρ`, with `ρ = r1/r0` | closed-form log | double precision within the segment; exact at its ends |

**The knot rule.** Every segment ends on an exact `Ratio`, and from that moment the tempo converts through
the flattened `Ratio` again. (As built, no rebase happens at the knot; see step 4 below.) So exactness is lost only inside a glide and comes back at its end. Sibling grids that
are commensurate before a glide and after it still meet on the nanosecond. During a glide nothing is
commensurate anyway, so nothing is lost.

`Linear` is exact in the sense that `Ratio.scale` is exact: integer arithmetic, rounded once per conversion,
measured from the segment's origin rather than from the previous result, so the error never accumulates. `t²`
overflows a `long` past about three seconds, so this path uses `BigInteger` (or `Math.multiplyHigh` if a
benchmark asks for it). The inverse is an integer square root plus a fix-up step, described below.

A segment with an arbitrary `Curve<Double>` speed, integrated numerically, is **not** in the first cut. §6.3
proposed sampling the speed on a grid and integrating piecewise-linearly, which is just a chain of `Linear`
segments, so it can be added later as a builder over those segments. No new conversion code would be needed.

### 2. Speed is continuous; its slope is not, yet

A glide always starts from the current speed, so speed is continuous at every knot. Local time is then
continuous with a continuous first derivative, which no discrete `rescale` can give. The speed's *slope* still
has a corner at each knot. For audio that is a corner in the pitch contour, mild but not nothing. A smoothstep
segment (cubic speed, quartic integral, Newton inverse with the same fix-up) removes it. It is listed as a
follow-up, not built in the first cut, so the exact paths land and get measured first.

### 3. Conversion composes up the tree, and keeps the flattened fast path

`effective = parent.effective × scale` only holds while every scale on the path to the root is constant. Two
paths:

- **All affine** (no ancestor mid-glide): keep today's flattened `Ratio` conversion. That is what makes
  "3:2 and 4:3 meet at 72 ms on the nanosecond" true, and it must stay the default.
- **Any segment non-affine**: compose. `elapsedAt(g) = segment.local(parent.elapsedAt(g) − segmentStart)`, and
  `globalAt` runs the inverses from the leaf up. That is one rounding per level, so at most `depth / 2` ns, and
  it never grows with time.

`effectiveScale()` stays, documented as the scale *at now*, the instantaneous one. It is a value for display,
never for conversion.

### 4. The map and its inverse form a Galois pair, by definition

Rate relies on `gridLine` and `gridIndexAtOrBefore` agreeing. If they disagree by one nanosecond, a step fires
twice or never. Today that holds because the affine map is exact in the cases that matter. With a quadratic
inverse it has to be **defined**, not hoped for:

> `globalAt(L)` is the **least** global moment `g` with `elapsedAt(g) ≥ L`.

The implementation computes a candidate from the closed form, then steps ±1 ns until that condition holds. So:

- `elapsedAt(globalAt(L)) ≥ L`
- `globalAt(elapsedAt(g)) ≤ g`

Both hold for every segment type, by construction. This is the property test that makes the whole milestone
trustworthy. It requires speed to stay strictly positive, which `Ratio` already enforces ("logical time does
not run backwards"). Pausing remains `Handoff.hold`, not a speed of zero.

### 5. API

```java
Tempo stretch = kron.tempo().child("bpm", Ratio.ONE);

stretch.glide(Ratio.of(3, 2), ms(800));                     // linear in speed, from wherever it is now
stretch.glide(Ratio.of(3, 2), ms(800), Glide.EXPONENTIAL);  // even steps in log-speed
stretch.rescale(Ratio.HALF);                                // unchanged: a discrete step

stretch.follow(scrub);                                      // speed from a Signal<Ratio>; see §6
stretch.live();                                             // declared volatile: horizon collapses to now

Signal<Dur> local = stretch.time();                         // this tempo's local clock, as a signal
```

`glide` replaces any glide in progress, starting from the speed at `now`. That
is `Animator.retarget` for a clock: nothing snaps, and the new glide is fully predictable.

`time()` is the seam faizuray needs. Its `Phase` today integrates frequency over global time. With this, a
phase can be `f · tempo.time()`, and a pitch-bend becomes a glide on the phase tempo. Following faizuray's rule
2, `time()` must be a plain signal that memoizes nothing and reads the map at whatever moment it is asked
about, never a `kron.computed`.

### 6. The honest horizon

Two numbers, as for signals, and both are finally wired in:

| Tempo state | `horizon()` (determined until) | `varyingUntil()` (changing until) |
|---|---|---|
| holding | `FOREVER` | `now` |
| gliding | `FOREVER`: the glide, then its final speed, both known | the glide's end |
| following a signal | that signal's horizon | that signal's `varyingUntil` |
| `live()` | `now` | `now` |

Then take the minimum (for `horizon`) or the maximum (for `varyingUntil`) of this tempo's entry and its
parent's, all the way to the root.

Holding and gliding are **optimistic** in exactly the sense §7.1 settled for `Cell`. A glide can be replaced at
any time, and the replacement retracts every prediction after its moment before an effect could act on one.
Only `live()` and following a live signal genuinely bound prediction. That is §6.3's fourth row: you cannot
render ahead through a time warp someone is dragging.

`follow(signal)` is not new integration machinery. Each change of the followed value becomes a short glide to
the new speed (the slew time is a parameter, defaulting to one step of the fastest fixed domain in the
subtree). So a scrub control produces continuous speed, and between inputs the subtree stays predictable
exactly as far as the signal's horizon allows.

Where the horizon gets consumed:

- **`Cell.horizon()`**: a held or driven cell becomes `min(FOREVER, tempo.horizon())`, not `FOREVER`. A
  following cell takes the minimum of that and its source's horizon.
- **`Cell.varyingUntil()`**: a held cell stays `now`, because a held value does not care how fast its tempo
  runs. A driven cell uses the fix from step 0.
- **`Predictor.collect`**: the fill window is capped at the **domain's** tempo horizon as well as the
  signal's. If a domain's own grid lines move with live input, the domain cannot be filled ahead, whatever
  its signals say.
- **Retraction**: `glide`, `follow` changes and `live` call `graph.invalidate(at)`, the same as `rescale`.
  Narrowing that to the subtree's dependents is a follow-up that M7's targeted invalidation makes possible.
  It is not needed for correctness.

### 7. What does not change

- Moments stay global, durations stay local, and slip stays at the root (§6.3). A glide reparameterizes one
  timeline; it never adds a clock.
- Dynamic domains still ignore tempo.
- `Step.dt()` in a fixed domain is still the local period exactly. Only the wall spacing between steps
  varies, which is the point: physics in a gliding tempo integrates with a constant `dt` and slows smoothly.
- `Dilated` and `Handoff.phase()` read grid lines through `Rate`, so they should follow without edits. They
  are on the audit list below rather than assumed.

## Steps

1. **The step-0 bug**: a failing test for a predicted driven cell across `rescale`, then derive `curveEnd`
   from the tempo on each call.
2. ~~**A segment model inside `Tempo`**, with only `Hold`, and `rescale` re-expressed as "end the current
   segment, start a `Hold`".~~ **Done.** `Segment` is a sealed interface whose only member is `Hold`.
   `Tempo.scale()` switches over it, so adding a glide will not compile until `scale()` handles it.
   `rescale` calls `begin(next)`, which rebases, swaps the segment and invalidates. No behaviour change: 210
   tests green.
3. ~~**Galois-pair conversion**: define `globalAt` as the least moment and add the fix-up step.~~ **Done**,
   with one change of design that the tests forced: `elapsedAt` now rounds **down** instead of to nearest.
   With rounding to nearest, the least moment in a 1:4 tempo that reaches local 20 ms is 79 999 998 ns, so
   exact grids landed 2 ns early. Floor forward and ceiling back keep every exact case exact, and only
   inexact ones round, by under a nanosecond. `TempoMapTest` covers four things: the closed form against
   the fix-up (200 000 cases, including the `BigInteger` path), the Galois pair over 100 random nested
   chains with rescales, grid lines read back as themselves in two inexact tempos, and 3:2 and 4:3
   still meeting at 72 ms. 214 tests green.
4. ~~**`Linear` glides**: the exact integral, the integer-square-root inverse, the composed (non-flattened)
   path while any ancestor is mid-glide, and the knot rebase back to the flattened path.~~ **Done**, with
   the knot handled differently from what this plan first said:
   - **No event at the knot.** Each tempo's map from global time has at most two pieces, fixed when a
     segment begins anywhere in its ancestry. The composed piece (the parent's map, then this tempo's
     segment, anchored in parent-local time) runs until the latest knot in the chain. The flattened piece,
     one exact `Ratio`, runs from there. A glide therefore ends without anything happening at its end, and a
     prediction made before the knot is the value seen after it. A rebase at the knot would have shifted
     rounding by a nanosecond and broken the "prediction is invisible" property.
   - **A segment is defined for all parent time**, at its starting speed before its start and at its end
     speed after its extent. A finished glide is settled into a `Hold` only at the next rebuild, which
     changes no values.
   - **A rebuild re-anchors by offset.** Old local times for the subtree are read first, then each tempo
     keeps its segment and moves only its offset. So a parent's change cannot make a child jump, even where
     composed and flattened rounding differ.
   - **`r0` is a `BigInteger` fraction**, because a glide that replaces one in progress starts from the
     exact speed reached, whose denominator carries the earlier extent. `r1` is a `Ratio`, per the knot
     rule. `scale()` returns the speed being glided to.

   `GlideTest` checks seven things: the exact integral, the rate across the knot and across a retarget,
   2 ms grid spacing at 3:2 after a glide, a 2:1 child exactly doubling a gliding parent, the Galois pair
   over 100 random chains of glides and rescales, and prediction on and off agreeing through glides for a
   domain inside the tempo and one outside it. It also checks that the square-root candidate lands within
   one nanosecond of the least moment. 222 tests green.
5. ~~**`Exponential` glides.**~~ **Done.** `tempo.glide(to, over, Glide.EXPONENTIAL)`, with the public
   `Glide` enum (`LINEAR`, `EXPONENTIAL`) choosing the shape; `glide(to, over)` stays linear.
   - **During the glide**, the integral is computed in `double` with `StrictMath.expm1`, and the inverse
     with `log1p`. `StrictMath` gives the same bits on every platform and under native-image.
   - **At the end**, the local time it reaches is fixed once as a whole nanosecond, and an exact `Ratio`
     hold follows. So nothing inexact outlives the glide.
   - **Retargeting:** every segment now reports `speedAt` as an exact fraction, so `glide()` retargets from
     any shape to any shape. Mid-way through an exponential glide that fraction is the exact value of the
     nearest `double`, so a retarget is continuous to about one part in 10¹⁶, not exactly.
   - **Monotone in practice:** the integral is accurate to about a millionth of a nanosecond, while local
     time grows by at least the slower speed every nanosecond. So rounding cannot step it backwards at any
     speed this side of a millionth. That is argued, not proven; a test checks it over 200 random glides.

   Tests check three things: the speed is the geometric mean halfway, the end lands within 1 ns of
   `D·(ρ − 1)/ln ρ`, and the rate stays continuous across exponential→linear→exponential retargets. They
   also check 2 ms grid spacing after an exponential glide to 3:2, and that the `log1p` candidate is within
   1 ns of the least moment. The random Galois and prediction-invisibility tests now mix both shapes.
   227 tests green.
6. ~~**Horizons**: `Tempo.horizon()` and `varyingUntil()` per the table, wired into `Cell` and
   `Predictor.collect`. Then `follow` and `live`.~~ **Done**, with three changes from §6:
   - **A held cell keeps `FOREVER`.** Its value does not depend on how fast its tempo runs, so only a
     *driven* cell takes its tempo's horizon, and only until its curve ends. An end already passed cannot
     move, since the map is continuous at now.
   - **`follow(source, slew)` takes an explicit slew** rather than defaulting to a domain's step. It is a
     reactive effect, so it sees changes that invalidate the graph (an input adapter's `set()`), not a
     source that varies along a curve; `glide` is for speeds known in advance.
   - **`release()`** clears `live` and stops following. Rescale and glide do not clear `live` (the
     same promise-about-behaviour rule as `Cell.set`), but they do stop following.

   `Rate.tempo()` is now public, and `Predictor.collect` caps the fill window at the domain's tempo
   horizon as well as the signal's. `TempoHorizonTest` checks the table and these cases:
   - a driven cell in a live tempo is not predicted, but in a gliding one it is;
   - a held cell in a live tempo is still predicted;
   - a domain in a live tempo is not filled ahead even for a knowable signal;
   - `follow` gives continuous speed, and an explicit glide stops it;
   - prediction on and off agree with a followed tempo and a live one rescaled per event.

   Undoing either piece of wiring makes its test fail. Without them, the prediction-invisibility test still
   passed, because retraction made the over-eager prediction wasted work rather than wrong output.
   234 tests green.
7. ~~**`Tempo.time()`**, plus a note in `consumer-notes.md` on building faizuray's phase over it.~~ **Done.**
   `time()` is one cached plain signal: it reads `elapsedAt` at whatever moment it is asked about, registers
   itself as a dependency, and reports the tempo's horizon. `TempoTimeTest` checks three things: it matches the map
   through an exponential glide, a computed over it inherits the horizon (forever, forever, then `now` when
   live), and a block read at both ends advances 2 ms per 1 ms in a 2:1 tempo, predicted or not. Wrapping the
   clock in `kron.computed` makes that last test fail exactly as faizuray's rule 2 warns: 2 ms per block without
   prediction, zero with it. `consumer-notes.md` §10 is the faizuray guidance. It covers reading `time()`
   directly, the one change needed on faizuray's side (a `Closed` phase that reports its tempo's horizon), and
   exactness per glide shape.
8. **Audit and measure**: `Dilated`, `Handoff`, and `gridLine` cost under a glide against the affine path.
   That is a `kronometer-bench` entry with a gate, at 48 kHz in a gliding tempo.

## Exit criteria

| Criterion | How it is shown |
|---|---|
| A glide is continuous | local time and speed equal on both sides of each knot, with zero tolerance on the `Ratio` at the end |
| Exactness returns after a glide | 3:2 and 4:3 sibling grids, one of them glided from 1:1, meet on the nanosecond at their common multiple |
| The map and inverse agree | property test over generated nested chains of `Hold`, `Linear` and `Exponential`: both Galois inequalities, every case |
| No step is lost or doubled | a fixed domain in a tempo that glides repeatedly for ten virtual minutes: indices contiguous, `dt` always the local period |
| Nested glides compose | parent and child gliding at once give the same moments as the hand-composed integral |
| Prediction is invisible | the M5 property, prediction on and off giving identical traces, extended to graphs that contain glides |
| The horizon is honest | a driven cell in a `live()` tempo is not precomputed; in a gliding tempo it is; a retarget retracts exactly the predictions after its moment |
| The step-0 bug stays fixed | the predicted ramp across `rescale` ends at 150 ms, not 100 |
| It is cheap enough | `gridLine` under a `Linear` glide within a stated factor of the affine path, recorded in `benchmarks/` |

## Deliberately not in this milestone

- **Smoothstep segments** (§2), until the exact paths are measured.
- **Arbitrary `Curve` speeds**, beyond a builder that chains `Linear` segments.
- **`samp` as the base unit** (§15.8). A sample-accurate audio grid wants it, and gliding tempos make it more
  pressing, but it changes `Dur` rather than `Tempo`.
- **Anything about phase alignment.** The Kuramoto clutch, and keeping overtones coherent while one clock
  glides against another, belongs in faizuray. This milestone gives it two exact clocks and an honest account
  of how far ahead each one is known.
