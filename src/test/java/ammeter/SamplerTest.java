package ammeter;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class SamplerTest {
    private static final class FakeClock {
        double now = 0.0;

        double time() {
            return now;
        }

        void sleep(double seconds) {
            now += seconds;
        }
    }

    private record Timed(Sampler.Result result, FakeClock clock) {
    }

    /** Runs the sampler on a fake clock where each reading takes the next latency from the list. */
    private static Timed runWithLatencies(List<Double> latencies, SamplingPlan plan, AmmeterException error) {
        FakeClock clock = new FakeClock();
        Iterator<Double> next = latencies.iterator();
        Sampler.Measurement measure = () -> {
            clock.now += next.next();
            if (error != null) {
                throw error;
            }
            return 1.0;
        };
        return new Timed(new Sampler(clock::time, clock::sleep).run(plan, measure), clock);
    }

    private static Timed runWithLatencies(List<Double> latencies, SamplingPlan plan) {
        return runWithLatencies(latencies, plan, null);
    }

    /** Runs the sampler on a fake clock; each reading returns the next value or throws the next error. */
    private static Sampler.Result runWithOutcomes(List<Object> outcomes, SamplingPlan plan, int maxConsecutiveErrors) {
        FakeClock clock = new FakeClock();
        Iterator<Object> next = outcomes.iterator();
        Sampler.Measurement measure = () -> {
            Object outcome = next.next();
            if (outcome instanceof AmmeterException e) {
                throw e;
            }
            if (outcome instanceof InterruptedException e) {
                throw e;
            }
            if (outcome instanceof RuntimeException e) {
                throw e;
            }
            return (Double) outcome;
        };
        return new Sampler(clock::time, clock::sleep).run(plan, measure, maxConsecutiveErrors);
    }

    private static Sampler.Result runWithOutcomes(List<Object> outcomes, SamplingPlan plan) {
        return runWithOutcomes(outcomes, plan, 0);
    }

    private static List<Double> repeat(double value, int times) {
        return new ArrayList<>(java.util.Collections.nCopies(times, value));
    }

    private static List<Object> repeatOutcome(Object outcome, int times) {
        return new ArrayList<>(java.util.Collections.nCopies(times, outcome));
    }

    private static double[] array(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static void assertClose(List<Double> actual, double... expected) {
        assertEquals(array(actual), expected, 1e-9);
    }

    private static List<Double> times(Sampler.Result result) {
        return result.samples().stream().map(Sample::timeS).collect(Collectors.toList());
    }

    private static List<Double> lags(Sampler.Result result) {
        return result.samples().stream().map(Sample::lagS).collect(Collectors.toList());
    }

    private static List<Double> latencies(Sampler.Result result) {
        return result.samples().stream().map(Sample::latencyS).collect(Collectors.toList());
    }

    private static List<Integer> indices(Sampler.Result result) {
        return result.samples().stream().map(Sample::index).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static double planField(SamplingPlan plan, String key) {
        return ((Number) Json.MAPPER.convertValue(plan, java.util.Map.class).get(key)).doubleValue();
    }

    /** A plan can be given as any two of count, duration and frequency, and the third is worked out. */
    @Test
    public void planFromAnyTwoValues() {
        Object[][] cases = {
            {10, null, 5, 10, 5.0},
            {null, 2, 5, 10, 5.0},
            {10, 2, null, 10, 5.0},
            {10, 2, 5, 10, 5.0},
            {null, 2.3, 10, 23, 10.0}, // 2.3 * 10 is 22.999... in floating point
            {10, 3, null, 10, 10 / 3.0},
        };
        for (Object[] c : cases) {
            SamplingPlan plan = SamplingPlan.fromConfig(c[0], c[1], c[2]);
            assertEquals(plan.count(), (int) (Integer) c[3]);
            assertEquals(plan.frequencyHz(), (double) (Double) c[4], 0.0);
        }

        for (int step = 1; step <= 100; step++) {
            double duration = step * 0.05;
            for (int frequency : new int[] {1, 3, 7, 10, 33, 50}) {
                if (duration * frequency < 1) {
                    continue;
                }
                SamplingPlan plan = SamplingPlan.fromConfig(null, duration, frequency);
                assertTrue(plan.durationS() <= duration + 1e-9, "past the duration " + duration + " at " + frequency);
                assertTrue((plan.count() + 1.0) / frequency > duration + 1e-9,
                        "unused time in " + duration + " at " + frequency);
                assertEquals(planField(plan, "total_duration_seconds"), plan.durationS(), 0.0);
            }
        }
        assertEquals(planField(SamplingPlan.fromConfig(5, 2, null), "sampling_frequency_hz"), 2.5, 0.0);
    }

    @DataProvider
    public Object[][] invalidPlans() {
        return new Object[][] {
            {10, null, null, "at least two"},
            {null, null, null, "at least two"},
            {10, 2, 10, "take 1 s, not 2 s"},
            {10, 1.15, 10, "take 1 s, not 1.15 s"}, // 15% off
            {10, 1.05, 10, "take 1 s, not 1.05 s"},
            {10, 1, true, "positive number"},
            {-1, null, 5, "positive number"},
            {"ten", null, 5, "positive number"},
            {true, null, 5, "positive number"},
            {10, null, true, "positive number"},
            {10, null, 0, "positive number"},
            {2.5, null, 5, "whole number"},
            {null, 0.1, 5, "not even one"},
            {null, Double.POSITIVE_INFINITY, 10, "positive number"},
            {10, null, Double.NaN, "positive number"},
            {Double.POSITIVE_INFINITY, null, 5, "positive number"},
        };
    }

    /** Plans that can't work are rejected with a clear ConfigException. */
    @Test(dataProvider = "invalidPlans")
    public void invalidPlans(Object count, Object duration, Object frequency, String message) {
        ConfigException error = expectThrows(ConfigException.class,
                () -> SamplingPlan.fromConfig(count, duration, frequency));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    /** With a fast ammeter every reading starts exactly when its slot opens. Failed readings add no wait. */
    @Test
    public void readingsFollowTheSchedule() {
        Sampler.Result result = runWithLatencies(repeat(0.01, 5), new SamplingPlan(5, 10)).result();
        assertClose(times(result), 0, 0.1, 0.2, 0.3, 0.4);
        assertClose(lags(result), 0, 0, 0, 0, 0);
        assertClose(latencies(result), 0.01, 0.01, 0.01, 0.01, 0.01);
        assertEquals(result.missed(), 0);

        result = runWithLatencies(repeat(0.05, 4), new SamplingPlan(4, 10), new AmmeterTimeoutException("slow")).result();
        assertClose(latencies(result), 0.05, 0.05, 0.05, 0.05); // failed readings keep their time
        assertClose(times(result), 0, 0.1, 0.2, 0.3); // and cost no extra wait
    }

    /** A reading that runs past the next slot makes the sampler skip that slot and count it as missed. */
    @Test
    public void lateReadingsSkipTheSlotsTheyRanOver() {
        Sampler.Result result = runWithLatencies(repeat(0.17, 4), new SamplingPlan(4, 10)).result();
        assertEquals(indices(result), List.of(0, 1, 3));
        assertClose(times(result), 0, 0.17, 0.34);
        assertClose(lags(result), 0, 0.07, 0.04);
        assertClose(latencies(result), 0.17, 0.17, 0.17);
        assertEquals(result.missed(), 1);

        result = runWithLatencies(List.of(0.01, 0.35, 0.01, 0.01, 0.01), new SamplingPlan(5, 10)).result(); // one long stall
        assertEquals(indices(result), List.of(0, 1, 4));
        assertClose(times(result), 0, 0.1, 0.45);
        assertEquals(result.missed(), 2);

        result = runWithLatencies(List.of(0.1995, 0.01, 0.01, 0.01), new SamplingPlan(4, 10)).result(); // slot still open
        assertEquals(indices(result), List.of(0, 1, 2, 3));
    }

    /** 1 s at 10 Hz against an ammeter that takes 1 s per reading: one reading, nine missed slots, a 1 s run. */
    @Test
    public void durationIsAHardDeadline() {
        Timed run = runWithLatencies(repeat(1.0, 10), SamplingPlan.fromConfig(null, 1, 10));
        assertEquals(run.result().samples().size(), 1);
        assertEquals(run.result().missed(), 9);
        assertEquals(run.clock().now, 1.0, 1e-6);
    }

    @DataProvider
    public Object[][] seeds() {
        return IntStream.range(0, 20).mapToObj(seed -> new Object[] {seed}).toArray(Object[][]::new);
    }

    /** Property test over 20 seeded runs with random response times. */
    @Test(dataProvider = "seeds")
    public void everyReadingStaysInItsOwnSlot(int seed) {
        Random rng = new Random(seed);
        double[] choices = {0.001, 0.05, 0.15, 0.4, 1.3};
        SamplingPlan plan = new SamplingPlan(40, 10);
        List<Double> latencies = IntStream.range(0, 40).mapToObj(i -> choices[rng.nextInt(choices.length)])
                .collect(Collectors.toList());
        Sampler.Result result = runWithLatencies(latencies, plan).result();
        List<Sample> samples = result.samples();

        List<Integer> indices = indices(result);
        assertEquals(indices, new ArrayList<>(new TreeSet<>(indices)));
        assertEquals(samples.size() + result.missed(), plan.count());
        for (Sample s : samples) {
            assertTrue(s.index() / 10.0 - 1e-9 <= s.timeS() && s.timeS() < (s.index() + 1) / 10.0, "slot " + s);
        }
        // a slot is only skipped when the reading before it was still running when the slot ended
        Set<Integer> skipped = new TreeSet<>(IntStream.range(0, plan.count()).boxed().collect(Collectors.toSet()));
        skipped.removeAll(indices);
        for (int slot : skipped) {
            Sample before = null;
            for (Sample s : samples) {
                if (s.index() < slot) {
                    before = s;
                }
            }
            assertTrue(before != null && before.timeS() + before.latencyS() >= (slot + 1) / 10.0 - 1e-9,
                    "slot " + slot + " skipped without cause");
        }
    }

    /** A failed reading is kept with its error. A bug in the code is raised, not recorded as a failed reading. */
    @Test
    public void failedReadingsAreKeptButBugsAreNotHidden() {
        List<Sample> samples = runWithOutcomes(List.of(1.0, new AmmeterTimeoutException("too slow"), 3.0),
                new SamplingPlan(3, 10)).samples();
        assertEquals(samples.stream().map(Sample::value).collect(Collectors.toList()), java.util.Arrays.asList(1.0, null, 3.0));
        assertEquals(samples.get(1).error(), "AmmeterTimeoutException: too slow");

        for (RuntimeException bug : List.of(new IllegalArgumentException("a bug in the client"),
                new UnsupportedOperationException("another bug"))) {
            expectThrows(bug.getClass(), () -> runWithOutcomes(repeatOutcome(bug, 3), new SamplingPlan(3, 10)));
        }
    }

    /** The run stops after exactly N failures in a row, of any ammeter error type. */
    @Test
    public void stopsAfterTooManyFailuresInARow() {
        List<Function<String, AmmeterException>> errors = List.of(AmmeterConnectionException::new,
                AmmeterTimeoutException::new, AmmeterResponseException::new);
        for (int limit = 1; limit <= 4; limit++) {
            for (Function<String, AmmeterException> error : errors) {
                Sampler.Result result = runWithOutcomes(repeatOutcome(error.apply("bad"), 50),
                        new SamplingPlan(50, 10), limit);
                assertEquals(result.samples().size(), limit);
                assertEquals(result.stopReason(), "stopped after " + limit + " failed measurements in a row");
                assertEquals(result.missed(), 0);
            }
        }

        List<Object> alternating = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            alternating.add(new AmmeterTimeoutException("slow"));
            alternating.add(1.0);
        }
        Sampler.Result scattered = runWithOutcomes(alternating, new SamplingPlan(20, 10), 2);
        assertEquals(scattered.samples().size(), 20);
        assertNull(scattered.stopReason());

        List<Object> twoThenOne = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            twoThenOne.add(new AmmeterTimeoutException("slow"));
            twoThenOne.add(new AmmeterTimeoutException("slow"));
            twoThenOne.add(1.0);
        }
        Sampler.Result pairs = runWithOutcomes(twoThenOne, new SamplingPlan(21, 10), 3);
        assertEquals(pairs.samples().size(), 21);
        assertNull(pairs.stopReason());

        Sampler.Result never = runWithOutcomes(repeatOutcome(new AmmeterConnectionException("refused"), 20),
                new SamplingPlan(20, 10), 0);
        assertEquals(never.samples().size(), 20);
        assertNull(never.stopReason());
    }

    /** Ctrl-C (a thread interrupt in Java) ends the run as 'interrupted' and keeps the readings taken so far. */
    @Test
    public void ctrlCKeepsWhatWasCollected() {
        List<Object> outcomes = new ArrayList<>(List.of(1.0, 1.0, new InterruptedException()));
        outcomes.addAll(repeatOutcome(1.0, 7));
        Sampler.Result result = runWithOutcomes(outcomes, new SamplingPlan(10, 10));
        assertEquals(result.samples().size(), 2);
        assertEquals(result.stopReason(), "interrupted");
    }
}
