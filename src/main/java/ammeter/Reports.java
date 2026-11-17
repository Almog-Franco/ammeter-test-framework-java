package ammeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Markdown reports of a run, a comparison and the archive. */
public final class Reports {
    public static final Map<String, String> LABELS = Map.of(
            "mean", "Mean",
            "median", "Median",
            "std_dev", "Std deviation",
            "min", "Min",
            "max", "Max",
            "cv", "CV (std / mean)",
            "geo_mean", "Geometric mean",
            "ci95", "95% CI of the mean",
            "outliers", "Outliers (1.5 IQR rule)");

    private Reports() {
    }

    /** A number with 4 significant digits, a whole number as it is, and "-" for none. */
    public static String fmt(Object value, String unit) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Double || value instanceof Float) {
            return Fmt.g(((Number) value).doubleValue(), 4) + unit;
        }
        return value + unit;
    }

    public static String fmt(Object value) {
        return fmt(value, "");
    }

    public static String pct(Double value, boolean signed) {
        return value == null ? "-" : Fmt.percent(value, 1, signed);
    }

    public static String pct(Double value) {
        return pct(value, false);
    }

    public static String runReport(Run run, List<String> metrics) {
        AmmeterSpec ammeter = run.ammeter();
        Statistics stats = run.statistics();
        Timing timing = run.consistency();
        String failed = run.errorCount() + " failed, " + run.missed() + " missed (their time slot had passed)";
        if (run.stopReason() != null && !run.stopReason().isEmpty()) {
            failed += ", " + run.stopReason();
        }
        List<String> lines = new ArrayList<>(List.of(
                "## " + ammeter.name() + ": " + run.status(),
                "",
                "Run `" + run.runId() + "`, " + ammeter.host() + ":" + ammeter.port() + ", started " + run.startedAt(),
                stats.count() + " valid readings of " + run.sampling().count() + " planned, " + failed,
                "",
                "| Metric | Value |",
                "|---|---|"));
        if (stats.count() > 0) {
            for (String metric : metrics) {
                Object value = stats.get(metric);
                String text;
                if (metric.equals("ci95")) {
                    List<?> ci = (List<?>) value;
                    text = fmt(ci.get(0)) + " to " + fmt(ci.get(1)) + " A";
                } else if (metric.equals("cv") || metric.equals("outliers")) {
                    text = fmt(value);
                } else {
                    text = fmt(value, " A");
                }
                lines.add("| " + LABELS.get(metric) + " | " + text + " |");
            }
        }

        lines.addAll(List.of(
                "",
                "| Timing and stability | Value |",
                "|---|---|",
                "| Sampling rate | " + fmt(timing.achievedHz()) + " Hz (requested " + fmt(timing.requestedHz())
                        + " Hz), " + timing.taken() + " of " + timing.planned() + " readings taken |",
                "| Interval jitter (std) | " + fmt(timing.intervalJitterMs()) + " ms |",
                "| Max delay vs schedule | " + fmt(timing.maxLagMs()) + " ms |",
                "| Response time mean / p95 / max | " + fmt(timing.latencyMeanMs()) + " / "
                        + fmt(timing.latencyP95Ms()) + " / " + fmt(timing.latencyMaxMs()) + " ms |",
                "| Drift over the run | " + drift(timing) + " |",
                "",
                "| Check | Result | Detail |",
                "|---|---|---|"));
        for (Check c : run.checks()) {
            lines.add("| " + c.name() + " | " + (c.skipped() ? "SKIP" : c.passed() ? "PASS" : "FAIL") + " | "
                    + c.detail() + " |");
        }
        if (run.errors() != null && !run.errors().isEmpty()) {
            lines.add("");
            lines.add("Errors: " + run.errors().entrySet().stream().map(e -> e.getKey() + " x" + e.getValue())
                    .collect(Collectors.joining(", ")));
        }
        if (run.faultInjection() != null) {
            lines.add("");
            lines.add("Fault injection was on: " + run.faultInjection());
        }
        return String.join("\n", lines) + "\n";
    }

    private static String drift(Timing timing) {
        if (timing.driftPct() == null) {
            return "-";
        }
        String pct = Fmt.g(timing.driftPct(), 3, true) + " %";
        if (timing.driftExact()) {
            return pct + " (readings on an exact straight line)";
        }
        if (timing.driftT() == null) {
            return pct;
        }
        return pct + " (t = " + Fmt.f(timing.driftT(), 2, true) + ")";
    }

    public static String comparisonReport(Comparison comparison) {
        List<String> lines = new ArrayList<>(List.of("## Comparison", ""));
        if (!comparison.rows().isEmpty()) {
            lines.add("| Rank | Ammeter | n | Mean (A) | Median (A) | CV (95% CI) | Geo. mean vs model | Errors |");
            lines.add("|---|---|---|---|---|---|---|---|");
            int rank = 1;
            for (Comparison.Row row : comparison.rows()) {
                lines.add("| " + rank++ + " | " + row.ammeter() + " | " + row.count() + " | " + fmt(row.mean()) + " | "
                        + fmt(row.median()) + " | " + fmt(row.cv()) + " (" + fmt(row.cvCi95().get(0)) + " to "
                        + fmt(row.cvCi95().get(1)) + ") | " + pct(row.bias(), true) + " | " + pct(row.errorRate())
                        + " |");
            }
            lines.add("");
        }
        if (!comparison.notRanked().isEmpty()) {
            lines.add("Not ranked: " + comparison.notRanked().stream().map(r -> r.ammeter() + " (" + r.reason() + ")")
                    .collect(Collectors.joining("; ")));
            lines.add("");
        }
        if (comparison.mostReliable() != null) {
            lines.add("Most reliable in this test: **" + comparison.mostReliable() + "**, the most precise (lowest CV) "
                    + "of the runs that passed every check, and clearly so: its 95% interval doesn't overlap the others.");
        } else {
            lines.add("No winner, " + comparison.conclusion() + ".");
        }
        lines.add("");
        lines.add("This compares the emulators, whose spread comes from the random input ranges in their code. "
                + "It doesn't show");
        lines.add("which measurement method is more accurate: that needs every meter measuring the same known current.");
        return String.join("\n", lines) + "\n";
    }

    public static String runsTable(List<ResultStore.IndexEntry> entries) {
        if (entries.isEmpty()) {
            return "No runs found.\n";
        }
        List<String> lines = new ArrayList<>(List.of(
                "| Run | Ammeter | Status | n | Mean (A) | Std (A) | CV | Errors |",
                "|---|---|---|---|---|---|---|---|"));
        for (ResultStore.IndexEntry e : entries) {
            lines.add("| " + e.runId() + " | " + e.ammeter() + " | " + e.status() + " | " + e.count() + " | "
                    + fmt(e.mean()) + " | " + fmt(e.stdDev()) + " | " + fmt(e.cv()) + " | " + pct(e.errorRate())
                    + " |");
        }
        return String.join("\n", lines) + "\n";
    }
}
