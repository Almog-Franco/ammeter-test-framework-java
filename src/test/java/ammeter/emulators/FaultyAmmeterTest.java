package ammeter.emulators;

import ammeter.AmmeterClient;
import ammeter.AmmeterResponseException;
import ammeter.AmmeterTimeoutException;
import ammeter.EmulatorProcess;
import ammeter.TestSupport;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class FaultyAmmeterTest {
    private static AmmeterClient client(AmmeterEmulator emulator, double timeoutS) {
        return new AmmeterClient("127.0.0.1", emulator.getPort(), emulator.getCurrentCommand(), timeoutS, 0);
    }

    private static AmmeterClient start(AmmeterEmulator emulator) throws InterruptedException {
        Thread server = new Thread(emulator::startServer, "emulator-" + emulator.getPort());
        server.setDaemon(true);
        server.start();
        assertTrue(EmulatorProcess.waitForPort("127.0.0.1", emulator.getPort(), 5.0));
        return client(emulator, 0.2);
    }

    @DataProvider(name = "faults")
    public Object[][] faults() {
        return new Object[][] {
            {"timeout", AmmeterTimeoutException.class, "no complete answer"},
            {"no_response", AmmeterResponseException.class, "empty response"},
            {"garbage", AmmeterResponseException.class, "not a number"},
        };
    }

    @Test(dataProvider = "faults")
    public void eachFaultIsCaught(String fault, Class<? extends Exception> error, String message) throws Exception {
        // Each fault that breaks the reply (timeout, no response, garbage) reaches the client as the right error.
        AmmeterClient client = start(new FaultyAmmeter(new EntesAmmeter(TestSupport.freePort()), Map.of(fault, 1.0), null, 0.5));
        Exception e = expectThrows(error, client::measure);
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    public void spikeAndStuckFaultsGiveBadReadings() throws Exception {
        // A spike is far outside the expected range, wide enough for the charts to switch to a log scale, and a stuck
        // fault repeats the same reading 5 times before the next real one.
        double spike = start(new FaultyAmmeter(new EntesAmmeter(TestSupport.freePort()), Map.of("spike", 1.0))).measure();
        assertTrue(spike > 200 * 1000);
        assertTrue(spansDecades(List.of(60.0, spike))); // so the charts switch to a log scale

        AmmeterClient client = start(new FaultyAmmeter(new EntesAmmeter(TestSupport.freePort()), Map.of("stuck", 1.0)));
        List<Double> readings = new ArrayList<>();
        for (int i = 0; i < FaultyAmmeter.STUCK_READINGS + 1; i++) {
            readings.add(client.measure());
        }
        assertEquals(new HashSet<>(readings.subList(0, FaultyAmmeter.STUCK_READINGS)).size(), 1);
        assertNotEquals(readings.get(FaultyAmmeter.STUCK_READINGS), readings.get(0));
    }

    @Test
    public void faultsAreLoggedDelayedAndThenOver() throws Exception {
        // Every fault is logged by name, and a timeout fault lasts the configured delay and then sends the real reading.
        // The emulator works normally once the fault is over.
        PrintStream previous = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            new FaultyAmmeter(new EntesAmmeter(0), Map.of("spike", 1.0)).reply(); // the fault is applied when the reply is built
            assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("FAULT INJECTED: spike"));
            buffer.reset();

            FaultyAmmeter slow = new FaultyAmmeter(new EntesAmmeter(TestSupport.freePort()), Map.of("timeout", 1.0), null, 1.6);
            start(slow);
            expectThrows(AmmeterTimeoutException.class, client(slow, 1.3)::measure); // the delay is the configured one, not a fixed one
            assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("FAULT INJECTED: timeout"));

            FaultyAmmeter emulator = new FaultyAmmeter(new EntesAmmeter(TestSupport.freePort()), Map.of("timeout", 1.0), null, 0.3);
            AmmeterClient client = start(emulator);
            expectThrows(AmmeterTimeoutException.class, client::measure);
            AmmeterClient patient = client(emulator, 2.0);
            double reading = patient.measure();
            assertTrue(5 <= reading && reading <= 200); // a client that waits long enough gets the real reading
            ratesOf(emulator).clear();
            reading = patient.measure();
            assertTrue(5 <= reading && reading <= 200); // the emulator keeps working after a fault
        } finally {
            System.setOut(previous);
        }
    }

    private static List<String> picks(Object seed, int n) {
        FaultyAmmeter emulator = new FaultyAmmeter(new EntesAmmeter(0), Map.of("timeout", 0.1, "garbage", 0.2), seed, 3.0);
        List<String> picks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            picks.add(emulator.pickFault());
        }
        return picks;
    }

    @Test
    public void faultRatesAndSeeds() {
        // Faults happen at the configured rates, the same seed gives the same sequence, and other seeds give
        // other sequences.
        List<String> picks = picks(3, 10000);
        assertApprox(picks.stream().filter("timeout"::equals).count(), 1000, 0.1);
        assertApprox(picks.stream().filter("garbage"::equals).count(), 2000, 0.1);
        assertApprox(picks.stream().filter(p -> p == null).count(), 7000, 0.05);

        assertEquals(picks(3, 50), picks(3, 50));
        assertNotEquals(picks(3, 50), picks(4, 50));
        assertNotEquals(picks(3, 50), picks(5, 50));
        assertEquals(picks("7:entes", 50), picks("7:entes", 50));
        assertNotEquals(picks("7:entes", 50), picks("7:greenlee", 50));
    }

    private static void assertApprox(double actual, double expected, double rel) {
        assertEquals(actual, expected, expected * rel);
    }

    // the charts' log-scale test lives in a package-private method of ammeter.Plots
    private static boolean spansDecades(List<Double> values) throws Exception {
        Method method = Class.forName("ammeter.Plots").getDeclaredMethod("spansDecades", List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, values);
    }

    // the Python test assigns emulator.rates = {}; Java keeps the rates private, so empty the map in place
    @SuppressWarnings("unchecked")
    private static Map<String, Double> ratesOf(FaultyAmmeter emulator) throws Exception {
        Field field = FaultyAmmeter.class.getDeclaredField("rates");
        field.setAccessible(true);
        return (Map<String, Double>) field.get(emulator);
    }
}
