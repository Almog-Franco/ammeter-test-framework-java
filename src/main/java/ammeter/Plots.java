package ammeter;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.BoxChart;
import org.knowm.xchart.BoxChartBuilder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.CategorySeries;
import org.knowm.xchart.CategorySeries.CategorySeriesRenderStyle;
import org.knowm.xchart.AnnotationText;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.XYSeries.XYSeriesRenderStyle;
import org.knowm.xchart.internal.chartpart.Chart;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** PNG charts of runs, comparisons and history, drawn with XChart. */
public final class Plots {
    private static final Color BLUE = new Color(0x1f77b4);
    private static final Color RED = new Color(0xd62728);
    private static final Color GREEN = new Color(0x2ca02c);
    private static final Color ORANGE = new Color(0xff7f0e);
    private static final Color RED_BAND = new Color(0xd6, 0x27, 0x28, 0x60);
    private static final BasicStroke DASHED = new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0);

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private Plots() {
    }

    /** Long-tailed readings (Greenlee) or spikes are unreadable on a linear axis. */
    static boolean spansDecades(List<Double> values) {
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return min > 0 && max / min > 100;
    }

    public static List<Path> plotRun(Run run, List<Sample> samples, Path outDir, List<String> plotTypes) {
        List<Double> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Sample s : samples) {
            if (s.value() != null) {
                times.add(s.timeS());
                values.add(s.value());
            }
        }
        List<Path> paths = new ArrayList<>();
        if (values.isEmpty()) {
            return paths;
        }
        Statistics stats = run.statistics();
        String name = run.ammeter().name();
        double mean = stats.mean();
        double std = stats.stdDev();
        double min = stats.min();
        double max = stats.max();
        boolean logScale = spansDecades(values);
        double first = times.get(0);
        double last = times.get(times.size() - 1);

        if (plotTypes.contains("timeseries")) {
            XYChart chart = new XYChartBuilder().width(990).height(440)
                    .title(name + ": readings over time (" + run.status() + ")").xAxisTitle("time (s)").yAxisTitle("current (A)").build();
            style(chart);
            chart.getStyler().setYAxisLogarithmic(logScale);
            XYSeries readings = chart.addSeries("reading", times, values);
            readings.setLineColor(BLUE).setLineWidth(0.8f);
            readings.setMarker(SeriesMarkers.CIRCLE).setMarkerColor(BLUE);
            line(chart, "mean " + Fmt.g(mean, 4) + " A", first, last, mean, RED, null);
            line(chart, "mean ± 1 std", first, last, mean + std, RED_BAND, DASHED);
            line(chart, "mean - 1 std", first, last, Math.max(mean - std, min / 2), RED_BAND, DASHED).setShowInLegend(false);
            List<Double> failed = samples.stream().filter(s -> s.error() != null).map(Sample::timeS).toList();
            if (!failed.isEmpty()) {
                XYSeries marks = chart.addSeries("failed (" + failed.size() + ")", failed, failed.stream().map(t -> min).toList());
                marks.setXYSeriesRenderStyle(XYSeriesRenderStyle.Scatter);
                marks.setMarker(SeriesMarkers.CROSS).setMarkerColor(Color.BLACK);
            }
            paths.add(save(chart, outDir.resolve("timeseries.png")));
        }

        if (plotTypes.contains("histogram")) {
            double[] edges = logScale ? logBins(min, max, 30) : linearBins(min, max, sturges(values.size()));
            int[] counts = new int[edges.length - 1];
            for (double v : values) {
                int bin = edges.length - 2;
                while (bin > 0 && v < edges[bin]) {
                    bin--;
                }
                counts[bin]++;
            }
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            int highest = 0;
            for (int i = 0; i < counts.length; i++) {
                xs.addAll(List.of(edges[i], edges[i], edges[i + 1], edges[i + 1]));
                ys.addAll(List.of(0.0, (double) counts[i], (double) counts[i], 0.0));
                highest = Math.max(highest, counts[i]);
            }
            XYChart chart = new XYChartBuilder().width(660).height(440)
                    .title(name + ": distribution").xAxisTitle("current (A)").yAxisTitle("count").build();
            style(chart);
            chart.getStyler().setXAxisLogarithmic(logScale);
            XYSeries bars = chart.addSeries("readings", xs, ys);
            bars.setXYSeriesRenderStyle(XYSeriesRenderStyle.Area);
            bars.setLineColor(BLUE).setLineWidth(0.5f);
            bars.setFillColor(new Color(0x1f, 0x77, 0xb4, 0xcc));
            bars.setMarker(SeriesMarkers.NONE).setShowInLegend(false);
            double top = Math.max(1, highest);
            vertical(chart, "mean " + Fmt.g(mean, 4) + " A", mean, top, RED, null);
            vertical(chart, "median " + Fmt.g(stats.median(), 4) + " A", stats.median(), top, GREEN, DASHED);
            paths.add(save(chart, outDir.resolve("histogram.png")));
        }
        return paths;
    }

    /** One run and its samples, as plotted side by side by {@link #plotComparison}. */
    public record RunSamples(Run run, List<Sample> samples) {
    }

    /** Readings divided by each ammeter's expected mean, so ranges 1000x apart fit on one chart. */
    public static Path plotComparison(List<RunSamples> runs, Path path) {
        List<String> names = new ArrayList<>();
        List<double[]> data = new ArrayList<>();
        List<double[]> intervals = new ArrayList<>();
        for (RunSamples pair : runs) {
            double[] values = pair.samples().stream()
                    .filter(s -> s.value() != null && s.value() > 0).mapToDouble(Sample::value).toArray();
            if (values.length == 0) {
                continue;
            }
            Statistics stats = pair.run().statistics();
            Double expected = pair.run().ammeter().expectedMean();
            double reference = expected != null && expected != 0 ? expected : stats.median();
            for (int i = 0; i < values.length; i++) {
                values[i] /= reference;
            }
            names.add(pair.run().ammeter().name());
            data.add(values);
            double cv = stats.cv() == null ? 0 : stats.cv();
            List<Double> ci = stats.cvCi95() == null || stats.cvCi95().isEmpty() ? List.of(cv, cv) : stats.cvCi95();
            intervals.add(new double[]{Math.min(cv, ci.get(0)), cv, Math.max(cv, ci.get(1))});
        }
        if (data.isEmpty()) {
            return null;
        }

        BoxChart left = new BoxChartBuilder().width(605).height(440).title("Readings / expected mean (log scale)").yAxisTitle("relative current").build();
        style(left);
        left.getStyler().setYAxisLogarithmic(true);
        for (int i = 0; i < names.size(); i++) {
            left.addSeries(names.get(i), data.get(i));
        }

        XYChart right = new XYChartBuilder().width(605).height(440)
                .title("Precision: CV with 95% interval (lower is better)").yAxisTitle("CV").build();
        style(right);
        double top = intervals.stream().mapToDouble(ci -> ci[2]).max().orElse(1);
        top = top > 0 ? top * 1.15 : 1;
        // XChart hides custom category labels on numeric axes, so the names are drawn as annotations
        right.getStyler().setXAxisMin(0.5).setXAxisMax(names.size() + 0.5).setYAxisMin(0.0).setYAxisMax(top);
        right.getStyler().setXAxisTicksVisible(false);
        for (int i = 0; i < names.size(); i++) {
            double[] ci = intervals.get(i);
            XYSeries series = right.addSeries(names.get(i), new double[]{i + 1, i + 1, i + 1}, ci);
            series.setLineColor(ORANGE).setLineWidth(2f);
            series.setMarker(SeriesMarkers.DIAMOND).setMarkerColor(ORANGE);
            right.addAnnotation(new AnnotationText(names.get(i), i + 1, ci[2] + top * 0.05, false));
        }
        right.getStyler().setLegendVisible(false);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                BitmapEncoder.saveBitmap(List.of(left, right), 1, 2, out, BitmapFormat.PNG);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    public static Path plotHistory(List<ResultStore.IndexEntry> entries, Path path) {
        List<ResultStore.IndexEntry> withMean = entries.stream().filter(e -> e.mean() != null).toList();
        if (withMean.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        List<Double> means = new ArrayList<>();
        List<Double> margins = new ArrayList<>();
        for (ResultStore.IndexEntry e : withMean) {
            String started = e.startedAt();
            labels.add(started.substring(0, Math.min(19, started.length())).replace("T", " "));
            means.add(e.mean());
            margins.add(1.96 * (e.stdDev() == null ? 0 : e.stdDev()) / Math.sqrt(e.count()));
        }
        CategoryChart chart = new CategoryChartBuilder().width(990).height(440)
                .title(withMean.get(0).ammeter() + ": mean and 95% CI per run").yAxisTitle("current (A)").build();
        style(chart);
        chart.getStyler().setDefaultSeriesRenderStyle(CategorySeriesRenderStyle.Line);
        chart.getStyler().setLegendVisible(false);
        if (labels.size() > 6) {
            chart.getStyler().setXAxisLabelRotation(30);
        }
        CategorySeries series = chart.addSeries("mean", labels, means, margins);
        series.setLineColor(BLUE);
        series.setMarker(SeriesMarkers.CIRCLE).setMarkerColor(BLUE);
        return save(chart, path);
    }

    private static int sturges(int n) {
        return (int) Math.ceil(Math.log(n) / Math.log(2)) + 1;
    }

    private static double[] linearBins(double low, double high, int bins) {
        if (high <= low) {
            low -= 0.5;
            high += 0.5;
        }
        double[] edges = new double[bins + 1];
        for (int i = 0; i <= bins; i++) {
            edges[i] = low + (high - low) * i / bins;
        }
        return edges;
    }

    private static double[] logBins(double low, double high, int bins) {
        double[] edges = new double[bins + 1];
        for (int i = 0; i <= bins; i++) {
            edges[i] = low * Math.pow(high / low, (double) i / bins);
        }
        return edges;
    }

    private static XYSeries line(XYChart chart, String name, double x0, double x1, double y, Color color, BasicStroke stroke) {
        XYSeries series = chart.addSeries(name, new double[]{x0, x1}, new double[]{y, y});
        series.setLineColor(color);
        series.setMarker(SeriesMarkers.NONE);
        if (stroke != null) {
            series.setLineStyle(stroke);
        }
        return series;
    }

    private static void vertical(XYChart chart, String name, double x, double top, Color color, BasicStroke stroke) {
        XYSeries series = chart.addSeries(name, new double[]{x, x}, new double[]{0, top});
        series.setLineColor(color).setLineWidth(1.5f);
        series.setMarker(SeriesMarkers.NONE);
        if (stroke != null) {
            series.setLineStyle(stroke);
        }
    }

    private static void style(Chart<?, ?> chart) {
        Styler styler = chart.getStyler();
        styler.setChartBackgroundColor(Color.WHITE);
        styler.setPlotBackgroundColor(Color.WHITE);
        styler.setLegendFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        styler.setLegendPosition(Styler.LegendPosition.InsideNE);
        styler.setChartTitleFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    }

    private static Path save(Chart<?, ?> chart, Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                BitmapEncoder.saveBitmap(chart, out, BitmapFormat.PNG);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }
}
