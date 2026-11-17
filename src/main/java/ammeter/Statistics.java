package ammeter;

import java.util.List;

/**
 * Summary statistics of the valid readings. With no readings only {@code count} is set; {@code cv} is null when the
 * mean is 0 and {@code geoMean} when a reading is 0 or negative.
 *
 * @param stdDev sample standard deviation (n - 1)
 */
public record Statistics(int count, Double mean, Double median, Double stdDev, Double min, Double max, Double cv,
                         List<Double> cvCi95, Double geoMean, List<Double> ci95, Double q1, Double q3,
                         Integer outliers) {

    public static Statistics empty() {
        return new Statistics(0, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The value of one of {@link Settings#METRICS}. */
    public Object get(String metric) {
        return switch (metric) {
            case "mean" -> mean;
            case "median" -> median;
            case "std_dev" -> stdDev;
            case "min" -> min;
            case "max" -> max;
            case "cv" -> cv;
            case "geo_mean" -> geoMean;
            case "ci95" -> ci95;
            case "outliers" -> outliers;
            default -> throw new IllegalArgumentException("unknown metric '" + metric + "'");
        };
    }
}
