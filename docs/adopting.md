# Adopting Kronometer

Adding a clock to an application that already has a frame loop. Read [README.md](../README.md) for
what the thing *is*; this is the wiring, the three mistakes everybody makes, and how to tell what went
wrong when nothing moves.

## The whole wiring

```java
try (Kron kron = Kron.driven()) {                       // stepped by your loop
    Rate frames = kron.dynamic("frames");               // one step per tick

    Cell<Double> lift = kron.bound(frames, "lift", 0.0, card::elevation);

    app.run(() -> kron.tick());                         // once per presented frame
}
```

Five lines, and the last one is the only one in the loop. `bound` creates a cell and lands its value
on a setter every frame; from then on you animate the cell and forget the setter exists:

```java
new Animator(kron).retarget(lift, 1.0, ms(200), Ease.OUT_CUBIC);
```

`kron.close()` cancels everything still alive and lets it unwind on the timeline, so `finally` blocks
run. Put it in try-with-resources, or call it when the window closes.

## Which clock

| | For | Ticked by |
|---|---|---|
| `Kron.driven()` | an application with a frame loop | you, once per presented frame |
| `Kron.realtime()` | a headless service, an audio engine | itself, paced against the wall |
| `Kron.virtual()` | tests | nothing — logical time jumps to the next scheduled moment |

**Under a driven clock, never call `run()`.** `tick()` is what pumps the timeline; `run()` is for a
runtime that paces itself, and calling it from a render loop will not return. The README's opening
example uses `run()` because it is a `realtime()` script — that is the difference, and it is the first
thing to get wrong.

## The three mistakes

### 1. `tick(System.nanoTime())`

`tick(long)` takes a moment on **Kronometer's** timeline, which starts at zero. A raw wall reading is
about 10¹⁵ nanoseconds from `Moment.ORIGIN`, and asking the kernel to run everything scheduled in
between is — measured, one `fixed(ms(20))` domain — seventeen million steps and roughly twenty seconds
of frozen render thread, on the first frame.

Use **`kron.tick()`**, which keeps its own origin. Use `tick(long)` only when you already have an
elapsed value you trust: a scripted run, a test, a swapchain present timestamp you have rebased
yourself.

The damage is now bounded either way — `Driven.maxAdvance(Dur)` caps how far one tick may carry
logical time, at one second by default, forgiving the excess and reporting it through `onOverrun` as a
`SKIPPED`. That is also what saves you at a breakpoint: a twenty-second pause costs one forgiven gap
rather than twenty seconds of replayed simulation into a window nobody was looking at. Turn it off
with `maxAdvance(Dur.FOREVER)` for a scripted run where a long jump is the point.

### 2. Scheduling from an event handler

Every GUI handler runs on a worker thread. The timeline is single-threaded, and `spork`, `Cell.set`,
`Cell.drive` and everything in `Animator` want to be on it. Cross the baton first:

```java
kron.onTimeline(() -> animator.retarget(lift, 1.0, ms(200), Ease.OUT_CUBIC));
```

That runs inline if you are already on the timeline and posts to the next moment if you are not, so it
is also correct during setup, before the kernel is running. Route every scheduling call in your
adapter through it and the question never comes up again.

**The refusal is consistent, and it is worth knowing why that took work.** A worker thread is refused
every time — never sometimes. Exactly three callers may touch the graph without crossing the baton: a
shred, anything during setup, and **the thread that calls `tick()`, between ticks** (which is what lets
`bridge.drain()` and a plain `lift.set(...)` work from your frame loop; `HANDOFF` loses that
concession, because there a batch may be in flight while you are between ticks). Anyone else gets
`NotOnTimeline` with `onTimeline` named in the message, on the first call, on every run. If you are
looking at an intermittent `NotOnTimeline`, you are on a build from before this was true.

### 3. Ticking the clock before delivering input

```java
bridge.drain();          // this frame's events are on the bus
kron.tick();             // the simulation reads them, and the frame renders what it produced
```

The other order costs a whole extra frame of latency, purely from the order two lines were written in.
Within a single domain the same rule applies at finer grain: `Rate.each` runs handlers in registration
order, so anything that *delivers* input registers before the effects that *read* it. See `Sampled`
for the worked example and for the one step of interpolation lag that is unavoidable on top.

## Sleeping when there is nothing to do

A loop that renders unconditionally burns a core drawing a still UI. Two lines, and they belong
together:

```java
kron.onWork(GLFW::glfwPostEmptyEvent);          // once, at startup — how the kernel wakes you

Dur budget = kron.sleepTimeout();               // each iteration — how long you may block
waitEvents(budget.equals(Dur.FOREVER) ? NO_TIMEOUT : budget.toMillis());
```

**`sleepTimeout()` returns `FOREVER` only if `onWork` is wired**, so the deadlock is unreachable rather
than merely documented: forget the wake and you get `Dur.ZERO`, the loop keeps redrawing exactly as it
did before you asked, and `kron.toString()` says `no wake listener`. Burning a core is a bug you file
next week. A frozen window is a bug you lose a day to — because the animation you started is *running
perfectly* the whole time, on a kernel nobody is ticking, and the graph looks healthy.

`onWork` runs on whichever thread did the posting, so the body must be thread-safe, non-blocking, and
must not call back into the `Kron`. Nudging an event queue is the whole intended use.

`nextDeadline()` and `isQuiescent()` are still there and still honest — use them for tests and
diagnostics. Just don't hand-compose them into a wait: that composition is where the missing wake
hides.

Advisory and deliberately conservative in both forms — the answer may be early, which costs a wasted
frame, and it is never late. Ask from the timeline, or from your loop once `tick()` has returned in
`INLINE`; that return is what publishes the kernel's writes to you.

## Testing

Nothing here needs a sleep, a latch or a tolerance. Supply the ticks:

```java
try (Kron kron = Kron.driven()) {
    Rate frames = kron.dynamic("frames");
    List<Double> shown = new ArrayList<>();
    kron.bind(frames, lift, shown::add);

    kron.tick(ms(16).nanos());
    kron.tick(ms(32).nanos());

    assertEquals(List.of(0.0, 0.5), shown);
}
```

A driven run whose tick values the test writes is already fully deterministic — `Kron.virtual()` is
for inventing moments when nobody else will, and a test that ticks by hand is the one inventing them.

Widgets are best tested by **declining to tick at all**: give the widget its timing as
`(DoubleConsumer progress, Runnable done)` and hand it nothing in the test. The widget module then
names no clock and needs none, and in the real application that seam is satisfied by:

```java
Tween.rampOn(frames, ms(200), Ease.OUT_CUBIC, progress::accept, done);
```

`rampOn` samples once per step of the domain — so on a frame clock, once per presented frame — and
delivers exactly `0` before any step has elapsed, exactly `1` on the first step at or after the end,
and `settled` one step later so the consumer has had a chance to present the `1`. It drives a `Cell`
with the curve, so the whole visible future of the ramp stays precomputable. `Tween.ramp` is the other
form: it blocks the calling shred and samples on a `Metro`, which is a grid of its own making and
therefore wrong for a display.

## When nothing moves

```java
System.out.println(kron);
// Kron(Clock.driven(INLINE, maxAdvance 1s), now @1.2s, 74 ticks, 3 shreds, 5 effects, frames[2], busy)
```

The first question is always whether the clock is being driven at all, and `ticks` answers it. If it
has stopped climbing on a render-on-demand loop, the cause is almost always a missing `onWork` — the
kernel has work and no way to say so. Then:

- **`kron.whyBusy()`** — one line per reason the runtime is not quiescent. The answer to "why is my
  loop never idle".
- **`kron.trace()`** — every spork, advance, await, wake and cancel, with moments.
- **`kron.predictions()`** — the precompute buffers, if you are wondering whether prediction is
  helping.
- **`rate.hasHandlers()`** — whether a domain is idle or being stepped for nothing.

## What belongs in your repo, not this one

Roughly two thirds of a first adapter is genuine consumer work, and it should stay yours:

- **`Interp<T>` for your own types.** Colour especially: the space is your decision. The rule that
  comes with it — interpolate perceptually, never in raw sRGB — is on `Interp`'s javadoc.
- **Anything that knows what a node, a box or a widget is.** An effect body is ordinary code; that is
  the whole seam and it needs nothing else.
- **A handle type for scheduling from off the timeline**, if you want cancel-before-start and
  cancel-after-start to be the same operation.

Kronometer deliberately has no module for any GUI, and the first consumer did not want one.
