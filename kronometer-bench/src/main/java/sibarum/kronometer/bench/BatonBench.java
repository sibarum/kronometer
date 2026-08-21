package sibarum.kronometer.bench;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * M0: does the baton cost what the design assumes?
 *
 * <p>Kronometer's effectful model is one kernel thread handing a baton to one shred at a time, which
 * is a park/unpark pair per shred per moment. Everything in
 * {@code docs/architecture.md} §2–§4 rests on that being cheap. This measures it three ways:
 *
 * <ol>
 *   <li><b>Ping-pong</b> — one shred, one round trip. The floor.</li>
 *   <li><b>Fan-out</b> — N shreds waking at one moment. The cost of a moment.</li>
 *   <li><b>Frame simulation</b> — N shreds each waking once per frame, driven from the timeline
 *       priority queue, with the queue churn measured separately so it can be subtracted. The
 *       realistic number.</li>
 * </ol>
 *
 * <p>Run it on the JVM and again as a native image; the native figure is the one that decides the
 * kernel's shape, and the one most likely to differ.
 */
public final class BatonBench {

    private static long sink;

    public static void main(String[] args) throws Exception {
        Gate.Kind gate = arg(args, "--gate", "PARK", Gate.Kind::valueOf);
        boolean kernelVirtual = "VIRTUAL".equals(arg(args, "--kernel", "PLATFORM", s -> s));
        boolean pingOnly = has(args, "--ping-only");
        boolean full = !has(args, "--fast");

        printEnvironment(kernelVirtual);

        double timerNanos = Bench.nanoTimeOverhead();
        System.out.printf("%nSystem.nanoTime() call overhead: %.1f ns  (the floor under any%n"
                + "per-operation latency below; throughput figures are unaffected)%n", timerNanos);

        // The kernel loop's own thread kind is a variable, not a given: a platform kernel thread
        // means every handoff crosses between the OS scheduler and the virtual-thread scheduler.
        runOn(kernelVirtual, () -> {
            pingPong(full);
            if (!pingOnly) {
                latencies(gate, timerNanos);
                fanOut(full);
                frameSimulation(full);
            }
        });

        System.out.printf("%n(sink %d/%d)%n", sink, Bench.sink());
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void runOn(boolean virtual, Body body) throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread t = start(virtual, "kernel", () -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        t.join();
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
    }

    // ---------------------------------------------------------------- benches

    private static void pingPong(boolean full) throws Exception {
        Bench.header("1. Ping-pong — one baton round trip, ns");
        for (boolean virtual : new boolean[] {true, false}) {
            for (Gate.Kind kind : Gate.Kind.values()) {
                if (!full && !(virtual && kind == Gate.Kind.PARK)) {
                    continue;
                }
                try (PingPong h = new PingPong(kind, virtual)) {
                    Bench.row((virtual ? "virtual" : "platform") + " / " + kind, Bench.measure("", h::run));
                }
            }
        }
    }

    private static void latencies(Gate.Kind gate, double timerNanos) throws Exception {
        try (PingPong h = new PingPong(gate, true)) {
            h.run(200_000);                                  // warm before believing anything
            long[] samples = h.sampleLatencies(200_000);
            Bench.Result r = Bench.Result.ofLatencies("", samples).minus(timerNanos);
            Bench.header("2. Ping-pong tail — per-round-trip latency, virtual / " + gate
                    + ", ns (timer overhead subtracted)");
            System.out.printf("  %-38s %10.0f %10.0f %10.0f %10.0f   %,12d%n",
                    "p50 / p90 / p99 / max", r.percentile(50), r.percentile(90), r.percentile(99),
                    r.max(), (long) samples.length);
        }
    }

    private static void fanOut(boolean full) throws Exception {
        Bench.header("3. Fan-out — N shreds woken at one moment, ns per handoff (virtual)");
        for (int n : full ? new int[] {1, 10, 100, 1_000, 10_000} : new int[] {1, 1_000}) {
            for (Gate.Kind kind : full ? Gate.Kind.values() : new Gate.Kind[] {Gate.Kind.PARK}) {
                try (FanOut h = new FanOut(n, kind, true)) {
                    Bench.Result r = Bench.measure("", h::run);
                    Bench.row(String.format("n=%-6d / %s", n, kind), r);
                    System.out.printf("  %-38s %10.1f µs per moment%n", "", r.min() * n / 1_000.0);
                }
            }
        }
    }

    private static void frameSimulation(boolean full) throws Exception {
        Bench.header("4. Frame simulation — timeline queue + baton, ns per handoff (virtual shreds)");
        for (int n : full ? new int[] {1, 10, 100, 1_000, 10_000} : new int[] {1_000}) {
            QueueOnly baseline = new QueueOnly(n);
            double queueNanos = Bench.measure("", baseline::run).min();
            for (Gate.Kind kind : new Gate.Kind[] {Gate.Kind.PARK, Gate.Kind.SPIN_PARK}) {
                try (FrameSim h = new FrameSim(n, kind, true)) {
                    Bench.Result r = Bench.measure("", h::run);
                    Bench.row(String.format("n=%-6d / %-9s (queue %.0f ns)", n, kind, queueNanos), r);
                    System.out.printf("  %-38s %10.1f µs per frame, %.0f%% baton%n", "",
                            r.min() * n / 1_000.0, 100.0 * (r.min() - queueNanos) / r.min());
                    budget(n, r.min());
                }
            }
        }
    }

    /**
     * The design constant this whole milestone exists to produce. Reported at a 10 % frame budget as
     * well as 100 %, because nobody ships an app that spends its whole frame on scheduling.
     */
    private static void budget(int n, double nanosPerHandoff) {
        double frameNanos = 1_000_000_000.0 / 60.0;
        System.out.printf("  %-38s %,10d shreds/frame at 10%% of a 60 Hz budget (%,d at 100%%)%n",
                "", (long) (frameNanos * 0.10 / nanosPerHandoff), (long) (frameNanos / nanosPerHandoff));
    }

    // ------------------------------------------------------------- harnesses

    /** One kernel thread, one shred, doing nothing but passing the baton back and forth. */
    private static final class PingPong implements AutoCloseable {
        private final Gate kernelGate;
        private final Gate shredGate;
        private final Thread shred;
        private volatile boolean stop;

        PingPong(Gate.Kind kind, boolean virtual) {
            this.kernelGate = Gate.of(kind);
            this.shredGate = Gate.of(kind);
            this.shred = start(virtual, "shred", () -> {
                try {
                    while (true) {
                        shredGate.await();
                        boolean done = stop;
                        kernelGate.open();
                        if (done) {
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void run(long ops) throws InterruptedException {
            for (long i = 0; i < ops; i++) {
                shredGate.open();
                kernelGate.await();
            }
        }

        long[] sampleLatencies(int samples) throws InterruptedException {
            long[] out = new long[samples];
            for (int i = 0; i < samples; i++) {
                long t0 = System.nanoTime();
                shredGate.open();
                kernelGate.await();
                out[i] = System.nanoTime() - t0;
            }
            return out;
        }

        @Override
        public void close() throws InterruptedException {
            stop = true;
            shredGate.open();
            kernelGate.await();
            shred.join();
        }
    }

    /** N shreds all scheduled at the same moment; the kernel walks them in order. */
    private static final class FanOut implements AutoCloseable {
        private final Gate kernelGate;
        private final Gate[] shredGates;
        private final Thread[] shreds;
        private volatile boolean stop;

        FanOut(int n, Gate.Kind kind, boolean virtual) {
            this.kernelGate = Gate.of(kind);
            this.shredGates = new Gate[n];
            this.shreds = new Thread[n];
            for (int i = 0; i < n; i++) {
                Gate mine = Gate.of(kind);
                shredGates[i] = mine;
                shreds[i] = start(virtual, "shred-" + i, () -> {
                    try {
                        while (true) {
                            mine.await();
                            boolean done = stop;
                            kernelGate.open();
                            if (done) {
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }

        void run(long handoffs) throws InterruptedException {
            int n = shredGates.length;
            for (long i = 0; i < handoffs; i++) {
                shredGates[(int) (i % n)].open();
                kernelGate.await();
            }
        }

        @Override
        public void close() throws InterruptedException {
            stop = true;
            for (Gate g : shredGates) {
                g.open();
                kernelGate.await();
            }
            for (Thread t : shreds) {
                t.join();
            }
        }
    }

    private record Wake(long moment, long seq, int shred) {
        static final Comparator<Wake> ORDER =
                Comparator.comparingLong(Wake::moment).thenComparingLong(Wake::seq);
    }

    /** The realistic shape: poll the timeline, hand over the baton, re-enqueue one frame later. */
    private static final class FrameSim implements AutoCloseable {
        private final Gate kernelGate;
        private final Gate[] shredGates;
        private final Thread[] shreds;
        private final PriorityQueue<Wake> timeline;
        private volatile boolean stop;
        private long seq;

        FrameSim(int n, Gate.Kind kind, boolean virtual) {
            this.kernelGate = Gate.of(kind);
            this.shredGates = new Gate[n];
            this.shreds = new Thread[n];
            this.timeline = new PriorityQueue<>(n, Wake.ORDER);
            for (int i = 0; i < n; i++) {
                Gate mine = Gate.of(kind);
                shredGates[i] = mine;
                shreds[i] = start(virtual, "shred-" + i, () -> {
                    try {
                        while (true) {
                            mine.await();
                            boolean done = stop;
                            kernelGate.open();
                            if (done) {
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                timeline.add(new Wake(0, seq++, i));
            }
        }

        void run(long handoffs) throws InterruptedException {
            for (long i = 0; i < handoffs; i++) {
                Wake w = timeline.poll();
                shredGates[w.shred()].open();
                kernelGate.await();
                timeline.add(new Wake(w.moment() + 1, seq++, w.shred()));
            }
        }

        @Override
        public void close() throws InterruptedException {
            stop = true;
            for (Gate g : shredGates) {
                g.open();
                kernelGate.await();
            }
            for (Thread t : shreds) {
                t.join();
            }
        }
    }

    /** Frame simulation with the baton removed, so the queue churn can be subtracted. */
    private static final class QueueOnly {
        private final PriorityQueue<Wake> timeline;
        private long seq;

        QueueOnly(int n) {
            this.timeline = new PriorityQueue<>(n, Wake.ORDER);
            for (int i = 0; i < n; i++) {
                timeline.add(new Wake(0, seq++, i));
            }
        }

        void run(long ops) {
            long acc = 0;
            for (long i = 0; i < ops; i++) {
                Wake w = timeline.poll();
                acc += w.shred();
                timeline.add(new Wake(w.moment() + 1, seq++, w.shred()));
            }
            sink += acc;
        }
    }

    // ----------------------------------------------------------------- misc

    private static Thread start(boolean virtual, String name, Runnable body) {
        return virtual
                ? Thread.ofVirtual().name(name).start(body)
                : Thread.ofPlatform().name(name).daemon(true).start(body);
    }

    private static void printEnvironment(boolean kernelVirtual) {
        boolean nativeImage = "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
        System.out.printf("Kronometer M0 — baton spike%n");
        System.out.printf("  kernel       %s thread%n", kernelVirtual ? "virtual" : "platform");
        System.out.printf("  runtime      %s%n", nativeImage
                ? "GraalVM native-image"
                : System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.printf("  java         %s%n", System.getProperty("java.version"));
        System.out.printf("  os           %s %s%n",
                System.getProperty("os.name"), System.getProperty("os.arch"));
        System.out.printf("  cores        %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("  vt carriers  %s%n",
                System.getProperty("jdk.virtualThreadScheduler.parallelism", "(default)"));
    }

    private static boolean has(String[] args, String name) {
        for (String a : args) {
            if (a.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static <T> T arg(String[] args, String name, String fallback,
                             java.util.function.Function<String, T> parse) {
        for (String a : args) {
            if (a.startsWith(name + "=")) {
                return parse.apply(a.substring(name.length() + 1).toUpperCase(java.util.Locale.ROOT));
            }
        }
        return parse.apply(fallback);
    }

    private BatonBench() {
    }
}
