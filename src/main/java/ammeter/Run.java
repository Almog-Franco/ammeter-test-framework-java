package ammeter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything recorded about one test run; saved as {@code run.json}.
 *
 * @param errors        failed readings per error type
 * @param faultInjection the faults the emulators injected, or null
 * @param settings      the effective settings, after profile and command-line overrides
 */
public record Run(String runId, Status status, AmmeterSpec ammeter, SamplingPlan sampling, String startedAt,
                  String finishedAt, String stopReason, int missed, Statistics statistics, int errorCount,
                  double errorRate, Map<String, Integer> errors, double outOfRangeRate, Timing consistency,
                  List<Check> checks, FaultOptions faultInjection, Map<String, Object> settings, ConfigInfo config,
                  Map<String, String> environment, String logFile) {

    /** What the emulators were asked to inject. {@code seed} is a whole number, text or null. */
    public record FaultOptions(Map<String, Double> rates, Object seed, double delayS) {

        /** As written in a report: {@code {'rates': {...}, 'seed': 7, 'delay_s': 3.0}}. */
        @Override
        public String toString() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rates", rates);
            map.put("seed", seed);
            map.put("delay_s", delayS);
            return Fmt.repr(map);
        }
    }

    /** The config file the run used. {@code content} is the file as read, before overrides. */
    public record ConfigInfo(String path, String sha256, String profile, Map<String, Object> content) {
    }
}
