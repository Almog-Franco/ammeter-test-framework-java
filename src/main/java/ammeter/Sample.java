package ammeter;

/**
 * One reading attempt.
 *
 * @param index    the time slot it was taken in
 * @param timeS    when the request started, relative to the start of the run
 * @param lagS     how much later than its slot start it began
 * @param latencyS how long the request took
 * @param value    the reading, or null if it failed
 * @param error    why it failed, e.g. {@code "AmmeterTimeoutException: no complete answer ..."}
 */
public record Sample(int index, double timeS, double lagS, double latencyS, Double value, String error) {
    public Sample(int index, double timeS, double lagS, double latencyS, Double value) {
        this(index, timeS, lagS, latencyS, value, null);
    }
}
