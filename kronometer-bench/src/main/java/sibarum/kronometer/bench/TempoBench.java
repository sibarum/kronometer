package sibarum.kronometer.bench;

import sibarum.kronometer.Dur;
import sibarum.kronometer.Glide;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Moment;
import sibarum.kronometer.Rate;
import sibarum.kronometer.Ratio;
import sibarum.kronometer.Tempo;

/**
 * M9: what does a gliding tempo cost?
 *
 * <p>Under a constant tempo a grid line is one exact ratio applied to one {@code long}. Under a glide it is a
 * composed map: an exact quadratic in {@code BigInteger} for a linear glide, {@code expm1}/{@code log1p} for an
 * exponential one, and in both directions a fix-up that checks the answer against the forward map. That is
 * certainly slower. The question is whether it is slow enough to matter at audio rates, where a fixed domain
 * places one grid line per sample.
 *
 * <p><b>The gate, set before measuring:</b> a conversion under a glide must cost under 10 % of a 48 kHz sample
 * period (2 083 ns), and a whole 48 kHz step in a gliding tempo must cost under twice the same step in a constant
 * one.
 */
public final class TempoBench {

    private static final Dur SAMPLE = Dur.hz(48_000);
    private static final long GATE_CONVERSION_NANOS = SAMPLE.nanos() / 10;
    private static final double GATE_STEP_FACTOR = 2.0;

    public static void main(String[] args) throws Exception {
        System.out.printf("Kronometer M9 - gliding tempo%n");
        System.out.printf("  runtime      %s%n", "runtime".equals(
                System.getProperty("org.graalvm.nativeimage.imagecode"))
                ? "GraalVM native-image" : System.getProperty("java.vm.name"));
        System.out.printf("  gate         conversion < %d ns; 48 kHz step < %.0fx constant%n",
                GATE_CONVERSION_NANOS, GATE_STEP_FACTOR);

        System.out.printf("%n1. One conversion, ns/op (min / p50 of ten 150 ms iterations)%n");
        System.out.printf("  %-28s %10s %10s %10s %10s%n", "", "globalAt", "p50", "elapsedAt", "p50");
        double worst = 0;
        for (Glide shape : new Glide[] {null, Glide.LINEAR, Glide.EXPONENTIAL}) {
            Tempo tempo = tempoIn(shape);
            Bench.Result inverse = Bench.measure("globalAt", ops -> {
                long acc = 0;
                for (long i = 0; i < ops; i++) {
                    acc += tempo.globalAt(Dur.ns((i * 20_833L) % 3_000_000_000_000L)).nanos();
                }
                sink = acc;
            });
            Bench.Result forward = Bench.measure("elapsedAt", ops -> {
                long acc = 0;
                for (long i = 0; i < ops; i++) {
                    acc += tempo.elapsedAt(Moment.ORIGIN.plus(Dur.ns((i * 20_833L) % 3_000_000_000_000L))).nanos();
                }
                sink = acc;
            });
            System.out.printf("  %-28s %10.0f %10.0f %10.0f %10.0f%n", label(shape),
                    inverse.min(), inverse.percentile(50), forward.min(), forward.percentile(50));
            if (shape != null) {
                worst = Math.max(worst, Math.max(inverse.percentile(50), forward.percentile(50)));
            }
        }

        System.out.printf("%n2. A 48 kHz fixed domain, one second of steps, ns per step taken"
                + " (best of %d interleaved rounds after %d)%n", STEP_ROUNDS, STEP_WARMUP_ROUNDS);
        double[] cost = stepCosts();
        double unity = cost[0];
        double constant = cost[1];
        System.out.printf("  %-28s %10.0f   (reference: 1:1 skips the ratio arithmetic entirely)%n",
                "constant 1:1", unity);
        System.out.printf("  %-28s %10.0f   (the gate's baseline: the affine path)%n", label(null), constant);
        double worstStep = 0;
        for (int i = 2; i < STEP_CASES.length; i++) {
            worstStep = Math.max(worstStep, cost[i]);
            System.out.printf("  %-28s %10.0f   %.2fx%n", label(STEP_CASES[i].shape()), cost[i], cost[i] / constant);
        }

        boolean conversionOk = worst < GATE_CONVERSION_NANOS;
        boolean stepOk = worstStep < GATE_STEP_FACTOR * constant;
        System.out.printf("%nGate: conversion p50 %s (%.0f ns against %d); step %s (%.2fx against %.0fx)%n",
                conversionOk ? "PASS" : "FAIL", worst, GATE_CONVERSION_NANOS,
                stepOk ? "PASS" : "FAIL", worstStep / constant, GATE_STEP_FACTOR);
    }

    private static long sink;

    private static String label(Glide shape) {
        return shape == null ? "constant 3:2" : shape == Glide.LINEAR ? "linear glide 1 -> 3:2" : "exponential glide 1 -> 3:2";
    }

    /** A tempo that is, for every moment the bench asks about, mid-glide (or constant, for the baseline). */
    private static Tempo tempoIn(Glide shape) {
        Kron kron = Kron.virtual();
        Tempo tempo = kron.tempo().child(shape == null ? Ratio.of(3, 2) : Ratio.ONE);
        if (shape != null) {
            kron.spork(() -> tempo.glide(Ratio.of(3, 2), Dur.s(3_600), shape));
        }
        kron.run();
        return tempo;
    }

    /** One step case: a tempo starting at {@code start}, gliding to 3:2 if {@code shape} is given. */
    private record StepCase(Ratio start, Glide shape) {
    }

    private static final StepCase[] STEP_CASES = {
            new StepCase(Ratio.ONE, null),
            new StepCase(Ratio.of(3, 2), null),
            new StepCase(Ratio.ONE, Glide.LINEAR),
            new StepCase(Ratio.ONE, Glide.EXPONENTIAL),
    };
    private static final int STEP_WARMUP_ROUNDS = 3;
    private static final int STEP_ROUNDS = 5;

    /**
     * The best per-step cost of every case in {@link #STEP_CASES}, measured round-robin: each round runs every
     * case once, starting one case later than the round before.
     *
     * <p>The first version measured each case to completion before starting the next, and the first case
     * always came out about twice as cheap as the rest. Not because it was cheaper: on GraalVM's JIT, with the
     * default virtual-thread scheduler, the first several wall seconds of baton handoffs run at about 500 ns
     * and then settle at about 1 200 ns, for any tempo. Whichever case ran first got the fast window to
     * itself. Run 1:1 three times in a row and it costs 537, 1 394, then 1 455 ns. Interleaving puts every
     * case on both sides of the change, so they are compared under the same conditions.
     */
    private static double[] stepCosts() {
        double[] best = new double[STEP_CASES.length];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        for (int round = 0; round < STEP_WARMUP_ROUNDS + STEP_ROUNDS; round++) {
            for (int k = 0; k < STEP_CASES.length; k++) {
                int i = (k + round) % STEP_CASES.length;
                double cost = runSteps(STEP_CASES[i].start(), STEP_CASES[i].shape());
                if (round >= STEP_WARMUP_ROUNDS) {
                    best[i] = Math.min(best[i], cost);
                }
            }
        }
        return best;
    }

    /**
     * One wall second of a 48 kHz domain in a tempo starting at {@code start}, gliding to 3:2 over an hour if
     * {@code shape} is given. Divided by the steps actually taken: a 3:2 tempo takes 72 000 of them in that
     * second, and the first version of this divided by 48 000 regardless, which made the baseline look cheaper
     * than it was by exactly the tempo's ratio.
     */
    private static double runSteps(Ratio start, Glide shape) {
        long[] taken = new long[1];
        long elapsed;
        try (Kron kron = Kron.virtual()) {
            Tempo tempo = kron.tempo().child(start);
            Rate audio = tempo.fixed("audio", SAMPLE);
            audio.each(step -> taken[0]++);
            if (shape != null) {
                kron.spork(() -> tempo.glide(Ratio.of(3, 2), Dur.s(3_600), shape));
            }
            long started = System.nanoTime();
            kron.runUntil(Moment.ORIGIN.plus(Dur.s(1)));
            elapsed = System.nanoTime() - started;
        }
        sink = taken[0];
        return elapsed / (double) taken[0];
    }

    private TempoBench() {
    }
}
