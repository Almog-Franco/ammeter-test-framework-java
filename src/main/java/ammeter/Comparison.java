package ammeter;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The comparison of several runs.
 *
 * @param rows         the ranked runs, most precise first
 * @param mostReliable null unless one run is clearly the most precise
 * @param conclusion   why there is no winner, or null
 * @param folder       where the comparison was saved, once it is
 */
public record Comparison(List<Row> rows, List<Row> notRanked, int minReadings, String mostReliable,
                         List<String> tied, String conclusion,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String folder) {

    /** @param reason why the run isn't ranked; null for ranked runs */
    public record Row(String ammeter, String runId, Status status, int count, Double mean, Double median, Double cv,
                      List<Double> cvCi95, Double bias, double errorRate,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

        Row withReason(String newReason) {
            return new Row(ammeter, runId, status, count, mean, median, cv, cvCi95, bias, errorRate, newReason);
        }
    }

    public Comparison withFolder(String newFolder) {
        return new Comparison(rows, notRanked, minReadings, mostReliable, tied, conclusion, newFolder);
    }
}
