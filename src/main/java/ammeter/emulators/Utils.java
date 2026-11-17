package ammeter.emulators;

import java.util.Random;

/** The random input source the emulators share, like Python's module-level {@code random}. */
public final class Utils {
    private static volatile Random random = new Random();

    private Utils() {
    }

    /** Generate a random float between minValue and maxValue. */
    public static double generateRandomFloat(double minValue, double maxValue) {
        return minValue + (maxValue - minValue) * random.nextDouble();
    }

    /** Makes the following readings repeatable. */
    public static void seed(long seed) {
        random = new Random(seed);
    }

    /** Replaces the generator, e.g. with one that always returns 0 to check the formulas at their bounds. */
    public static void setRandom(Random generator) {
        random = generator;
    }
}
