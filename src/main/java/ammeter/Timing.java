package ammeter;

/**
 * How steady a run was: timing of the requests, and whether the readings drift over time.
 *
 * @param driftExact true when the readings lie exactly on a sloped straight line, so no t value exists
 */
public record Timing(int planned, int taken, double requestedHz, double achievedHz, double intervalJitterMs,
                     double maxLagMs, Double latencyMeanMs, Double latencyP95Ms, Double latencyMaxMs,
                     Double driftPct, Double driftT, boolean driftExact) {
}
