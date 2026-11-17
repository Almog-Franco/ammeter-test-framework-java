package ammeter.tools;

import ammeter.Fmt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Derives the expected values in config.yaml and measures how the model and drift checks behave.
 *
 * <p>It simulates the three emulators straight from the physics and input ranges in the README, not from their code,
 * so it is an independent reference.
 *
 * <pre>java -cp target/ammeter.jar ammeter.tools.CalibrateChecks [--runs 200000]</pre>
 */
public final class CalibrateChecks {
    private static final SplittableRandom RNG = new SplittableRandom(12345);

    interface Model {
        double reading();
    }

    private static final Map<String, Model> MODELS = new LinkedHashMap<>();

    static {
        MODELS.put("greenlee", () -> uniform(1, 10) / uniform(0.1, 100));
        MODELS.put("entes", () -> uniform(0.01, 0.1) * uniform(500, 2000));
        MODELS.put("circutor", () -> {
            double dt = uniform(0.001, 0.01);
            double sum = 0;
            for (int i = 0; i < 10; i++) {
                sum += uniform(0.1, 1.0);
            }
            return dt * sum;
        });
    }

    private CalibrateChecks() {
    }

    private static double uniform(double low, double high) {
        return low + (high - low) * RNG.nextDouble();
    }

    private static double[] run(Model model, int n) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = model.reading();
        }
        return x;
    }

    static double modelZ(double[] x, double logMean, double logStd) {
        double sum = 0;
        for (double v : x) {
            sum += Math.log(v);
        }
        return (sum / x.length - logMean) / (logStd / Math.sqrt(x.length));
    }

    /** The t value of a straight-line fit of the readings against their index. */
    static double driftT(double[] x) {
        int n = x.length;
        double mean = 0;
        for (double v : x) {
            mean += v;
        }
        mean /= n;
        double tt = 0;
        double xt = 0;
        for (int i = 0; i < n; i++) {
            double t = i - (n - 1) / 2.0;
            tt += t * t;
            xt += (x[i] - mean) * t;
        }
        double slope = xt / tt;
        double residuals = 0;
        for (int i = 0; i < n; i++) {
            double r = x[i] - mean - slope * (i - (n - 1) / 2.0);
            residuals += r * r;
        }
        return slope / Math.sqrt(residuals / (n - 2) / tt);
    }

    public static void main(String[] args) {
        int runs = 200_000;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--runs") && i + 1 < args.length) {
                runs = Integer.parseInt(args[++i]);
            } else if (args[i].startsWith("--runs=")) {
                runs = Integer.parseInt(args[i].substring("--runs=".length()));
            } else {
                System.err.println("usage: CalibrateChecks [--runs RUNS]");
                System.exit(2);
            }
        }

        System.out.println("Expected values (4,000,000 simulated readings each)");
        Map<String, double[]> reference = new LinkedHashMap<>();
        for (var entry : MODELS.entrySet()) {
            double[] x = run(entry.getValue(), 4_000_000);
            double[] logs = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                logs[i] = Math.log(x[i]);
            }
            double logMean = mean(logs);
            double logStd = std(logs);
            reference.put(entry.getKey(), new double[]{logMean, logStd});
            System.out.printf("  %-9s mean %s  cv %.4f  geo_mean %s  log_std %.4f%n", entry.getKey(), Fmt.g(mean(x), 5),
                    std(x) / mean(x), Fmt.g(Math.exp(logMean), 5), logStd);
        }

        System.out.printf("%nFalse alarms on clean data (%d runs each): share of runs over the limit%n", runs);
        for (int n : new int[]{10, 30, 50, 100}) {
            for (var entry : MODELS.entrySet()) {
                double[] ref = reference.get(entry.getKey());
                int modelAlarms = 0;
                int driftAlarms = 0;
                for (int r = 0; r < runs; r++) {
                    double[] x = run(entry.getValue(), n);
                    modelAlarms += Math.abs(modelZ(x, ref[0], ref[1])) > 5 ? 1 : 0;
                    driftAlarms += Math.abs(driftT(x)) > 5 ? 1 : 0;
                }
                System.out.printf("  n=%3d %-9s model |z|>5: %.1e   drift |t|>5: %.1e%n", n, entry.getKey(),
                        (double) modelAlarms / runs, (double) driftAlarms / runs);
            }
        }

        int detectionRuns = runs / 10;
        System.out.printf("%nDetection with 50 readings (%d runs each): share of runs caught%n", detectionRuns);
        String[] labels = {"x0.5 calibration", "x0.8 calibration", "+100% linear drift"};
        for (var entry : MODELS.entrySet()) {
            double[] ref = reference.get(entry.getKey());
            int[] model = new int[3];
            int[] drift = new int[3];
            for (int r = 0; r < detectionRuns; r++) {
                double[] x = run(entry.getValue(), 50);
                for (int k = 0; k < 3; k++) {
                    double[] faulty = new double[50];
                    for (int i = 0; i < 50; i++) {
                        faulty[i] = switch (k) {
                            case 0 -> x[i] * 0.5;
                            case 1 -> x[i] * 0.8;
                            default -> x[i] * (1 + i / 49.0);
                        };
                    }
                    model[k] += Math.abs(modelZ(faulty, ref[0], ref[1])) > 5 ? 1 : 0;
                    drift[k] += Math.abs(driftT(faulty)) > 5 ? 1 : 0;
                }
            }
            for (int k = 0; k < 3; k++) {
                System.out.printf("  %-9s %-20s model check %.3f   drift check %.3f%n", entry.getKey(), labels[k],
                        (double) model[k] / detectionRuns, (double) drift[k] / detectionRuns);
            }
        }
    }

    private static double mean(double[] x) {
        double sum = 0;
        for (double v : x) {
            sum += v;
        }
        return sum / x.length;
    }

    private static double std(double[] x) {
        double m = mean(x);
        double ss = 0;
        for (double v : x) {
            ss += (v - m) * (v - m);
        }
        return Math.sqrt(ss / (x.length - 1));
    }
}
