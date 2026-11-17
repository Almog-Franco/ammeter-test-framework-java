package ammeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/** Statistics, timing, acceptance checks and the comparison of runs. */
public final class Analysis {
    public static final int MIN_READINGS_MODEL_CHECK = 10;
    public static final int MIN_READINGS_DRIFT_CHECK = 30;

    private Analysis() {
    }

    public static Statistics summarize(List<Double> values) {
        int n = values.size();
        if (n == 0) {
            return Statistics.empty();
        }
        double[] v = toArray(values);
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        double mean = mean(v);
        double std = n > 1 ? stdev(v) : 0.0;
        double q1 = n > 1 ? quartile(sorted, 1) : v[0];
        double q3 = n > 1 ? quartile(sorted, 3) : v[0];
        double fence = 1.5 * (q3 - q1);
        int outliers = 0;
        for (double x : v) {
            if (x < q1 - fence || x > q3 + fence) {
                outliers++;
            }
        }
        double min = sorted[0];
        return new Statistics(
                n, mean, median(sorted), std, min, sorted[n - 1],
                mean != 0 ? std / mean : null,
                n > 1 && mean != 0 ? bootstrapCi(v, Analysis::cv) : null,
                min > 0 ? geometricMean(v) : null,
                bootstrapCi(v, Analysis::mean),
                q1, q3, outliers);
    }

    /**
     * 95% confidence interval of a statistic by resampling. Unlike mean ± 1.96 SE it doesn't assume normal data,
     * which matters for Greenlee's long-tailed readings. Fixed seed, so reports are repeatable.
     */
    public static List<Double> bootstrapCi(double[] values, ToDoubleFunction<double[]> statistic, int resamples) {
        if (values.length < 2) {
            return values.length == 0 ? List.of() : List.of(statistic.applyAsDouble(values),
                    statistic.applyAsDouble(values));
        }
        Random rng = new Random(0);
        double[] estimates = new double[resamples];
        double[] resample = new double[values.length];
        for (int r = 0; r < resamples; r++) {
            for (int i = 0; i < resample.length; i++) {
                resample[i] = values[rng.nextInt(values.length)];
            }
            estimates[r] = statistic.applyAsDouble(resample);
        }
        Arrays.sort(estimates);
        return List.of(estimates[(int) (0.025 * resamples)], estimates[(int) (0.975 * resamples) - 1]);
    }

    public static List<Double> bootstrapCi(double[] values, ToDoubleFunction<double[]> statistic) {
        return bootstrapCi(values, statistic, 1000);
    }

    /** How steady the run was: timing of the requests, and whether the readings drift over time. */
    public static Timing consistency(List<Sample> samples, SamplingPlan plan) {
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            Sample a = samples.get(i - 1);
            Sample b = samples.get(i);
            if (b.index() == a.index() + 1) {
                intervals.add(b.timeS() - a.timeS());
            }
        }
        double[] latencies = samples.stream().mapToDouble(Sample::latencyS).sorted().toArray();
        int n = latencies.length;
        double maxLag = samples.stream().mapToDouble(Sample::lagS).max().orElse(0.0);

        Double driftPct = null;
        Double driftT = null;
        boolean driftExact = false;
        List<Sample> valid = samples.stream().filter(s -> s.value() != null).toList();
        if (valid.size() >= 4 && valid.get(valid.size() - 1).timeS() > valid.get(0).timeS()) {
            double[] t = valid.stream().mapToDouble(Sample::timeS).toArray();
            double[] v = valid.stream().mapToDouble(Sample::value).toArray();
            double tMean = mean(t);
            double vMean = mean(v);
            double[] sxy = new double[t.length];
            double[] sxx = new double[t.length];
            for (int i = 0; i < t.length; i++) {
                sxy[i] = (t[i] - tMean) * (v[i] - vMean);
                sxx[i] = (t[i] - tMean) * (t[i] - tMean);
            }
            double slope = sum(sxy) / sum(sxx);
            double intercept = vMean - slope * tMean;
            if (vMean != 0) {
                driftPct = 100 * slope * (t[t.length - 1] - t[0]) / vMean;
            }
            // t-statistic of the slope: how sure we can be that the readings really trend
            double[] residuals = new double[t.length];
            for (int i = 0; i < t.length; i++) {
                double r = v[i] - intercept - slope * t[i];
                residuals[i] = r * r;
            }
            double slopeSe = Math.sqrt(sum(residuals) / (v.length - 2) / sum(sxx));
            if (slopeSe > 0) {
                driftT = slope / slopeSe;
            } else if (slope != 0) {
                driftExact = true; // a perfect straight line: no noise at all around the trend
            }
        }
        return new Timing(
                plan.count(), samples.size(), plan.frequencyHz(), samples.size() / plan.durationS(),
                intervals.size() > 1 ? stdev(toArray(intervals)) * 1000 : 0.0,
                maxLag * 1000,
                n > 0 ? mean(latencies) * 1000 : null,
                n > 0 ? latencies[(int) Math.ceil(0.95 * n) - 1] * 1000 : null,
                n > 0 ? latencies[n - 1] * 1000 : null,
                driftPct, driftT, driftExact);
    }

    public static int countOutOfRange(List<Double> values, Double low, Double high) {
        if (low == null || high == null) {
            return 0;
        }
        return (int) values.stream().filter(v -> !(low <= v && v <= high)).count();
    }

    /** The longest run of identical consecutive readings. */
    public static int longestRepeat(List<Double> values) {
        int longest = values.isEmpty() ? 0 : 1;
        int current = longest;
        for (int i = 1; i < values.size(); i++) {
            current = values.get(i).doubleValue() == values.get(i - 1).doubleValue() ? current + 1 : 1;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    /**
     * How many standard errors the mean of log(readings) is from what the emulator's physics predicts.
     *
     * <p>On a log scale the readings are close to normal, even Greenlee's long-tailed ones, so the limit behaves the
     * same for every ammeter (see docs/DESIGN.md for the false-alarm rates). Null when there is no model or fewer
     * than {@link #MIN_READINGS_MODEL_CHECK} positive readings.
     */
    public static Double modelZ(List<Double> values, AmmeterSpec spec) {
        if (spec.expectedGeoMean() == null || spec.expectedLogStd() == null) {
            return null;
        }
        double[] logs = logsOfPositive(values);
        if (logs.length < MIN_READINGS_MODEL_CHECK) {
            return null;
        }
        double standardError = spec.expectedLogStd() / Math.sqrt(logs.length);
        return (mean(logs) - Math.log(spec.expectedGeoMean())) / standardError;
    }

    public static List<Check> evaluate(List<Double> values, double errorRate, Timing timing, AmmeterSpec spec,
                                       Acceptance acceptance) {
        List<Check> checks = new ArrayList<>();
        if (spec.expectedMin() != null && spec.expectedMax() != null && !values.isEmpty()) {
            int bad = countOutOfRange(values, spec.expectedMin(), spec.expectedMax());
            checks.add(Check.of("values_in_range", bad == 0, bad + " of " + values.size() + " outside ["
                    + Fmt.g(spec.expectedMin()) + ", " + Fmt.g(spec.expectedMax()) + "] A"));
        }

        checks.add(Check.of("error_rate", errorRate <= acceptance.maxErrorRate(),
                Fmt.percent(errorRate, 1) + " failed (limit " + Fmt.percent(acceptance.maxErrorRate(), 0) + ")"));

        double off = 1 - timing.achievedHz() / timing.requestedHz();
        checks.add(Check.of("sampling_rate", off <= acceptance.maxFrequencyError() + 1e-9,
                timing.taken() + " of " + timing.planned() + " planned readings taken, "
                        + Fmt.g(timing.achievedHz(), 3) + " Hz for " + Fmt.g(timing.requestedHz())
                        + " Hz requested (" + Fmt.percent(off, 1) + " short, limit "
                        + Fmt.percent(acceptance.maxFrequencyError(), 0) + ")"));

        if (!values.isEmpty()) {
            int longest = longestRepeat(values);
            checks.add(Check.of("not_stuck", longest < acceptance.stuckAfter(), "longest run of identical readings: "
                    + longest + " (stuck from " + acceptance.stuckAfter() + ")"));
        }

        if (spec.expectedGeoMean() != null) {
            Double z = modelZ(values, spec);
            if (z == null) {
                checks.add(Check.skip("matches_model",
                        "needs at least " + MIN_READINGS_MODEL_CHECK + " positive readings"));
            } else {
                double geoMean = Math.exp(mean(logsOfPositive(values)));
                checks.add(Check.of("matches_model", Math.abs(z) <= acceptance.maxModelZ(),
                        "geometric mean " + Fmt.g(geoMean, 4) + " A, model " + Fmt.g(spec.expectedGeoMean())
                                + " A (z = " + Fmt.f(z, 2, true) + ", limit ±" + Fmt.g(acceptance.maxModelZ()) + ")"));
            }
        }

        if (values.size() < MIN_READINGS_DRIFT_CHECK) {
            checks.add(Check.skip("no_drift", "needs at least " + MIN_READINGS_DRIFT_CHECK + " readings"));
        } else if (timing.driftExact()) {
            checks.add(Check.of("no_drift", false, "readings lie exactly on a sloped straight line"));
        } else if (timing.driftT() != null) {
            double t = timing.driftT();
            String pct = timing.driftPct() == null ? "-" : Fmt.g(timing.driftPct(), 3, true);
            checks.add(Check.of("no_drift", Math.abs(t) < acceptance.maxDriftT(), pct + " % over the run (t = "
                    + Fmt.f(t, 2, true) + ", limit ±" + Fmt.g(acceptance.maxDriftT()) + ")"));
        }
        return checks;
    }

    public static Status verdict(List<Check> checks, int validCount, String stopReason) {
        if ("interrupted".equals(stopReason)) {
            return Status.INTERRUPTED;
        }
        if (validCount == 0) {
            return Status.ERROR;
        }
        return checks.stream().allMatch(Check::passed) && stopReason == null ? Status.PASS : Status.FAIL;
    }

    /**
     * Finds the most precise ammeter (lowest CV) among runs that passed every check with enough readings.
     *
     * <p>Range, errors, agreement with the model and drift are already pass/fail checks, so precision is what's left
     * to compare. CV is relative, so ammeters whose currents differ by about 1000x can be compared. A winner is named
     * only if its 95% interval of the CV doesn't overlap the others'; otherwise the evidence isn't enough.
     */
    public static Comparison compare(List<Run> runs, int minReadings) {
        List<Comparison.Row> rows = new ArrayList<>();
        List<Comparison.Row> notRanked = new ArrayList<>();
        for (Run run : runs) {
            Statistics stats = run.statistics();
            Double geoMean = stats.geoMean();
            Double expected = run.ammeter().expectedGeoMean();
            Comparison.Row row = new Comparison.Row(run.ammeter().name(), run.runId(), run.status(), stats.count(),
                    stats.mean(), stats.median(), stats.cv(), stats.cvCi95(),
                    geoMean != null && expected != null && expected != 0 ? geoMean / expected - 1 : null,
                    run.errorRate(), null);
            if (run.status() != Status.PASS) {
                notRanked.add(row.withReason("status " + run.status()));
            } else if (stats.count() < minReadings) {
                notRanked.add(row.withReason("only " + stats.count() + " readings, " + minReadings + " needed"));
            } else if (row.cv() == null || row.cvCi95() == null || row.cvCi95().isEmpty()) {
                notRanked.add(row.withReason("precision can't be computed"));
            } else {
                rows.add(row);
            }
        }

        rows.sort(Comparator.comparingDouble(Comparison.Row::cv).thenComparingDouble(Comparison.Row::errorRate));
        if (rows.size() < 2) {
            return new Comparison(rows, notRanked, minReadings, null, List.of(),
                    "insufficient evidence: fewer than two runs passed every check with at least " + minReadings
                            + " readings", null);
        }
        Comparison.Row best = rows.get(0);
        List<String> tied = rows.stream().filter(r -> r.cvCi95().get(0) <= best.cvCi95().get(1))
                .map(Comparison.Row::ammeter).toList();
        if (tied.size() == 1) {
            return new Comparison(rows, notRanked, minReadings, best.ammeter(), List.of(), null, null);
        }
        return new Comparison(rows, notRanked, minReadings, null, tied, "insufficient evidence: "
                + String.join(", ", tied) + " are equally precise within the uncertainty of their readings; "
                + "more readings are needed to separate them", null);
    }

    public static Comparison compare(List<Run> runs) {
        return compare(runs, 30);
    }

    /** Arithmetic mean with a compensated sum, like Python's {@code statistics.fmean}. */
    public static double mean(double[] values) {
        return sum(values) / values.length;
    }

    /** Sample standard deviation (n - 1). */
    public static double stdev(double[] values) {
        double mean = mean(values);
        double[] squares = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            squares[i] = (values[i] - mean) * (values[i] - mean);
        }
        return Math.sqrt(sum(squares) / (values.length - 1));
    }

    public static double geometricMean(double[] values) {
        return Math.exp(mean(Arrays.stream(values).map(Math::log).toArray()));
    }

    /** Coefficient of variation (sample std / mean); infinite when the mean is 0. */
    public static double cv(double[] values) {
        double mean = mean(values);
        return mean != 0 ? stdev(values) / mean : Double.POSITIVE_INFINITY;
    }

    /** Neumaier's compensated sum: as exact as Python's {@code math.fsum} for the data seen here. */
    public static double sum(double[] values) {
        double sum = 0.0;
        double compensation = 0.0;
        for (double x : values) {
            double t = sum + x;
            compensation += Math.abs(sum) >= Math.abs(x) ? (sum - t) + x : (x - t) + sum;
            sum = t;
        }
        return sum + compensation;
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /** Python's {@code statistics.quantiles(values, n=4, method="inclusive")}. */
    private static double quartile(double[] sorted, int i) {
        int m = sorted.length - 1;
        int j = i * m / 4;
        int delta = i * m - j * 4;
        return (sorted[j] * (4 - delta) + sorted[j + 1] * delta) / 4;
    }

    private static double[] logsOfPositive(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).filter(v -> v > 0).map(Math::log).toArray();
    }

    private static double[] toArray(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
