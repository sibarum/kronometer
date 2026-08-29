# Render on demand

> **The contract between a host loop and the kernel: draw when something changed, sleep when nothing
> did, and never trade smoothness for either.** Written as the design for the vexelray-gui integration,
> then corrected by building it. General to any host that owns its own event loop.

**Measured, on Win32 + Vulkan, two applications.** Both idled at ~140 fps drawing a still window, with
the kernel reporting 99–100 % of those frames unnecessary. Now:

| regime | budget | rate |
|---|---|---|
| unfocused | `forever` | **0 fps** — parked until an event |
| focused, idle | 200 ms | **5 fps** — the floor, so nothing waits longer than that |
| animating | 16 ms | **60 fps** — the ceiling |

A worker thread's post wakes the loop for **exactly one frame** and it parks again.

> **Do not read the floor as a failure to reach zero.** It is the design: see
> [the bound comes first](#the-bound-comes-first-and-the-wakes-are-an-optimisation). Zero-while-focused
> is achievable only by enumerating every source of change correctly and forever, and the cost of being
> wrong is a UI that intermittently ignores clicks.

A retained-mode GUI has to answer "does this frame need drawing", and the usual answer is a hand-written
dirty flag: set it on every mutation, clear it on every present, and spend the rest of the project
finding the mutation that forgot. Kronometer answers it from the dependency graph instead. The flag
still exists — it is a timeline entry, posted by invalidation — but **nothing writes it by hand**,
because the thing that made an effect dirty is the same thing that recorded the dependency.

That is the whole idea. What follows is the wiring, and the two modelling rules that decide whether it
works or quietly does nothing.

## The property that makes this safe

**While anything is varying, the sleep budget is zero.** `Kron.sleepTimeout()` returns `Dur.ZERO` the
moment an effect reads something mid-flight, so a loop that sleeps on it *never sleeps during an
animation* — it free-runs exactly as an unconditional loop does. Render-on-demand engages only when
there is provably nothing to draw.

So there is no ratio to tune and no smoothness being traded away. The only latency this adds is on the
first frame after idle: one wake plus one frame, which is the same click-to-pixel an unconditional loop
already pays.

## What each layer owns

Four small pieces. Each lives in the layer that owns the fact, and each degrades to today's behaviour
if the one below it is missing.

### 1. The platform: a wait that can time out

Two methods on the OS window abstraction, and **both default to doing nothing** so an unported platform
keeps its unconditional loop rather than freezing:

```java
/** Block until an event arrives or timeoutNanos elapses (Long.MAX_VALUE = indefinitely).
 *  Default: return at once, leaving the caller to spin as it does today. */
default void waitEvents(long timeoutNanos) { }

/** End a waitEvents() from any thread. Default: no-op. */
default void postWake() { }
```

On Win32 that is `MsgWaitForMultipleObjectsEx(0, NULL, ms, QS_ALLINPUT, MWMO_INPUTAVAILABLE)` and
`PostMessageW(hwnd, WM_NULL, 0, 0)`. This is the only genuine blocker. Everything above is inert without
it, and harmlessly so.

Three things the implementation insisted on:

- **`MWMO_INPUTAVAILABLE` is load-bearing, not a flag to tidy away.** Without it the call counts only
  input arriving *after* the wait begins, so anything posted between the last peek and the wait is not
  new — and the loop sleeps through the very event it was about to handle. A hang whose frequency
  depends on timing.
- **Round the timeout down, and never to zero.** Win32 offers milliseconds. Down, because waking early
  costs one wasted pass and waking late costs a deadline, and never-late is the whole value of the
  budget. Never to zero, because a sub-millisecond budget rounded to zero turns the wait back into the
  spin it exists to remove.
- **The wait is thread-scoped, not window-scoped.** One call covers every window on the loop thread,
  because they share its message queue. That is what makes a single call in the shared loop correct
  rather than a main-window special case.

### 2. The framework loop: one JDK-typed seam

A GUI framework should not acquire a dependency on Kronometer to do this, so the seam is a JDK type —
the same trick that lets a widget declare its timing need as `(DoubleConsumer, Runnable)` and stay
clock-free:

```java
public GuiApp pacing(LongSupplier nanosUntilNextFrame)     // default () -> 0, i.e. today
```

`Long.MAX_VALUE` means *may block indefinitely*. The loop consults it **after** presenting, so the
first frame is never delayed:

```java
long budget = pacing.getAsLong();
if (budget > 0) {
    window.waitEvents(budget);
}
```

### 3. The application: two lines

```java
krono.kron().onWork(app::postWake);                         // end the sleep when a worker posts
if (maxFrames <= 0) {                                       // not on a capped run — see below
    app.pacing(() -> krono.kron().sleepTimeout().nanos());
}
```

**Never set pacing on a run with a frame cap.** A capped run is a script — a capture, a bounded check —
and blocking makes N frames of a still window take forever rather than N presents. Both flags live in
the caller, so the caller decides; the framework deliberately does not second-guess it, because a
`pacing` that silently stopped applying under some other argument would be a worse surprise than one
documented not to mix.

**`onWork` has one slot and last call wins.** So a `Kron` must have exactly one `onWork` call site in
the whole application. Two, and the loser is whichever ran first — a profiler or a debug harness
installing its own leaves the real loop with nothing to wake it, and the window stops responding for
reasons that look unrelated to whatever was added. (Found the hard way: the profiler written to measure
this feature was the thing that broke it. It now takes the real wake as an argument and owns the single
call.) If something else needs to observe wakes, hand it the real listener to call.

### 4. The widgets: model discrete changes as discrete

No code, one rule, and it is the rule that decides whether any of the above helps. See below.

## The two rules

### Discrete changes must be discrete

A blinking caret is the canonical trap, and it is a trap because both spellings look right and produce
the same picture.

| Modelled as | `varyingUntil` | What the loop does |
|---|---|---|
| `Curve.forever(t -> …)` — a continuous function | forever | budget is **always zero**; the whole application runs flat out to animate one caret |
| a two-state cell set on a 500 ms metro | not varying | next deadline is the next flip; the loop sleeps 500 ms, wakes, flips, sleeps |

Seventy times the power for an identical result. The same applies to spinners, marching dashes, pulsing
highlights — anything periodic whose value is a step rather than a slope.

Get this right and a frame-rate ceiling is largely unnecessary: the existing deadline machinery already
produces the correct rate for anything discrete. A declared ceiling is only for genuinely *continuous*
motion that you want under-sampled.

### Several clocks, one loop: take the minimum

One event loop serves every window, and a process may hold more than one `Kron` — a hosted window that
attaches its own is the ordinary case, not an exotic one. The pacing supplier must return the
**minimum** budget across all of them.

Taking the maximum, or only the main window's, starves whichever window is animating while another is
idle. It presents as "the animation is broken", never as "the loop is wrong", which is why it is worth
writing down rather than discovering.

## Present mode

Sleeping bounds the idle case. It does nothing for the animating case, where the loop deliberately
free-runs — so that case wants **FIFO present** (vsync) to bound it. Two throttles, one per regime:
`sleepTimeout()` for idle, the presenter for motion. A loop with neither renders 2.3 frames per
displayed frame; a loop with only the first still does, whenever anything moves.

## Failure modes, and what prevents each

| Failure | Symptom | Prevented by |
|---|---|---|
| `onWork` never wired | window frozen; animation running perfectly on a kernel nobody ticks | `sleepTimeout()` returns `FOREVER` **only** when a listener exists; unwired it returns `ZERO` and you get today's redraw |
| One signal varying forever | render-on-demand "doesn't work"; nothing to point at | `kron.whyBusy()` names the effect. Log it once when the app has not been quiescent for N seconds |
| Platform has no wait | nothing changes | `waitEvents` defaults to returning at once |
| A worker mutates the graph | used to be intermittent | refused every time, with `onTimeline` named in the message — see [adopting.md](adopting.md) |
| Frame budget composed as a maximum | one window starves | stated above; assert it in a test with two clocks |
| Two `onWork` call sites | frozen window, apparently unrelated to the change | one call site per `Kron`; give observers the real listener to call |
| `pacing` set on a capped run | a capture never finishes | guard on `maxFrames <= 0` in the caller |

**Verify the wake path from a thread that is neither the timeline nor the loop.** It deserves a live
self-test rather than only a unit test, because its failure mode is the worst available: the window stops
responding, the animation that was started runs perfectly on a kernel nobody is ticking, and nothing
anywhere says so. Posting a no-op the way a click handler would and asserting a frame follows is four
lines and it is the difference between a log line and a day:

```
wake path OK: a worker post woke the loop and produced a frame
```

## The bound comes first, and the wakes are an optimisation

**Read this before the channel table below, because it is what makes that table survivable.**

Everything that follows is an attempt to enumerate every source of change so that each can wake the
loop. That approach is correct and it does not hold. Five channels surfaced in one integration, each
hidden behind the last, and the fifth was found by a user narrowing a bug to a single menu item. The
list is not closed: the next queue anyone adds, drained once per frame and announcing nothing, silently
restores a window that ignores a click. **You cannot test for the absence of a wake**, and the symptom —
occasionally unresponsive, fine again the moment the pointer moves — points nowhere near its cause.

So the guarantee must not depend on the list being complete:

```java
app.idleRefresh(200_000_000L)    // 5 Hz floor while focused: nothing waits longer than this
   .maxFrameRate(16_666_666L);   // 60 Hz ceiling while animating
```

A missed wake now costs **latency instead of a hang**. That is a different kind of defect — bounded,
uniform, survivable — and it is the whole point. Five wakes a second that mostly find nothing, against
the 140 frames an unconditional loop was spending, is not a performance question.

Two things keep it cheap. **Focus**: a window nobody is looking at parks indefinitely, so the floor is
paid only where it can be perceived. **The ceiling**: `sleepTimeout()` reports zero while anything is
varying, which means "as fast as you can" — on a presenter that does not block, that was 140 fps to
feed a 60 Hz display.

And the wakes stay, because a floor alone is not enough for good interaction: 200 ms on a click is
perceptibly sluggish. With both, the common paths respond in zero frames and the uncommon ones respond
within the floor. **The wakes make it feel instant; the floor makes it correct.** Only one of those is
allowed to depend on somebody having thought of everything.

## Channels worth waking for

Each buys zero-latency response on a path users hit often. None is load-bearing any more: get one wrong
and the floor catches it. Five surfaced in one integration, each found only after the previous fix made
it visible - which is the evidence for the section above, not a list to be completed.

| Channel | Woken by | Symptom when missing |
|---|---|---|
| time passing | `Kron.onWork` | animations never start |
| the retained tree | `Gui.onWork`, from every mutation publish | click changes state, screen keeps the old picture |
| the application's own per-frame queues | a wake after every input handler returns | the *effect* arrives late — see below |

### The third one, and how to recognise it

An application that drains its own queues in the frame hook — a history to restore, a file to open, a
preview to render — has a channel neither of the other two can see. A click handler drops a request on
such a queue: nothing was mutated, no clock was touched, and the request will be executed by a drain
that only runs if a frame runs. To a parked loop it does not exist.

**The tell is that the effect, not the picture, is what arrives late.** An animation the handler queued
starts *from the beginning* whenever a frame eventually comes, instead of being found already in
progress. That single observation separates this from the previous section: a drawing problem shows a
motion mid-flight, an execution problem shows it starting fresh. It is worth knowing because the two
feel identical to a user and have nothing in common underneath.

Rather than wake from each queue — a list that is never finished, and whose next entry is written by
whoever adds a feature — hang the wake on what every such path has in common: **a handler ran**. Waking
after each one returns covers every queue, including the ones not written yet, and costs one frame per
input event that finds nothing to do.

```java
this.handlers = task -> base.execute(() -> {
    try {
        task.run();
    } finally {
        wake();
    }
});
```

### And drain the whole queue

The same integration had three queues polling **one** request per frame. That is survivable only while
the loop redraws unconditionally, because the next frame is always a few milliseconds away. A loop that
parks has no next frame to leave the remainder for, and a half-drained queue cannot ask for one: the
backlog simply stops. Under render on demand, a per-frame drain empties or it stalls.

## Two sources of "something changed", not one

**The mistake this document made on its first pass, and it shipped.** The last section states the
constraint — *anything that changes has to be in the graph* — and then the wiring consults only the
clock. A retained-mode GUI has its own mutation channel, and the clock cannot see it.

The failure is specific and it is nasty. Click handlers run off the GUI thread, so a handler publishes
its mutation **after** the frame that dispatched the click has already drained, reconciled and
presented. The loop then asks the clock, is told there is nothing to do, and parks with the mutation
queued. The state changed; the screen keeps showing what it showed before. It comes right the instant
any stray OS event arrives — so it presents as **"the UI only updates when I move the mouse"**, which
points at input handling rather than at the loop, and it is worst on a touchpad, where a click carries
no pointer movement to save it.

So a host loop needs a wake from **every** channel that can make a frame due:

```java
krono.kron().onWork(app::postWake);      // time passing
gui.onWork(app::postWake);               // the application changing something
```

Neither is redundant. Either alone leaves half the reasons a frame is owed unaccounted for.

A `hasPendingWork()`-style query is **not** a substitute: a handler still running on a worker has not
published yet, so there is nothing for the query to report. Only the wake closes that race, and it
closes it because `MWMO_INPUTAVAILABLE` makes a wake that lands between reading the budget and entering
the wait still count.

### What it looks like when it is right

Fixing this raised idle from 0 fps to ~2 fps in the calculator — and that is the fix, not a regression.
A blinking caret publishes about two mutations a second, so two frames a second is the correct answer.
**Before the fix the caret was frozen as well**, for the same reason and unnoticed, because a frozen
caret reads as a design choice. Idle went from 142 fps to 2 fps: still seventy times less work, and now
with a caret that blinks.

The general lesson is worth more than the fix: **a source of change that no wake covers does not fail
loudly.** It fails as a UI that feels broken in a way nobody can attribute. Enumerate the channels.

## What this is not

It is not a dirty flag with extra steps, and the distinction is worth being precise about. A dirty flag
is a claim maintained by whoever remembers to maintain it. This is a **derivation**: read-registration
records what an effect depends on, invalidation posts a timeline entry, and the aggregate over the
timeline, the rate domains and everything mid-flight is what `sleepTimeout()` reports. Nothing in
application code participates, so nothing in application code can forget.

The cost of that is a real constraint, stated plainly: **anything that changes has to be visible to
something that can wake the loop.** Inside the graph it is derived for you. Outside it — a GUI's own
retained tree, say — it needs its own wake, and the section above is what happens when it does not get
one. A dirty flag you cannot forget to set, in exchange for enumerating your channels of change once.
