package ammeter.emulators;

import ammeter.Fmt;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Wraps a working emulator and makes some answers go wrong, to check that the framework catches them.
 *
 * <pre>
 * timeout      answers only after the client has given up
 * no_response  sends nothing back
 * garbage      sends text that is not a number
 * spike        sends a reading a million times too big
 * stuck        repeats the same reading for the next few requests, like a frozen sensor
 * </pre>
 */
public class FaultyAmmeter extends AmmeterEmulator {
    public static final int STUCK_READINGS = 5;

    private final AmmeterEmulator inner;
    private final Map<String, Double> rates;
    private final double delayS;
    private final Random rng;
    private double stuckValue;
    private int stuckLeft;

    /** @param seed null for a random sequence, a number, or a text such as {@code "7:entes"} */
    public FaultyAmmeter(AmmeterEmulator inner, Map<String, ? extends Number> rates, Object seed, double delayS) {
        super(inner.getPort());
        this.inner = inner;
        this.rates = new LinkedHashMap<>();
        rates.forEach((fault, rate) -> this.rates.put(fault, rate.doubleValue()));
        this.delayS = delayS;
        this.rng = seed == null ? new Random() : new Random(seedValue(seed));
    }

    public FaultyAmmeter(AmmeterEmulator inner, Map<String, ? extends Number> rates) {
        this(inner, rates, null, 3.0);
    }

    @Override
    public byte[] getCurrentCommand() {
        return inner.getCurrentCommand();
    }

    @Override
    public double measureCurrent() {
        return inner.measureCurrent();
    }

    public String pickFault() {
        double roll = rng.nextDouble();
        for (Map.Entry<String, Double> entry : rates.entrySet()) {
            if (roll < entry.getValue()) {
                return entry.getKey();
            }
            roll -= entry.getValue();
        }
        return null;
    }

    @Override
    protected String reply() {
        if (stuckLeft > 0) {
            stuckLeft--;
            return Fmt.repr(stuckValue);
        }
        double value = inner.measureCurrent();
        String fault = pickFault();
        if (fault != null) {
            System.out.println("FAULT INJECTED: " + fault);
        }
        if ("timeout".equals(fault)) {
            sleep(delayS);
        } else if ("no_response".equals(fault)) {
            return ""; // nothing goes out
        } else if ("garbage".equals(fault)) {
            return "ERR#42";
        } else if ("spike".equals(fault)) {
            return Fmt.repr(value * 1e6);
        } else if ("stuck".equals(fault)) {
            stuckValue = value;
            stuckLeft = STUCK_READINGS - 1;
        }
        return Fmt.repr(value);
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A number is used as it is; a text is hashed, so the same text always gives the same faults. */
    static long seedValue(Object seed) {
        if (seed instanceof Number) {
            return ((Number) seed).longValue();
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.toString().getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
