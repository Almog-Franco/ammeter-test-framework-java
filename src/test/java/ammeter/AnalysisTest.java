package ammeter;

import static ammeter.TestSupport.ENTES;
import static ammeter.TestSupport.entesReadings;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class AnalysisTest {
    private static final Acceptance LIMITS = Acceptance.DEFAULT;
    private static final AmmeterSpec CIRCUTOR = new AmmeterSpec("circutor", 5003, "X", "127.0.0.1", null, 0.001, 0.1,
            null, null, null);

    private static List<Double> doubles(double... values) {
        return new ArrayList<>(Arrays.stream(values).boxed().toList());
    }

    @SafeVarargs
    private static List<Double> cat(List<Double>... parts) {
        List<Double> all = new ArrayList<>();
        for (List<Double> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static List<Double> repeat(List<Double> pattern, int times) {
        List<Double> all = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            all.addAll(pattern);
        }
        return all;
    }

    private static List<Double> repeat(double value, int times) {
        return repeat(doubles(value), times);
    }

    private static List<Double> scaled(List<Double> values, double factor) {
        return values.stream().map(v -> v * factor).toList();
    }

    private static double fmean(List<Double> values) {
        return Analysis.mean(values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private static double geometricMean(List<Double> values) {
        return Math.exp(fmean(values.stream().map(Math::log).toList()));
    }

    private static double stdev(double... values) {
        return Analysis.stdev(values);
    }

    /** Equal within a relative tolerance of 1e-6. */
    private static void approx(Double actual, double expected) {
        assertNotNull(actual);
        assertEquals(actual, expected, Math.max(Math.abs(expected) * 1e-6, 1e-12));
    }

    private static void approx(Double actual, double expected, double tolerance) {
        assertNotNull(actual);
        assertEquals(actual, expected, tolerance);
    }

    private static List<Sample> samplesAt(List<Double> values) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            samples.add(new Sample(i, i * 0.1, 0.0, 0.001, values.get(i)));
        }
        return samples;
    }

    private static Map<String, Check> checksFor(List<Double> values, AmmeterSpec spec, double errorRate,
                                                Acceptance limits, Integer planned) {
        Timing timing = Analysis.consistency(samplesAt(values),
                new SamplingPlan(planned != null ? planned : values.size(), 10));
        Map<String, Check> checks = new LinkedHashMap<>();
        for (Check c : Analysis.evaluate(values, errorRate, timing, spec, limits)) {
            checks.put(c.name(), c);
        }
        return checks;
    }

    private static Map<String, Check> checksFor(List<Double> values) {
        return checksFor(values, ENTES, 0.0, LIMITS, null);
    }

    private static Map<String, Check> checksFor(List<Double> values, AmmeterSpec spec) {
        return checksFor(values, spec, 0.0, LIMITS, null);
    }

    private static Map<String, Check> checksWithErrorRate(List<Double> values, double errorRate) {
        return checksFor(values, ENTES, errorRate, LIMITS, null);
    }

    private static Map<String, Check> checksWithLimits(List<Double> values, Acceptance limits) {
        return checksFor(values, ENTES, 0.0, limits, null);
    }

    private static Map<String, Check> checksWithPlan(List<Double> values, int planned) {
        return checksFor(values, ENTES, 0.0, LIMITS, planned);
    }

    @Test
    public void summaryStatistics() {
        Statistics stats = Analysis.summarize(doubles(1, 2, 3, 4, 100));
        assertEquals(stats.count(), 5);
        assertEquals(stats.mean(), 22.0, 0.0);
        assertEquals(stats.median(), 3.0, 0.0);
        approx(stats.stdDev(), 43.6176, 1e-5 * 43.6176); // sqrt(7610 / 4)
        assertEquals(stats.min(), 1.0, 0.0);
        assertEquals(stats.max(), 100.0, 0.0);
        approx(stats.cv(), 43.6176 / 22, 1e-5 * 43.6176 / 22);
        approx(stats.geoMean(), Math.pow(2400, 0.2));
        assertEquals(stats.outliers(), Integer.valueOf(1));
        assertEquals(Analysis.summarize(doubles(1, 2, 3, 4)).median(), 2.5, 0.0);
        // 16 is past q3 + 1.5 IQR (13)
        assertEquals(Analysis.summarize(doubles(1, 2, 3, 4, 5, 6, 7, 8, 16)).outliers(), Integer.valueOf(1));
        Statistics low = Analysis.summarize(doubles(1, 9, 10, 11, 12, 13, 14, 15, 16));
        // 1 is below q1 - 1.5 IQR (4)
        assertEquals(low.q1(), 10.0, 0.0);
        assertEquals(low.q3(), 14.0, 0.0);
        assertEquals(low.outliers(), Integer.valueOf(1));

        List<Double> values = entesReadings(30);
        Random rng = new Random(0);
        double[] means = new double[1000];
        for (int r = 0; r < means.length; r++) {
            List<Double> resample = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                resample.add(values.get(rng.nextInt(values.size())));
            }
            means[r] = fmean(resample);
        }
        Arrays.sort(means);
        // 2.5th and 97.5th of 1000 seeded resamples
        assertEquals(Analysis.summarize(values).ci95(), List.of(means[25], means[974]));
        assertEquals(Analysis.summarize(values).ci95(), Analysis.summarize(values).ci95());

        stats = Analysis.summarize(cat(repeat(0.02, 30), doubles(50.0))); // long tail, like Greenlee
        double ciLow = stats.ci95().get(0);
        double ciHigh = stats.ci95().get(1);
        assertTrue(0.02 <= ciLow && ciLow < ciHigh && ciHigh <= 50.0);
        double cvLow = stats.cvCi95().get(0);
        double cvHigh = stats.cvCi95().get(1);
        assertTrue(0 <= cvLow && cvLow <= stats.cv() && stats.cv() <= cvHigh);
    }

    @DataProvider
    public Object[][] summaryEdgeCases() {
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("count", 0);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("count", 1);
        one.put("mean", 5.0);
        one.put("std_dev", 0.0);
        one.put("ci95", List.of(5.0, 5.0));
        one.put("cv_ci95", null);
        Map<String, Object> zeros = new LinkedHashMap<>();
        zeros.put("count", 2);
        zeros.put("cv", null);
        zeros.put("cv_ci95", null);
        zeros.put("geo_mean", null);
        return new Object[][] {
            {doubles(), none},
            {doubles(5.0), one},
            {doubles(0.0, 0.0), zeros},
        };
    }

    @Test(dataProvider = "summaryEdgeCases")
    public void summaryEdgeCases(List<Double> values, Map<String, Object> expected) {
        Statistics stats = Analysis.summarize(values);
        expected.forEach((key, value) -> {
            Object actual = switch (key) {
                case "count" -> stats.count();
                case "mean" -> stats.mean();
                case "std_dev" -> stats.stdDev();
                case "ci95" -> stats.ci95();
                case "cv" -> stats.cv();
                case "cv_ci95" -> stats.cvCi95();
                case "geo_mean" -> stats.geoMean();
                default -> throw new IllegalArgumentException(key);
            };
            assertEquals(actual, value, key);
        });
    }

    @Test
    public void timingStatistics() {
        Timing steady = Analysis.consistency(samplesAt(repeat(doubles(1.0, 2.0), 20)), new SamplingPlan(40, 10));
        approx(steady.achievedHz(), 10);
        assertEquals(steady.taken(), 40);
        assertEquals(steady.planned(), 40);
        approx(steady.intervalJitterMs(), 0, 1e-6);
        assertTrue(Math.abs(steady.driftT()) < 2);

        List<Sample> gap = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            if (i != 3 && i != 4) {
                gap.add(new Sample(i, i * 0.1, 0.0, 0.001, 1.0 + i % 2));
            }
        }
        Timing timing = Analysis.consistency(gap, new SamplingPlan(10, 10));
        approx(timing.achievedHz(), 8);
        approx(timing.intervalJitterMs(), 0, 1e-6); // the gap is not counted as jitter

        List<Sample> samples = List.of(
                new Sample(0, 0.00, 0.00, 0.001, 1.0),
                new Sample(1, 0.10, 0.00, 0.002, 2.0),
                new Sample(2, 0.21, 0.01, 0.003, null, "AmmeterTimeoutException: slow"),
                new Sample(3, 0.30, 0.00, 0.004, 4.0),
                new Sample(5, 0.52, 0.02, 0.010, 5.0), // slot 4 was missed
                new Sample(6, 0.60, 0.00, 0.005, 6.0));
        timing = Analysis.consistency(samples, new SamplingPlan(10, 10));
        assertEquals(timing.taken(), 6);
        assertEquals(timing.planned(), 10);
        approx(timing.achievedHz(), 6.0); // failed readings were taken, too
        approx(timing.intervalJitterMs(), stdev(0.10, 0.11, 0.09, 0.08) * 1000);
        approx(timing.maxLagMs(), 20);
        approx(timing.latencyMeanMs(), 25.0 / 6);
        approx(timing.latencyP95Ms(), 10);
        approx(timing.latencyMaxMs(), 10);

        List<Sample> three = List.of(
                new Sample(0, 0.0, 0.0, 0.001, 1.0),
                new Sample(1, 0.1, 0.0, 0.001, 2.0),
                new Sample(2, 0.22, 0.03, 0.001, null, "AmmeterTimeoutException: slow"));
        timing = Analysis.consistency(three, new SamplingPlan(3, 10));
        approx(timing.intervalJitterMs(), stdev(0.1, 0.12) * 1000);
        approx(timing.maxLagMs(), 30); // a failed reading's delay counts, too
        List<Sample> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(new Sample(i, i * 0.1, 0.0, (i + 1) / 1000.0, 1.0));
        }
        approx(Analysis.consistency(twenty, new SamplingPlan(20, 10)).latencyP95Ms(), 19);
    }

    @Test
    public void driftIsMeasuredAndAnyTrendFailsTheCheck() {
        List<Double> values = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            values.add(10 + 0.1 * i + (i % 2 == 1 ? 0.05 : -0.05));
            times.add(i * 0.1);
        }
        Timing timing = Analysis.consistency(samplesAt(values), new SamplingPlan(40, 10));
        double tMean = fmean(times);
        double vMean = fmean(values);
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < 40; i++) {
            sxy += (times.get(i) - tMean) * (values.get(i) - vMean);
            sxx += (times.get(i) - tMean) * (times.get(i) - tMean);
        }
        double slope = sxy / sxx;
        double intercept = vMean - slope * tMean;
        double squares = 0;
        for (int i = 0; i < 40; i++) {
            double r = values.get(i) - intercept - slope * times.get(i);
            squares += r * r;
        }
        double slopeSe = Math.sqrt(squares / 38 / sxx);
        approx(timing.driftPct(), 100 * slope * 3.9 / vMean);
        approx(timing.driftT(), slope / slopeSe);
        // a reversed clamp
        Timing mirrored = Analysis.consistency(samplesAt(scaled(values, -1)), new SamplingPlan(40, 10));
        approx(mirrored.driftPct(), timing.driftPct()); // same relative trend

        Timing shortRun = Analysis.consistency(samplesAt(doubles(10.0, 12.0, 11.0, 13.0, 12.0)),
                new SamplingPlan(5, 10));
        assertNotNull(shortRun.driftPct()); // from 4 readings on
        assertNotNull(shortRun.driftT());

        List<Double> rising = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rising.add(10 + i * 0.5 + (i % 2 == 1 ? 0.2 : -0.2));
        }
        timing = Analysis.consistency(samplesAt(rising), new SamplingPlan(40, 10));
        assertTrue(timing.driftPct() > 50);
        assertTrue(timing.driftT() > 5);

        List<Double> exact = new ArrayList<>(); // exact in floating point: no noise at all
        List<Double> tiny = new ArrayList<>(); // tiny rounding noise
        List<Double> falling = new ArrayList<>();
        List<Double> fallingNoisy = new ArrayList<>();
        List<Double> risingNoisy = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int noise = i % 2 == 1 ? 1 : -1;
            exact.add(2.0 * i + 1);
            tiny.add(10 + 0.1 * i);
            falling.add(100.0 - 2 * i);
            fallingNoisy.add(100.0 - 2 * i + noise);
            risingNoisy.add(20.0 + 2 * i + noise);
        }
        for (List<Double> trend : List.of(exact, tiny, falling, fallingNoisy, risingNoisy)) {
            assertFalse(checksFor(trend).get("no_drift").passed(), trend.toString());
        }
    }

    @Test
    public void goodRunPassesEveryCheck() {
        Map<String, Check> checks = checksFor(entesReadings(50));
        assertEquals(List.copyOf(checks.keySet()),
                List.of("values_in_range", "error_rate", "sampling_rate", "not_stuck", "matches_model", "no_drift"));
        for (Check c : checks.values()) {
            assertTrue(c.passed() && !c.skipped(), c.toString());
        }
    }

    @Test
    public void stuckReadingsFail() {
        Map<String, Check> checks = checksFor(repeat(5.0, 50));
        assertTrue(checks.get("values_in_range").passed());
        assertFalse(checks.get("not_stuck").passed());
        assertFalse(checks.get("matches_model").passed());
        assertEquals(Analysis.verdict(List.copyOf(checks.values()), 50, null), Status.FAIL);

        checks = checksFor(repeat(55.51, 50)); // stuck at exactly the expected geometric mean
        assertTrue(checks.get("matches_model").passed());
        assertFalse(checks.get("not_stuck").passed());

        List<Double> values = entesReadings(40);
        values.set(10, values.get(11));
        assertTrue(checksFor(values).get("not_stuck").passed()); // two identical readings in a row are fine...
        values.set(12, values.get(11));
        assertFalse(checksFor(values).get("not_stuck").passed()); // ...three are not

        values = entesReadings(40);
        // frozen at the very end
        assertFalse(checksFor(cat(values.subList(0, 37), repeat(values.get(39), 3))).get("not_stuck").passed());
        List<Double> pairs = new ArrayList<>();
        for (double v : entesReadings(20)) {
            pairs.add(v);
            pairs.add(v);
        }
        assertTrue(checksFor(pairs).get("not_stuck").passed()); // pairs only
        List<Double> circutor = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            circutor.add(0.0291 + i * 0.0001);
        }
        assertTrue(checksFor(circutor, CIRCUTOR).get("not_stuck").passed());
        assertTrue(checksFor(cat(values.subList(0, 30), repeat(values.get(0), 5))).get("not_stuck").detail()
                .contains("identical readings: 5"));
    }

    @Test
    public void calibrationErrorsFailTheModelCheck() {
        List<Double> values = entesReadings(50);
        Check low = checksFor(scaled(values, 0.5)).get("matches_model");
        assertFalse(low.passed());
        assertTrue(low.detail().contains("z = -"), low.detail());
        assertFalse(checksFor(scaled(values, 1.8)).get("matches_model").passed());
        String detail = checksFor(values).get("matches_model").detail();
        assertTrue(detail.contains("geometric mean " + Fmt.g(geometricMean(values), 4) + " A"), detail);

        List<Double> logs = values.subList(0, 40).stream().map(Math::log).toList();
        double z = (fmean(logs) - Math.log(ENTES.expectedGeoMean())) / (ENTES.expectedLogStd() / Math.sqrt(40));
        String withZeros = checksFor(cat(values.subList(0, 40), repeat(0.0, 10))).get("matches_model").detail();
        // zero readings have no log, so they don't count
        assertTrue(withZeros.contains(String.format(Locale.ROOT, "z = %+.2f,", z)), withZeros);
    }

    @Test
    public void limitsAreExact() {
        List<Double> values = entesReadings(30);
        for (double bad : new double[] {4.99, 200.01}) {
            assertFalse(checksFor(cat(doubles(bad), values.subList(1, 30))).get("values_in_range").passed());
        }
        assertTrue(checksFor(cat(doubles(5.0, 200.0), values.subList(2, 30))).get("values_in_range").passed());
        assertTrue(checksFor(cat(doubles(1.0, 2.0, 300.0), values.subList(3, 30))).get("values_in_range").detail()
                .contains("3 of 30 outside"));
        assertTrue(checksFor(cat(values.subList(0, 5), doubles(5000.0), values.subList(6, 30)))
                .get("values_in_range").detail().contains("1 of 30 outside"));

        assertTrue(checksWithErrorRate(values, 0.05).get("error_rate").passed());
        assertFalse(checksWithErrorRate(values, 0.0501).get("error_rate").passed());
        assertFalse(checksWithErrorRate(values, 0.07).get("error_rate").passed());
        assertFalse(checksWithErrorRate(values, 0.2).get("error_rate").passed());

        int[][] cases = {{50, 50, 1}, {45, 50, 1}, {44, 50, 0}, {90, 100, 1}, {89, 100, 0}};
        for (int[] c : cases) {
            Check check = checksWithPlan(entesReadings(c[0]), c[1]).get("sampling_rate");
            assertEquals(check.passed(), c[2] == 1, check.detail());
            assertTrue(check.detail().contains(c[0] + " of " + c[1] + " planned readings taken"), check.detail());
        }
        Timing none = Analysis.consistency(List.of(), new SamplingPlan(50, 10));
        Map<String, Check> empty = new LinkedHashMap<>();
        for (Check c : Analysis.evaluate(List.of(), 0.0, none, ENTES, LIMITS)) {
            empty.put(c.name(), c);
        }
        assertFalse(empty.get("sampling_rate").passed());

        int[] sizes = {5, 9, 10, 20, 29, 30};
        boolean[] modelSkipped = {true, true, false, false, false, false};
        boolean[] driftSkipped = {true, true, true, true, true, false};
        for (int i = 0; i < sizes.length; i++) {
            Map<String, Check> checks = checksFor(entesReadings(sizes[i]));
            assertEquals(checks.get("matches_model").skipped(), modelSkipped[i], "n = " + sizes[i]);
            assertEquals(checks.get("no_drift").skipped(), driftSkipped[i], "n = " + sizes[i]);
        }

        // the configured limits are used
        double t = Math.abs(Analysis.consistency(samplesAt(values), new SamplingPlan(30, 10)).driftT());
        assertFalse(checksWithLimits(values, new Acceptance(0.05, 0.1, 3, 5.0, t * 0.9)).get("no_drift").passed());
        assertTrue(checksWithLimits(values, new Acceptance(0.05, 0.1, 3, 5.0, t * 1.1)).get("no_drift").passed());
        List<Double> four = cat(values.subList(0, 10), repeat(values.get(10), 4), values.subList(14, 30));
        assertFalse(checksFor(four).get("not_stuck").passed());
        assertTrue(checksWithLimits(four, new Acceptance(0.05, 0.1, 5, 5.0, 5.0)).get("not_stuck").passed());
    }

    @Test
    public void verdictRules() {
        List<Check> passing = List.of(Check.of("error_rate", true, ""));
        assertEquals(Analysis.verdict(passing, 10, "stopped after 5 failed measurements in a row"), Status.FAIL);
        assertEquals(Analysis.verdict(passing, 0, null), Status.ERROR);
        assertEquals(Analysis.verdict(passing, 0, "interrupted"), Status.INTERRUPTED);
        assertEquals(Analysis.verdict(List.of(), 0, "stopped after 5 failed measurements in a row"), Status.ERROR);
        assertEquals(Analysis.verdict(List.of(), 10, "interrupted"), Status.INTERRUPTED);

        List<String> names = List.of("values_in_range", "error_rate", "sampling_rate", "not_stuck", "matches_model",
                "no_drift");
        assertEquals(Analysis.verdict(names.stream().map(n -> Check.of(n, true, "")).toList(), 10, null),
                Status.PASS);
        for (String failing : names) { // any one failed check fails the run
            List<Check> checks = names.stream().map(n -> Check.of(n, !n.equals(failing), "")).toList();
            assertEquals(Analysis.verdict(checks, 10, null), Status.FAIL, failing);
        }
    }

    private static Run intervalRun(String name, double cv, double low, double high, double errorRate) {
        Statistics stats = new Statistics(30, null, null, null, null, null, cv, List.of(low, high), null, null, null,
                null, null);
        return run(name, name, Status.PASS, stats, errorRate, null);
    }

    private static Run intervalRun(String name, double cv, double low, double high) {
        return intervalRun(name, cv, low, high, 0.0);
    }

    private static Run run(String name, List<Double> values, Status status, double errorRate, Double expectedGeoMean) {
        return run(name, "run_" + name, status, Analysis.summarize(values), errorRate, expectedGeoMean);
    }

    private static Run run(String name, List<Double> values) {
        return run(name, values, Status.PASS, 0.0, null);
    }

    private static Run run(String name, List<Double> values, Status status) {
        return run(name, values, status, 0.0, null);
    }

    private static Run run(String name, List<Double> values, double errorRate) {
        return run(name, values, Status.PASS, errorRate, null);
    }

    private static Run run(String name, String runId, Status status, Statistics stats, double errorRate,
                           Double expectedGeoMean) {
        AmmeterSpec spec = new AmmeterSpec(name, 0, "X", "127.0.0.1", null, null, null, null, expectedGeoMean, null);
        return new Run(runId, status, spec, null, null, null, null, 0, stats, 0, errorRate, Map.of(), 0.0, null,
                List.of(), null, Map.of(), null, Map.of(), null);
    }

    private static List<String> ammeters(List<Comparison.Row> rows) {
        return rows.stream().map(Comparison.Row::ammeter).toList();
    }

    private static List<String> reasons(List<Comparison.Row> rows) {
        return rows.stream().map(Comparison.Row::reason).toList();
    }

    private static List<Double> thirds(double a, double b, double c, int times) {
        return repeat(doubles(a, b, c), times);
    }

    @Test
    public void clearlyMostPreciseRunWins() {
        Run steady = run("steady", thirds(99, 100, 101, 20));
        Run noisy = run("noisy", thirds(50, 100, 150, 20));
        Comparison result = Analysis.compare(List.of(noisy, steady));
        assertEquals(ammeters(result.rows()), List.of("steady", "noisy"));
        assertEquals(result.mostReliable(), "steady");
        assertNull(result.conclusion());

        List<Double> base = entesReadings(50);
        Run high = run("high", scaled(base, 1.02), Status.PASS, 0.0, geometricMean(base));
        Run exact = run("exact", base, Status.PASS, 0.03, geometricMean(base));
        Map<String, Comparison.Row> rows = new LinkedHashMap<>();
        Analysis.compare(List.of(high, exact)).rows().forEach(r -> rows.put(r.ammeter(), r));
        approx(rows.get("high").bias(), 0.02);
        approx(rows.get("exact").bias(), 0, 1e-12);

        Run fewer = run("fewer", base, 0.01);
        Run more = run("more", base, 0.03);
        // same CV: fewer errors first
        assertEquals(ammeters(Analysis.compare(List.of(more, fewer)).rows()), List.of("fewer", "more"));
        Comparison close = Analysis.compare(List.of(intervalRun("a", 0.5014, 0.4, 0.6),
                intervalRun("b", 0.5012, 0.4, 0.6, 0.03)));
        assertEquals(ammeters(close.rows()), List.of("b", "a")); // CVs are compared exactly, not rounded
        Comparison sameCv = Analysis.compare(List.of(intervalRun("x", 0.3, 0.2, 0.4, 0.014),
                intervalRun("y", 0.3, 0.2, 0.4, 0.012)));
        // and so are error rates
        assertEquals(sameCv.rows().stream().map(Comparison.Row::errorRate).toList(), List.of(0.012, 0.014));
    }

    @Test
    public void onlyPassingRunsWithEnoughReadingsAreRanked() {
        Comparison result = Analysis.compare(List.of(run("failed", doubles(100.0), Status.FAIL, 0.9, null),
                run("good", thirds(90, 100, 110, 10))));
        assertNull(result.mostReliable());
        assertEquals(ammeters(result.rows()), List.of("good"));
        assertEquals(result.notRanked().get(0).reason(), "status FAIL");
        assertTrue(result.conclusion().contains("insufficient evidence"));

        for (Status status : new Status[] {Status.FAIL, Status.ERROR, Status.INTERRUPTED}) {
            result = Analysis.compare(List.of(run("x", thirds(99, 100, 101, 15), status),
                    run("a", thirds(90, 100, 110, 15)), run("b", thirds(50, 100, 150, 15))));
            assertEquals(reasons(result.notRanked()), List.of("status " + status));
            assertEquals(result.mostReliable(), "a");
        }

        result = Analysis.compare(List.of(run("x", doubles(), Status.ERROR, 1.0, null),
                run("y", doubles(), Status.ERROR, 1.0, null)));
        assertNull(result.mostReliable());
        assertEquals(result.rows(), List.of());
        assertEquals(result.notRanked().size(), 2);

        result = Analysis.compare(List.of(run("short", thirds(99, 100, 101, 3)), run("a", thirds(90, 100, 110, 10)),
                run("b", thirds(50, 100, 150, 10))));
        assertEquals(ammeters(result.notRanked()), List.of("short"));
        assertTrue(result.notRanked().get(0).reason().contains("only 9 readings, 30 needed"));
        assertEquals(result.mostReliable(), "a");

        Run r29 = run("r29", thirds(90, 100, 110, 10).subList(0, 29));
        Run r30 = run("r30", thirds(50, 100, 150, 10));
        result = Analysis.compare(List.of(r29, r30));
        assertEquals(ammeters(result.notRanked()), List.of("r29"));
        assertEquals(ammeters(result.rows()), List.of("r30"));
        result = Analysis.compare(List.of(r29, r30), 40);
        assertEquals(reasons(result.notRanked()), List.of("only 29 readings, 40 needed", "only 30 readings, 40 needed"));

        result = Analysis.compare(List.of(run("zero", repeat(0.0, 30)), run("a", thirds(90, 100, 110, 10)),
                run("b", thirds(50, 100, 150, 10))));
        // a mean of 0 has no CV
        assertEquals(reasons(result.notRanked()), List.of("precision can't be computed"));
        assertEquals(result.mostReliable(), "a");
    }

    @Test
    public void overlappingPrecisionIsNotAWin() {
        Comparison result = Analysis.compare(List.of(run("first", entesReadings(50, 1)),
                run("second", entesReadings(50, 2))));
        assertNull(result.mostReliable());
        assertEquals(Set.copyOf(result.tied()), Set.of("first", "second"));
        assertTrue(result.conclusion().contains("equally precise"));

        Run best = run("best", thirds(99, 100, 101, 20));
        Run separate = run("separate", thirds(80, 100, 120, 20));
        // high CV, but an interval reaching down to 0
        Run wide = run("wide", cat(repeat(100.0, 58), doubles(1000.0, 1000.0)));
        result = Analysis.compare(List.of(best, separate, wide));
        assertEquals(ammeters(result.rows()), List.of("best", "separate", "wide"));
        assertNull(result.mostReliable());
        assertEquals(result.tied(), List.of("best", "wide"));

        Run small = run("small", thirds(0.009, 0.010, 0.011, 10)); // same relative spread, currents 1000x apart
        Run large = run("large", thirds(9, 10, 11, 10));
        List<Comparison.Row> rows = Analysis.compare(List.of(small, large)).rows();
        approx(rows.get(0).cv(), rows.get(1).cv());
        assertNull(Analysis.compare(List.of(small, large)).mostReliable());

        Comparison touching = Analysis.compare(List.of(intervalRun("low", 0.1, 0.05, 0.15),
                intervalRun("high", 0.2, 0.15, 0.25)));
        assertNull(touching.mostReliable());
    }
}
