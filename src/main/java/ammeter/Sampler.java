package ammeter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Reading i belongs to the time slot [start + i/f, start + (i+1)/f).
 *
 * <p>A reading starts when its slot begins, or right after the previous reading if that one ran late. When a slot has
 * already passed, it is skipped and counted as missed: there are no catch-up bursts, and no reading starts after the
 * planned duration. A reading that is in progress at the end may still finish, within the client's timeout.
 */
public class Sampler {
    /** Takes one reading. */
    @FunctionalInterface
    public interface Measurement {
        double measure() throws AmmeterException, InterruptedException;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(double seconds) throws InterruptedException;
    }

    /** What a run produced. {@code stopReason} is null when every slot was tried. */
    public record Result(List<Sample> samples, int missed, String stopReason) {
    }

    private final DoubleSupplier clock;
    private final Sleeper sleeper;

    public Sampler(DoubleSupplier clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public Sampler() {
        this(() -> System.nanoTime() / 1e9, Sampler::sleep);
    }

    /**
     * @param maxConsecutiveErrors stop after this many failed readings in a row; 0 never stops.
     *                             A thread interrupt (Ctrl-C) ends the run with stop reason "interrupted".
     */
    public Result run(SamplingPlan plan, Measurement measure, int maxConsecutiveErrors) {
        List<Sample> samples = new ArrayList<>();
        int missed = 0;
        String stopReason = null;
        int consecutiveErrors = 0;
        double start = clock.getAsDouble();
        try {
            for (int i = 0; i < plan.count(); i++) {
                double slotStart = start + i / plan.frequencyHz();
                double slotEnd = start + (i + 1) / plan.frequencyHz();
                double now = clock.getAsDouble();
                if (now < slotStart) {
                    sleeper.sleep(slotStart - now);
                    now = clock.getAsDouble();
                }
                if (now >= slotEnd) {
                    missed++;
                    continue;
                }

                Double value = null;
                String error = null;
                try {
                    value = measure.measure();
                    consecutiveErrors = 0;
                } catch (AmmeterException e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    consecutiveErrors++;
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                samples.add(new Sample(i, now - start, Math.max(0.0, now - slotStart), clock.getAsDouble() - now, value, error));

                if (maxConsecutiveErrors > 0 && consecutiveErrors >= maxConsecutiveErrors) {
                    stopReason = "stopped after " + consecutiveErrors + " failed measurements in a row";
                    break;
                }
            }
        } catch (InterruptedException e) {
            stopReason = "interrupted";
        }
        return new Result(samples, missed, stopReason);
    }

    public Result run(SamplingPlan plan, Measurement measure) {
        return run(plan, measure, 0);
    }

    private static void sleep(double seconds) throws InterruptedException {
        long nanos = (long) (seconds * 1e9);
        Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
    }
}
