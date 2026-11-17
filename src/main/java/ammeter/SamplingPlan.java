package ammeter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** How many readings to take and how often; the duration follows from them. */
@JsonIgnoreProperties(value = "total_duration_seconds", allowGetters = true)
public record SamplingPlan(@JsonProperty("measurements_count") int count,
                           @JsonProperty("sampling_frequency_hz") double frequencyHz) {

    @JsonProperty("total_duration_seconds")
    public double durationS() {
        return count / frequencyHz;
    }

    /** Builds a plan from any two of the three values, since count = duration * frequency. Null means not given. */
    public static SamplingPlan fromConfig(Object count, Object durationS, Object frequencyHz) {
        String[] keys = {"measurements_count", "total_duration_seconds", "sampling_frequency_hz"};
        Object[] values = {count, durationS, frequencyHz};
        int given = 0;
        for (Object value : values) {
            given += value == null ? 0 : 1;
        }
        if (given < 2) {
            throw new ConfigException(
                    "sampling: set at least two of measurements_count, total_duration_seconds, sampling_frequency_hz");
        }
        for (int i = 0; i < keys.length; i++) {
            Object value = values[i];
            if (value != null && !(value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                    && ((Number) value).doubleValue() > 0)) {
                throw new ConfigException("sampling: " + keys[i] + " must be a positive number, got " + Fmt.repr(value));
            }
        }
        Double n = count == null ? null : ((Number) count).doubleValue();
        Double d = durationS == null ? null : ((Number) durationS).doubleValue();
        Double f = frequencyHz == null ? null : ((Number) frequencyHz).doubleValue();
        if (n != null && n != Math.floor(n)) {
            throw new ConfigException("sampling: measurements_count must be a whole number, got " + Fmt.str(count));
        }

        if (f == null) {
            f = n / d;
        } else if (n == null) {
            n = Math.floor(d * f + 1e-9);
            if (n < 1) {
                throw new ConfigException("sampling: " + Fmt.str(durationS) + " s at " + Fmt.str(frequencyHz)
                        + " Hz is not even one measurement");
            }
        } else if (d != null && !isClose(n, d * f, 0.01)) {
            throw new ConfigException("sampling: " + Fmt.str(count) + " measurements at " + Fmt.str(frequencyHz)
                    + " Hz take " + Fmt.g(n / f) + " s, not " + Fmt.str(durationS) + " s");
        }
        return new SamplingPlan((int) Math.min(n, Integer.MAX_VALUE), f);
    }

    /** Python's {@code math.isclose(a, b, rel_tol=relTol)}. */
    static boolean isClose(double a, double b, double relTol) {
        return a == b || Math.abs(a - b) <= relTol * Math.max(Math.abs(a), Math.abs(b));
    }
}
