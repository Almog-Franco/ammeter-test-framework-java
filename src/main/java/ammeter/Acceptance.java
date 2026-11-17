package ammeter;

/** The limits a run must stay within to pass. */
public record Acceptance(double maxErrorRate, double maxFrequencyError, int stuckAfter, double maxModelZ,
                         double maxDriftT) {
    public static final Acceptance DEFAULT = new Acceptance(0.05, 0.1, 3, 5.0, 5.0);
}
