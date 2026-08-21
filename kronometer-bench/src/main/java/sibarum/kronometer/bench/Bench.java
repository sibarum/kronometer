package sibarum.kronometer.bench;

import java.util.Arrays;

/**
 * Minimal measurement scaffolding. Hand-rolled rather than JMH so the same classes run unchanged
 * under GraalVM native-image, which is the comparison M0 actually needs.
 *
 * <p>The tradeoff that buys is a duty of care, so: every workload is auto-calibrated to a useful
 * iteration length, warmed before it is believed, reported as a distribution rather than a mean, and
 * accompanied by a baseline meant to be subtracted. The measured quantity is throughput-derived
 * (one timestamp pair per iteration of many operations), so timer overhead is amortised to nothing —
 * except in {@link Result#ofLatencies}, which is explicitly per-operation and says so.
 */
final class Bench {

    private Bench() {
    }

    /** A unit of work that performs {@code ops} operations. */
    interface Workload {
        void run(long ops) throws Exception;
    }

    record Result(String label, long opsPerIteration, double[] nanosPerOp) {

        static Result ofLatencies(String label, long[] samples) {
            double[] copy = new double[samples.length];
            for (int i = 0; i < samples.length; i++) {
                copy[i] = samples[i];
            }
            Arrays.sort(copy);
            return new Result(label, 1, copy);
        }

        Result sorted() {
            double[] copy = nanosPerOp.clone();
            Arrays.sort(copy);
            return new Result(label, opsPerIteration, copy);
        }

        double min() {
            return sorted().nanosPerOp[0];
        }

        double percentile(double p) {
            double[] s = sorted().nanosPerOp;
            int idx = (int) Math.round((p / 100.0) * (s.length - 1));
            return s[Math.clamp(idx, 0, s.length - 1)];
        }

        double max() {
            double[] s = sorted().nanosPerOp;
            return s[s.length - 1];
        }

        Result minus(double baselineNanos) {
            double[] adjusted = new double[nanosPerOp.length];
            for (int i = 0; i < nanosPerOp.length; i++) {
                adjusted[i] = Math.max(0, nanosPerOp[i] - baselineNanos);
            }
            return new Result(label, opsPerIteration, adjusted);
        }
    }

    private static final long TARGET_ITERATION_NANOS = 150_000_000L;   // 150 ms
    private static final long MAX_OPS = 1L << 28;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURED_ITERATIONS = 10;

    static Result measure(String label, Workload workload) throws Exception {
        long ops = calibrate(workload);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            timeOnce(workload, ops);
        }
        double[] perOp = new double[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            perOp[i] = timeOnce(workload, ops) / (double) ops;
        }
        return new Result(label, ops, perOp);
    }

    /** Grow the op count until one iteration takes long enough for the timer to be irrelevant. */
    private static long calibrate(Workload workload) throws Exception {
        long ops = 256;
        long elapsed = timeOnce(workload, ops);
        while (elapsed < TARGET_ITERATION_NANOS && ops < MAX_OPS) {
            double factor = TARGET_ITERATION_NANOS / (double) Math.max(elapsed, 1);
            ops = Math.min(MAX_OPS, Math.max(ops * 2, (long) (ops * Math.min(factor, 16))));
            elapsed = timeOnce(workload, ops);
        }
        return ops;
    }

    private static long timeOnce(Workload workload, long ops) throws Exception {
        long t0 = System.nanoTime();
        workload.run(ops);
        return System.nanoTime() - t0;
    }

    /** The floor under every per-operation latency figure in this harness. */
    static double nanoTimeOverhead() {
        long ops = 2_000_000;
        for (int warm = 0; warm < 3; warm++) {
            spinNanoTime(ops);
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            best = Math.min(best, spinNanoTime(ops) / (double) ops);
        }
        return best;
    }

    private static long sink;

    private static long spinNanoTime(long ops) {
        long t0 = System.nanoTime();
        long acc = 0;
        for (long i = 0; i < ops; i++) {
            acc += System.nanoTime();
        }
        long elapsed = System.nanoTime() - t0;
        sink = acc;
        return elapsed;
    }

    static long sink() {
        return sink;
    }

    static void row(String label, Result r) {
        System.out.printf("  %-38s %10.0f %10.0f %10.0f %10.0f   %,12d%n",
                label, r.min(), r.percentile(50), r.percentile(90), r.max(), r.opsPerIteration());
    }

    static void header(String what) {
        System.out.printf("%n%s%n", what);
        System.out.printf("  %-38s %10s %10s %10s %10s   %12s%n",
                "", "min", "p50", "p90", "max", "ops/iter");
    }
}
