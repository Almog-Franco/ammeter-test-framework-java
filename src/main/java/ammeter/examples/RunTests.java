package ammeter.examples;

import ammeter.AmmeterTestFramework;
import ammeter.Comparison;
import ammeter.Run;
import ammeter.Statistics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ammeter.Reports.fmt;

/** Tests the three ammeters one by one and compares them, using the framework as a library. */
public final class RunTests {
    private RunTests() {
    }

    public static void main(String[] args) {
        Map<String, Run> results = new LinkedHashMap<>();
        Comparison ranking;
        try (AmmeterTestFramework framework = new AmmeterTestFramework()) {
            for (String ammeterType : List.of("greenlee", "entes", "circutor")) {
                System.out.println("Testing " + ammeterType + " ammeter...");
                results.put(ammeterType, framework.runTest(ammeterType));
            }
            ranking = framework.compareRuns(results.values().stream().map(Run::runId).toList());
        }

        results.forEach((ammeterType, result) -> {
            Statistics stats = result.statistics();
            System.out.println("\nResults for " + ammeterType + ": " + result.status() + " (" + stats.count() + " readings)");
            System.out.println("  mean " + fmt(stats.mean()) + " A, median " + fmt(stats.median()) + " A, "
                    + "std " + fmt(stats.stdDev()) + " A, min " + fmt(stats.min()) + " A, max " + fmt(stats.max()) + " A");
            System.out.println("  saved as " + result.runId());
        });
        System.out.println("\nMost reliable in this test: "
                + (ranking.mostReliable() != null ? ranking.mostReliable() : "no conclusion, " + ranking.conclusion()));
    }
}
