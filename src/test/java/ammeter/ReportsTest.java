package ammeter;

import static ammeter.TestSupport.ENTES;
import static ammeter.TestSupport.entesReadings;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.testng.annotations.Test;

public class ReportsTest {
    private static final Path GOLDEN = Settings.PROJECT_ROOT.resolve("src/test/resources/golden");

    /** A Mersenne Twister (MT19937) generator, so the readings in the golden files never change. */
    private static final class MersenneTwister {
        private final int[] mt = new int[624];
        private int pos = 624;

        MersenneTwister(int seed) {
            init(19650218);
            int i = 1;
            int j = 0;
            for (int k = 624; k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + seed + j;
                i++;
                j = 0;
                if (i >= 624) {
                    mt[0] = mt[623];
                    i = 1;
                }
            }
            for (int k = 623; k > 0; k--) {
                mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
                i++;
                if (i >= 624) {
                    mt[0] = mt[623];
                    i = 1;
                }
            }
            mt[0] = 0x80000000;
        }

        private void init(int seed) {
            mt[0] = seed;
            for (int i = 1; i < 624; i++) {
                mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            }
        }

        private int next() {
            if (pos >= 624) {
                for (int k = 0; k < 624; k++) {
                    int y = (mt[k] & 0x80000000) | (mt[(k + 1) % 624] & 0x7fffffff);
                    mt[k] = mt[(k + 397) % 624] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                pos = 0;
            }
            int y = mt[pos++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            return y ^ (y >>> 18);
        }

        double random() {
            long a = Integer.toUnsignedLong(next()) >>> 5;
            long b = Integer.toUnsignedLong(next()) >>> 6;
            return (a * 67108864.0 + b) / 9007199254740992.0;
        }

        double uniform(double low, double high) {
            return low + (high - low) * random();
        }
    }

    private static List<Double> goldenEntesReadings(int n, int seed) {
        MersenneTwister rng = new MersenneTwister(seed);
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double b = rng.uniform(0.01, 0.1);
            double k = rng.uniform(500, 2000);
            values.add(b * k);
        }
        return values;
    }

    private static Run run(List<Double> values, Integer planned, int missed, AmmeterSpec spec, double interval) {
        int plannedCount = planned == null ? values.size() : planned;
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            samples.add(new Sample(i, i * interval, 0.0, 0.001, values.get(i)));
        }
        SamplingPlan plan = new SamplingPlan(plannedCount, 1 / interval);
        Timing timing = Analysis.consistency(samples, plan);
        List<Check> checks = Analysis.evaluate(values, 0.0, timing, spec, Acceptance.DEFAULT);
        return new Run("20261026T120000Z_entes_abc123", Analysis.verdict(checks, values.size(), null), spec, plan,
                "2026-10-26T12:00:00+00:00", null, null, missed, Analysis.summarize(values), 0, 0.0, Map.of(), 0.0,
                timing, checks, null, null, null, null, null);
    }

    private static Run run(List<Double> values) {
        return run(values, null, 0, ENTES, 0.1);
    }

    private static Run run(List<Double> values, AmmeterSpec spec) {
        return run(values, null, 0, spec, 0.1);
    }

    private static Run withErrorRate(Run r, double errorRate) {
        return new Run(r.runId(), r.status(), r.ammeter(), r.sampling(), r.startedAt(), r.finishedAt(), r.stopReason(),
                r.missed(), r.statistics(), r.errorCount(), errorRate, r.errors(), r.outOfRangeRate(), r.consistency(),
                r.checks(), r.faultInjection(), r.settings(), r.config(), r.environment(), r.logFile());
    }

    private static Run withFailures(Run r, String stopReason, Map<String, Integer> errors, int errorCount,
                                    Run.FaultOptions faults) {
        return new Run(r.runId(), r.status(), r.ammeter(), r.sampling(), r.startedAt(), r.finishedAt(), stopReason,
                r.missed(), r.statistics(), errorCount, r.errorRate(), errors, r.outOfRangeRate(), r.consistency(),
                r.checks(), faults, r.settings(), r.config(), r.environment(), r.logFile());
    }

    private static AmmeterSpec named(String name, int port, Double expectedGeoMean) {
        return new AmmeterSpec(name, port, "X", "127.0.0.1", null, null, null, null, expectedGeoMean, null);
    }

    private static AmmeterSpec entesAs(String name, double expectedGeoMean) {
        return new AmmeterSpec(name, ENTES.port(), ENTES.command(), ENTES.host(), ENTES.emulator(), ENTES.expectedMin(),
                ENTES.expectedMax(), ENTES.expectedMean(), expectedGeoMean, ENTES.expectedLogStd());
    }

    private static void assertContains(String text, String part) {
        assertTrue(text.contains(part), "missing: " + part + "\nin:\n" + text);
    }

    private static void assertMatches(String text, String regex) {
        assertTrue(Pattern.compile(regex).matcher(text).find(), "no match: " + regex + "\nin:\n" + text);
    }

    @Test
    public void runReport() {
        String text = Reports.runReport(run(entesReadings(20), 22, 2, ENTES, 0.1), Settings.METRICS);
        assertContains(text, "20 valid readings of 22 planned, 0 failed, 2 missed");
        assertContains(text, "| no_drift | SKIP | skipped: needs at least 30 readings |");
        assertContains(text, "| matches_model | PASS |");
        assertContains(text, "| Geometric mean |");

        Run failing = withFailures(run(entesReadings(20)), "stopped after 5 failed measurements in a row",
                Map.of("AmmeterTimeoutException", 5), 5, new Run.FaultOptions(Map.of("timeout", 0.1), null, 0.0));
        text = Reports.runReport(failing, Settings.METRICS);
        assertContains(text, "stopped after 5 failed measurements in a row");
        assertContains(text, "Errors: AmmeterTimeoutException x5");
        assertContains(text, "Fault injection was on");

        List<Double> line = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            line.add(10.0 + i);
        }
        text = Reports.runReport(run(line), Settings.METRICS); // an exact line is drift, not noise
        assertFalse(text.contains("not significant"));
        assertContains(text, "| no_drift | FAIL |");
        assertContains(text, "## entes: FAIL");
        List<Double> fall = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            fall.add(100.0 - 2 * i);
        }
        String falling = Reports.runReport(run(fall, null, 0, ENTES, 1.0), Settings.METRICS);
        assertContains(falling, "| Drift over the run | -");
        assertContains(falling, "exact straight line");
    }

    @Test
    public void comparisonReport() {
        AmmeterSpec first = named("first", 1, null);
        AmmeterSpec second = named("second", 2, null);
        String tie = Reports.comparisonReport(Analysis.compare(List.of(
                run(entesReadings(50, 1), first), run(entesReadings(50, 2), second))));
        assertContains(tie, "No winner, insufficient evidence");
        assertFalse(tie.contains("Most reliable"));
        assertTrue(tie.contains("doesn't show") && tie.contains("more accurate"));
        assertMatches(tie, "(first, second|second, first) are equally precise");
        assertMatches(tie, "\\| \\d\\.\\d+ \\(\\d\\.\\d+ to \\d\\.\\d+\\) \\|");

        List<Double> narrowValues = new ArrayList<>();
        List<Double> wideValues = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            narrowValues.addAll(List.of(99.0, 100.0, 101.0));
            wideValues.addAll(List.of(50.0, 100.0, 150.0));
        }
        Run narrow = withErrorRate(run(narrowValues, named("narrow", 1, 101.0)), 0.01);
        Run wide = withErrorRate(run(wideValues, named("wide", 2, 99.0)), 0.04);
        String text = Reports.comparisonReport(Analysis.compare(List.of(narrow, wide)));
        assertContains(text, "Most reliable in this test: **narrow**");
        assertContains(text, "It doesn't show");
        assertTrue(text.indexOf("| 1 | narrow |") < text.indexOf("| 2 | wide |"));
        assertTrue(text.contains("| -1.0% | 1.0% |") && text.contains("| -8.2% | 4.0% |"));

        List<Double> stuck = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            stuck.add(5.0);
        }
        assertContains(Reports.comparisonReport(Analysis.compare(List.of(run(stuck), run(entesReadings(50))))),
                "Not ranked: entes (status FAIL)");
        List<Double> stuckSix = stuck.stream().map(v -> 6.0).toList();
        assertContains(Reports.comparisonReport(Analysis.compare(List.of(run(stuck, first), run(stuckSix, second)))),
                "Not ranked: first (status FAIL); second (status FAIL)");
    }

    @Test
    public void reportsMatchTheSavedCopies() throws IOException {
        List<Double> highValues = new ArrayList<>(goldenEntesReadings(50, 1));
        highValues.add(190.0);
        // exact in floating point, so the timing figures are exact too
        Run high = run(highValues, null, 0, entesAs("high", 50.0), 0.125);
        Run low = run(goldenEntesReadings(50, 2), null, 0, entesAs("low", 70.0), 0.125);
        List<ResultStore.IndexEntry> entries = new ArrayList<>();
        for (Run r : List.of(high, low)) { // what the archive index stores
            Statistics s = r.statistics();
            entries.add(new ResultStore.IndexEntry(r.runId(), r.ammeter().name(), r.status(), r.startedAt(), s.count(),
                    s.mean(), s.stdDev(), s.cv(), r.errorRate()));
        }
        Map<String, String> reports = new LinkedHashMap<>();
        reports.put("run_report.md", Reports.runReport(high, Settings.METRICS));
        reports.put("comparison_report.md", Reports.comparisonReport(Analysis.compare(List.of(high, low))));
        reports.put("runs_table.md", Reports.runsTable(entries));
        for (Map.Entry<String, String> report : reports.entrySet()) {
            Path file = GOLDEN.resolve(report.getKey());
            if (System.getenv("UPDATE_GOLDEN") != null && !System.getenv("UPDATE_GOLDEN").isEmpty()) {
                Files.createDirectories(GOLDEN);
                Files.writeString(file, report.getValue(), StandardCharsets.UTF_8);
            }
            assertEquals(report.getValue(), readGolden(file), report.getKey() + " changed (if intended: UPDATE_GOLDEN=1)");
        }
    }

    private static String readGolden(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
