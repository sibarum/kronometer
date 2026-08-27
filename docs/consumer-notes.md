# Notes from the first consumer — vexelray-gui

> **Status: a request list, not a plan.** Written from the consumer side while adopting Kronometer
> (M8), against vexelray-gui `781de08` and kronometer `62dda7f`. Nothing here is a defect report; it
> all works. It is the list of things vexelray-gui **had to build for itself** that look general, plus
> the one thing it **cannot build at all** without help from the kernel.
>
> Each item states the evidence rather than the wish, because the useful question is not "would this
> be nice" but "did a consumer re-derive it, and would the next one".

## 0. The M8 exit criterion, answered

> *"whether the seams are actually as generic as §13 claims"* — roadmap, M8

**They are.** There is no `kronometer-vexelray` module and nothing wanted one. Specifically:

| §13 claim | How it actually went |
|---|---|
| `Clock.driven()` stepped by an opaque `long nanos` | The GUI ticks it from the frame loop's `beforeFrame` hook. `INLINE` returns with the batch complete, so mutations an animation posts are on the bus before the reconciler runs — the frame that presents a value is the frame that computed it. |
| `Interp<T>` is a functional interface, so `Interp<Color>` is a few lines over there | Exactly that. `Colors.OKLAB` and `Lengths.LERP` are 20 lines each in the adapter. **The decision to keep colour interpolation out was right**, and this is the evidence: the rule that matters (interpolate perceptually, never raw sRGB) needed the consumer's colour type *and* the consumer's colour space to state. |
| An effect body is ordinary code | `() -> node.overlay(cue.at(t, box))` needed no adapter. `Node` being write-only and thread-safe is what makes it free. |
| `Topic` drives a `Cell` at `horizon == now` | Not yet exercised — the GUI's input still enters through its own dispatch. Reported as untested rather than as working. |
| Tests | `Kron.driven()` plus hand-supplied ticks made every timing test deterministic with no sleeps. The GUI's widget module contains no clock at all and is tested by *declining* to call the ramp. |

The one seam that mattered most was not in §13 and is worth naming: **a widget declares its timing
need as two JDK types** (`DoubleConsumer progress, Runnable done`) and `KronoGui.ramp` satisfies it
without either module knowing the other exists. That is what lets `vexelray-gui-widget` animate while
staying clock-free, and it is reusable by anything with the same problem. Kronometer should keep
satisfying that shape **by accident rather than by contract** — see §4.

---

## 1. `Kron` cannot be asked when it next needs to run — and that blocks a real feature

**The only item here that is not a convenience.**

vexelray-gui's architecture specifies a render-on-demand loop: block in `waitEvents(timeout =
animating ? nextDeadline : ∞)`, wake on input or a worker's publish, zero frames when idle. It is
**not built**, and the reason is that the GUI cannot answer either half of that condition. As shipped
it renders unconditionally every iteration, paced only by `PRESENT_MODE_FIFO_KHR` — a continuous 60 Hz
redraw of a completely still UI.

`Signal.varyingUntil()` is per-signal and exactly right. What a host loop needs is the aggregate, and
only the kernel has it: the timeline queue, each domain's next boundary, and which effects have live
handlers. A consumer cannot reconstruct any of that without reaching inside.

```java
Moment  Kron.nextDeadline();   // earliest of: next timeline entry, next step of a domain with live
                               // handlers, and the varyingUntil of anything an effect reads
boolean Kron.isQuiescent();    // nothing scheduled, nothing varying — the host may sleep indefinitely
```

Notes on shape, from the consumer's side:

- **Advisory is fine, and is what a driven clock needs.** Under `Kron.driven()` the host supplies the
  ticks, so `nextDeadline()` is not a promise the kernel will run then — it is the kernel saying "there
  is no point ticking me before this". That is precisely the value a host that owns its own wait wants.
  A conservative answer (too early) costs a wasted frame; there must be no answer that is too late.
- **This is the read-only counterpart to M7's idle-parking.** The kernel already knows how to park when
  nothing is scheduled under a paced clock. Everything needed is in there; it is not exposed to a host
  that parks on its own behalf.
- **It wants a diagnostic sibling.** "Why is my loop never idle" is currently unanswerable. `predictions()`
  exists; something like a count of live handlers per `Rate`, or what is currently varying and until
  when, would make a never-quiescent kernel debuggable instead of mysterious.

Until this lands, every Kronometer consumer with its own event loop either spins at the display rate or
guesses.

---

## 2. `Tween.run` is 90 % of the ramp a consumer needs, and differs in two general ways

`Tween.run(Rate, Dur, Ease, Sink)` is very close to what vexelray-gui wanted. It ended up
re-implementing it as `KronoGui.ramp` — about 40 lines of fiddly kernel work — because of two
properties, **neither of which is about GUIs**.

**a. There is no leading `0`.** `run` calls `metro.tick()` before its first sample, so the first
`sink.at` lands a full step into the duration. A four-frame ramp starts the consumer at 0.25, and a
consumer that treats its first sample as "this is where the motion begins" begins by jumping to
wherever that landed.

`Tween` already makes the argument for the *other* endpoint, and makes it well:

> *"an animation that stops one sample short of its target is a bug you find later, in the form of a
> shadow that never quite settles"*

The same sentence is true at the start and is not currently honoured there. `KronoGui.ramp` delivers
`0` before a single frame has elapsed for this reason.

**b. There is no completion that outlives the value.** `run` returns in the same moment as
`sink.at(1f)`. A consumer that tears down on arrival — hides a node, drops an overlay, releases a
buffer, closes a voice — therefore runs in the same batch as the final sample, so the end value is
written and then overwritten before anything is presented, and the last state anyone actually saw is
the second-to-last sample. At 60 fps over 200 ms that is a fifth of the animation still on screen at
the instant it disappears. It reads as a cut at the end of a fade.

`KronoGui.ramp` defers `done` to the step *after* the one that carried `1`. This is not a rendering
concern: it is the general rule that **a value's consumer must have had a chance to commit it before
being told the motion is over**, and it applies to anything with a present/flush/commit boundary.

Proposed, in `kronometer-anim`:

```java
// 0 delivered before any time passes; 1 delivered exactly; settled one step after the step carrying 1
Tween.ramp(Rate domain, Dur extent, Ease ease, Sink progress, Runnable settled);
```

Both properties are cheap to add and both are currently invisible to a test that checks only that the
endpoints were reached — which is why they were found by running an application rather than by a suite.

---

## 3. Everything is on the timeline; every consumer's handlers are not

`Time.spork`, `Animator.play` and `Animator.retarget` all require the timeline. Every GUI event handler
runs on a worker. Starting an animation from a click is the single most common thing anyone wants to
do, and it is refused by default.

`KronoGui` therefore routes **every** scheduling method through one helper, and invented one type:

```java
void onTimeline(Runnable work) {           // run now if already there, else post to the next moment
    if (kron.isOnTimeline()) work.run(); else kron.post(work);
}
```

```java
final class Scheduled { ... }   // a promise about identity rather than a reference to a shred
```

`Scheduled` exists because a spork posted from off the timeline has no `Shred` to return *yet*, and a
handle that is sometimes `null` is worse than no handle at all. It makes cancel-before-start and
cancel-after-start equivalent, which is the only property that makes it usable from a handler.

Both are general, both are the most-copied thing in the adapter, and both are small:

- **`Kron.onTimeline(Runnable)`** — four lines, and every adapter will write them.
- **An off-timeline-safe spork returning a `Scheduled`-shaped handle.** This would also make
  `Animator.play` and `Animator.retarget` callable from a handler thread, which they are not today —
  and `Animator` is otherwise exactly the right abstraction for a consumer to reach for first.
  (`Animator.running` being a plain `HashMap` is correct *because* the class is timeline-only; if
  `play` becomes callable from anywhere, the posting has to happen before the map is touched rather
  than around it, or the map has to change. Worth deciding deliberately rather than discovering.)

If only one thing in this document is taken, take this one: it is the difference between "the adapter
is a few lines" and "the adapter is a class every consumer has to get right".

---

## 4. Supersession has two legitimate policies and Kronometer names one

`Animator.play(key, motion)` already gives keyed supersession, and it is closer to what vexelray-gui
needed than the GUI realised while building around it. But its cancellation is documented as:

> *"the outgoing motion unwinds — its `finally` blocks run at this moment — before the replacement
> begins"*

For a **one-shot acknowledgement** that is exactly backwards. vexelray-gui plays cues — a sweep across
a field that just took a command, a ring around one that refused — into a slot whose identity value is
null. When a second cue supersedes a first, the loser's teardown would clear the paint the winner just
put down: a flash that erases itself part-way through, and only when the user was quick enough to
produce two events inside one duration.

So `Cues` keeps its own identity table in which a superseded play's **samples and its completion both
become no-ops**. The loser is *abandoned*, not unwound.

Both policies are correct, for different things:

| Policy | Right when | Example |
|---|---|---|
| **Unwind the loser** | it owns a resource that must be released, or a state that must be restored | a shred holding a voice, a lock, a reservation |
| **Abandon the loser** | its teardown would undo the winner, because both write the same slot | a one-shot decoration; anything whose "cleanup" is "put the shared thing back" |

This is raised as a **design question, not a request**. A defensible answer is "the consumer's problem"
— but then it is worth saying so next to `play`, because the current wording reads as though unwinding
is simply what supersession means, and a consumer that believes it will ship the erasing-flash bug.

---

## 5. Reduced motion needs to reach motion already in flight

vexelray-gui wants a global `motion(DISABLED)` that collapses every timeline to its end state and keeps
the loop at zero idle frames. Its current answer is *not starting* the animation — which is the honest
collapse for a cue (a cue exists only in the middle, so "instantly" means "not at all") and is no
answer at all for motion that is already running when the setting changes. Reduced motion is a system
preference: it arrives at runtime, mid-animation, from outside.

A kernel-level `kron.settle()` — every driven cell jumps to its curve's end, every ramp delivers its
final value and its completion, the effects retire — would serve every consumer, and pairs with §1:
after a settle, `isQuiescent()` should be true. Whether that is a kernel operation or a tempo trick
(scale → ∞) is Kronometer's call; the consumer only needs the guarantee that it is *complete* rather
than *cancelled*, because a cancelled animation leaves the property wherever it happened to be.

---

## 6. Nice-to-haves

- **Put the easing guidance on `Ease`.** vexelray-gui learned expensively that a transition wants **two
  curves, not one**: opacity is linear because it has no place to arrive at and the eye reads it about
  as given, while displacement is eased-out because decelerating into a position is what reads as
  weight. Running both off one curve is most of what makes a transition feel like a slideshow. Concretely:
  an `OUT_CUBIC` fade is 87 % complete at the halfway point of its own duration, so the visible part
  finishes in the first third and the rest is a stall — which does not read as a slow fade, it reads as
  a delay followed by a jump. **Invisible to a test that checks the endpoints**, because the endpoints
  are perfect either way. One paragraph on `Ease` would carry that to every consumer instead of each
  finding it by shipping it.
- **State the perceptual rule where an implementer will read it.** The roadmap says colour interpolation
  stays out because it needs the consumer's colour type — agreed, and it worked. But the *rule* it
  hands over ("interpolate perceptually, never raw sRGB") lives only in the roadmap. It belongs in
  `Interp`'s javadoc, which is what someone writing `Interp<MyColor>` will actually have open.
- **`Rate` could report whether it has live handlers.** Needed by §1 anyway, and independently useful:
  it is the difference between "this domain is idle" and "this domain is being stepped for nothing".
- **A worked example of the one-frame cross-domain latency.** `Sampled` states it honestly; a consumer
  meeting it for the first time in a frame loop would benefit from seeing which of the two orderings
  (input-then-clock, or clock-then-input) it is choosing between. vexelray-gui ticks input first and
  the clock second, and that ordering is load-bearing.

---

## 7. What we do *not* want Kronometer to take

Recorded so the layering stays honest, and so a future contributor does not read the list above as an
invitation.

- **Colour or `Length` interpolation.** Confirmed correct to leave out. The adapter is trivial, and the
  colour space is a decision the consumer owns.
- **Any knowledge of nodes, boxes, pictures, or widgets.** The effect body being ordinary code is the
  whole seam and it needs nothing else.
- **A Kronometer type in the widget layer.** `vexelray-gui-widget` deliberately names no clock; its
  timing seam is `(DoubleConsumer, Runnable)` precisely because both are JDK types. If §2 lands, it
  should keep satisfying that shape incidentally. A `kronometer-widget` contract would invert the
  layering and cost the widget module its clock-free tests.
- **Deadline awareness inside widgets.** If §1 lands, the deadline is asked once at the application
  edge, by the thing that owns the loop. No widget should ever learn that a frame is due.

---

## 8. For reference — what vexelray-gui built on top

The actual surface area a first consumer needed, so Kronometer can judge which parts belong upstream.
Items marked ⬆ are the ones this document argues about.

| In `vexelray-gui-krono` | What it is |
|---|---|
| `KronoGui.attach/tick/close` | Construct, step per presented frame, close with the window |
| `KronoGui.onTimeline` ⬆ | Run now or post — every other method routes through it (§3) |
| `Scheduled` ⬆ | Off-timeline spork handle; cancel before or after start (§3) |
| `KronoGui.ramp` ⬆ | 0→1 with exact endpoints and a deferred completion (§2) |
| `KronoGui.bind/bound` | Signal → node property, once per frame |
| `KronoGui.retarget/animate` | Thin wrappers over `Animator`/`Tween`, plus `onTimeline` |
| `KronoGui.spork/after/every` | Thin wrappers, plus `onTimeline` |
| `KronoGui.live` | Read an app-written value into the graph as volatile |
| `Colors.OKLAB`, `Lengths.LERP` | `Interp<T>` for the consumer's own types — correctly here, not there |
| `frames` domain | A dynamic `Rate` stepped once per tick — it runs after everything else scheduled in that window, which is the right order for a pass that reads what the rest of the frame just produced |

Roughly two thirds of that is genuine adapter work that belongs in the consumer's repo, which is the
result §13 predicted. The other third is §2 and §3.
