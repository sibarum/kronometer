package sibarum.kronometer.bench;

import sibarum.kronometer.Clock;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Metro;
import sibarum.kronometer.Overrun;
import sibarum.kronometer.Realtime;
import sibarum.kronometer.Repayment;
import sibarum.kronometer.Settlement;
import sibarum.kronometer.Wall;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * M2: what does a real realtime run actually look like, and what was M0's 117 µs outlier?
 *
 * <p>M0 measured a tight baton — p99 of 680 ns — with a maximum round trip of 117 µs over 200 000
 * samples, and deferred the question to here on the grounds that the slip model is exactly the machine
 * for absorbing such things. This measures a paced 60 Hz and 1 kHz run and reports two different
 * things that are easy to conflate:
 *
 * <ul>
 *   <li><b>Jitter</b> — how far each frame's wall arrival strays from one period after the last. This
 *       is where an outlier shows up.</li>
 *   <li><b>Slip</b> — how far the schedule as a whole has fallen behind. A jitter spike that the
 *       kernel recovers from never becomes slip; one it cannot recover from does.</li>
 * </ul>
 *
 * The distinction is the point: a stall you absorb and a stall you carry are not the same event, and
 * only the second one is a bug in the pacing.
 */
public final class SlipBench {

    private record Run(String label, double hz, Settlement settlement, int frames) { }

    private record Result(long[] jitterNanos, Dur finalSlip, Map<Overrun.Kind, Integer> events) { }

    public static void main(String[] args) {
        for (String a : args) {
            if (a.startsWith("--spin=")) {
                spinTail = Dur.us(Long.parseLong(a.substring(7)));
            }
        }
        System.out.printf("Kronometer M2 — realtime pacing%n");
        System.out.printf("  runtime      %s%n", "runtime".equals(
                System.getProperty("org.graalvm.nativeimage.imagecode"))
                ? "GraalVM native-image"
                : System.getProperty("java.vm.name"));
        System.out.printf("  cores        %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("  vt carriers  %s%n",
                System.getProperty("jdk.virtualThreadScheduler.parallelism", "(default)"));

        List<Run> runs = List.of(
                new Run("60 Hz  / SLIP", 60, Settlement.SLIP, 300),
                new Run("60 Hz  / CATCH_UP", 60, Settlement.CATCH_UP, 300),
                new Run("1 kHz  / SLIP", 1_000, Settlement.SLIP, 5_000),
                new Run("1 kHz  / CATCH_UP", 1_000, Settlement.CATCH_UP, 5_000));

        System.out.printf("%nPer-frame jitter — |arrival interval − period|, ns%n");
        System.out.printf("  %-22s %9s %9s %9s %11s %11s   %s%n",
                "", "p50", "p90", "p99", "max", "slip", "events");

        for (Run run : runs) {
            measure(run);                                   // warm the paths, discard
            Result result = measure(run);
            Bench.Result jitter = Bench.Result.ofLatencies("", result.jitterNanos());
            System.out.printf("  %-22s %9.0f %9.0f %9.0f %11.0f %11s   %s%n",
                    run.label(), jitter.percentile(50), jitter.percentile(90), jitter.percentile(99),
                    jitter.max(), result.finalSlip(), render(result.events()));
        }

        System.out.printf("%nReading it: jitter is transient, slip is carried. A max far above p99 with%n"
                + "negligible slip means the kernel absorbed the spike — which is the design working,%n"
                + "not the design failing.%n");
    }

    /** Overridable so the spin tail's effect on jitter can be measured rather than assumed. */
    private static Dur spinTail = null;

    private static Result measure(Run run) {
        Dur period = Dur.hz(run.hz());
        Map<Overrun.Kind, Integer> events = new EnumMap<>(Overrun.Kind.class);
        Realtime clock = Clock
                .realtime(spinTail == null ? Wall.system() : Wall.system(spinTail))
                .settlement(run.settlement())
                .repayment(Repayment.rate(0.005));
        clock.onOverrun(o -> events.merge(o.kind(), 1, Integer::sum));

        List<Long> arrivals = new ArrayList<>(run.frames());
        try (Kron kron = Kron.of(clock)) {
            kron.spork("frames", () -> {
                Metro metro = Metro.of(period);
                for (int i = 0; i < run.frames(); i++) {
                    metro.tick();
                    arrivals.add(System.nanoTime());
                }
            });
            kron.run();
        }

        long[] jitter = new long[arrivals.size() - 1];
        for (int i = 1; i < arrivals.size(); i++) {
            jitter[i - 1] = Math.abs((arrivals.get(i) - arrivals.get(i - 1)) - period.nanos());
        }
        return new Result(jitter, clock.slip(), events);
    }

    private static String render(Map<Overrun.Kind, Integer> events) {
        if (events.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        events.forEach((kind, count) -> sb.append(sb.isEmpty() ? "" : ", ").append(kind)
                .append('×').append(count));
        return sb.toString();
    }

    private SlipBench() {
    }
}
