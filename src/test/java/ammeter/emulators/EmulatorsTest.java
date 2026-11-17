package ammeter.emulators;

import ammeter.AmmeterClient;
import ammeter.AmmeterException;
import ammeter.AmmeterResponseException;
import ammeter.AmmeterSpec;
import ammeter.EmulatorException;
import ammeter.EmulatorProcess;
import ammeter.Run;
import ammeter.Settings;
import ammeter.TestSupport;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** The same contract for every configured ammeter, checked against the real emulators. */
@SuppressWarnings("try") // try-with-resources only scopes stdout capture / emulator processes
public class EmulatorsTest {
    private static final String ENTES_COMMAND = "MEASURE_ENTES -get_data";

    // min, max and mean straight from each emulator's formula and input ranges
    private static final Map<String, double[]> FORMULAS = Map.of(
            "greenlee", new double[] {1 / 100.0, 10 / 0.1, 5.5 * Math.log(100 / 0.1) / 99.9}, // V / R
            "entes", new double[] {0.01 * 500, 0.1 * 2000, 0.055 * 1250}, // B * K
            "circutor", new double[] {0.001 * 10 * 0.1, 0.01 * 10 * 1.0, 0.0055 * 10 * 0.55}); // time step * sum of 10 voltages
    private static final Map<String, double[]> AT_INPUT_BOUNDS = Map.of( // all inputs low / high
            "greenlee", new double[] {10.0, 0.1}, "entes", new double[] {5.0, 200.0}, "circutor", new double[] {0.001, 0.1});

    /** Replaces System.out until closed. */
    private static final class Capture implements AutoCloseable {
        private final PrintStream previous = System.out;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Capture(boolean keep) {
            OutputStream sink = keep ? buffer : OutputStream.nullOutputStream();
            System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            System.setOut(previous);
        }
    }

    private static Thread serverThread(AmmeterEmulator emulator) {
        Thread server = new Thread(emulator::startServer, "emulator-" + emulator.getPort());
        server.setDaemon(true);
        server.setUncaughtExceptionHandler((thread, error) -> { });
        server.start();
        return server;
    }

    private static void assertApprox(double actual, double expected, double rel) {
        assertEquals(actual, expected, Math.abs(expected) * rel);
    }

    @DataProvider(name = "names")
    public Object[][] names() {
        return Settings.load().ammeters().keySet().stream().map(name -> new Object[] {name}).toArray(Object[][]::new);
    }

    @Test(dataProvider = "names")
    public void eachAmmeterAnswersOnlyItsExactCommand(String name) throws Exception {
        // Contract test for each configured ammeter: its full command gives varied readings in range, at full
        // resolution, and a partial command or a trailing newline gets no answer.
        AmmeterSpec spec = TestSupport.ammeters().get(name);
        AmmeterClient client = AmmeterClient.fromSpec(spec);
        List<Double> readings = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            readings.add(client.measure());
        }
        assertTrue(readings.stream().allMatch(r -> spec.expectedMin() <= r && r <= spec.expectedMax()));
        assertTrue(new HashSet<>(readings).size() > 1);
        assertTrue(readings.stream().allMatch(r -> r != Math.rint(r))); // full resolution, not rounded

        byte[] full = spec.command().getBytes(StandardCharsets.UTF_8);
        byte[] withNewline = (spec.command() + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] partial = spec.command().split("\\s+")[0].getBytes(StandardCharsets.UTF_8); // e.g. MEASURE_GREENLEE
        assertNotEquals(full, withNewline);
        for (byte[] wrong : List.of(partial, withNewline)) {
            AmmeterResponseException e = expectThrows(AmmeterResponseException.class,
                    () -> new AmmeterClient(spec.host(), spec.port(), wrong).measure());
            assertTrue(e.getMessage().contains("empty response"), e.getMessage());
        }
    }

    @Test
    public void readmeCircutorCommandIsNotWhatTheEmulatorAccepts() {
        // The CIRCUTOR command in the README gets no answer. This records the documentation bug that the config
        // works around.
        AmmeterSpec spec = TestSupport.ammeters().get("circutor");
        AmmeterClient client = new AmmeterClient(spec.host(), spec.port(),
                "MEASURE_CIRCUTOR -get_measurement".getBytes(StandardCharsets.UTF_8));
        expectThrows(AmmeterResponseException.class, client::measure);
    }

    @Test(dataProvider = "names")
    public void emulatorSurvivesClientsThatResetTheConnection(String name) throws Exception {
        // A client that resets the connection, before or after sending a command, doesn't stop the emulator
        // (fix 14 in the supplied code).
        AmmeterEmulator emulator = Emulators.create(name, TestSupport.freePort());
        double reading;
        Thread server;
        try (Capture ignored = new Capture(false)) {
            server = serverThread(emulator);
            assertTrue(EmulatorProcess.waitForPort("127.0.0.1", emulator.getPort(), 5.0));
            for (boolean sendFirst : new boolean[] {false, true}) {
                Socket conn = new Socket("127.0.0.1", emulator.getPort());
                if (sendFirst) {
                    conn.getOutputStream().write(emulator.getCurrentCommand());
                    conn.getOutputStream().flush();
                }
                TestSupport.reset(conn);
                conn.close();
                Thread.sleep(200);
                assertTrue(server.isAlive());
            }
            reading = new AmmeterClient("127.0.0.1", emulator.getPort(), emulator.getCurrentCommand()).measure();
        }
        assertTrue(Double.isFinite(reading));
        assertTrue(server.isAlive());
    }

    private static class Broken extends EntesAmmeter {
        private final RuntimeException error;

        Broken(int port, RuntimeException error) {
            super(port);
            this.error = error;
        }

        @Override
        public double measureCurrent() {
            throw error;
        }
    }

    @DataProvider(name = "ownErrors")
    public Object[][] ownErrors() {
        // OSError(24, "Too many open files") has no Java twin: an unchecked IOException is the closest error that is not a dropped client
        List<Object[]> cases = new ArrayList<>();
        for (boolean withFaults : new boolean[] {false, true}) {
            cases.add(new Object[] {new IllegalStateException("a bug in measure_current"), withFaults});
            cases.add(new Object[] {new UncheckedIOException(new IOException("Too many open files")), withFaults});
        }
        return cases.toArray(Object[][]::new);
    }

    @Test(dataProvider = "ownErrors")
    public void emulatorStopsOnItsOwnErrors(RuntimeException error, boolean withFaults) throws Exception {
        // A real bug inside an emulator isn't hidden like a dropped client: the emulator stops, also when fault
        // injection is wrapped around it.
        AmmeterEmulator emulator = new Broken(TestSupport.freePort(), error);
        if (withFaults) {
            emulator = new FaultyAmmeter(emulator, Map.of()); // fault injection must not hide it either
        }
        Thread server;
        try (Capture ignored = new Capture(false)) {
            server = serverThread(emulator);
            assertTrue(EmulatorProcess.waitForPort("127.0.0.1", emulator.getPort(), 5.0));
            AmmeterEmulator started = emulator;
            expectThrows(AmmeterException.class,
                    () -> new AmmeterClient("127.0.0.1", started.getPort(), started.getCurrentCommand()).measure());
            server.join(2000);
        }
        assertFalse(server.isAlive()); // real faults must not be hidden like a dropped client
        assertFalse(EmulatorProcess.portIsOpen("127.0.0.1", emulator.getPort()));
    }

    private static List<Double> readings(int port, Long seed, Path tmp, int count, Run.FaultOptions faults) throws Exception {
        AmmeterSpec spec = new AmmeterSpec("entes", port, ENTES_COMMAND, "127.0.0.1", "entes", null, null, null, null, null);
        try (EmulatorProcess ignored = new EmulatorProcess(List.of(spec), tmp.resolve("emulators_" + port + ".log"), faults, seed).start()) {
            AmmeterClient client = AmmeterClient.fromSpec(spec);
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                values.add(client.measure());
            }
            return values;
        }
    }

    private static List<Double> readings(Long seed, Path tmp) throws Exception {
        return readings(TestSupport.freePort(), seed, tmp, 5, null);
    }

    private static List<Double> readings(Long seed, Path tmp, Run.FaultOptions faults) throws Exception {
        return readings(TestSupport.freePort(), seed, tmp, 5, faults);
    }

    private static Map<String, List<Boolean>> faultOutcomes(Path tmp) throws Exception {
        List<AmmeterSpec> specs = List.of(
                new AmmeterSpec("entes", TestSupport.freePort(), ENTES_COMMAND, "127.0.0.1", "entes", null, null, null, null, null),
                new AmmeterSpec("entes_b", TestSupport.freePort(), ENTES_COMMAND, "127.0.0.1", "entes", null, null, null, null, null));
        Map<String, List<Boolean>> result = new LinkedHashMap<>();
        Run.FaultOptions faults = new Run.FaultOptions(Map.of("garbage", 0.5), 3, 3.0);
        try (EmulatorProcess ignored = new EmulatorProcess(specs, tmp.resolve("faults_" + specs.get(0).port() + ".log"), faults, null).start()) {
            for (AmmeterSpec spec : specs) {
                AmmeterClient client = AmmeterClient.fromSpec(spec, 2.0, 0);
                List<Boolean> seen = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    try {
                        client.measure();
                        seen.add(true);
                    } catch (AmmeterResponseException e) {
                        seen.add(false);
                    }
                }
                result.put(spec.name(), seen);
            }
        }
        return result;
    }

    @Test
    public void seedsMakeReadingsAndFaultsRepeatable() throws Exception {
        // The same seed gives the same readings and faults, and another seed gives other readings. The seed is exactly
        // the one given, faults don't change the readings, and each ammeter has its own fault sequence. The configured
        // fault delay reaches the emulator process.
        Path tmp = TestSupport.tempDir();
        List<Double> first = readings(42L, tmp);
        assertEquals(readings(42L, tmp), first);
        assertNotEquals(readings(43L, tmp), first);
        assertNotEquals(readings(44L, tmp), first);
        assertNotEquals(readings(null, tmp), first);
        assertEquals(readings(0L, tmp), readings(0L, tmp));
        try (Capture ignored = new Capture(false)) {
            EntesAmmeter emulator = new EntesAmmeter(0);
            Utils.seed(42);
            List<Double> local = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                local.add(emulator.measureCurrent());
            }
            assertEquals(local, first); // exactly the seed that was given
        } finally {
            Utils.setRandom(new Random());
        }

        Run.FaultOptions faults = new Run.FaultOptions(Map.of("spike", 0.0), 1, 3.0);
        assertEquals(readings(7L, tmp, faults), readings(7L, tmp, faults));
        assertEquals(readings(7L, tmp, faults), readings(7L, tmp)); // faults off
        Map<String, List<Boolean>> outcomes = faultOutcomes(tmp);
        assertNotEquals(outcomes.get("entes"), outcomes.get("entes_b")); // each ammeter has its own fault sequence
        assertEquals(faultOutcomes(tmp), outcomes);
        Run.FaultOptions delayed = new Run.FaultOptions(Map.of("timeout", 1.0), 1, 0.2); // answers after 0.2 s, within the client's 2 s
        assertEquals(readings(TestSupport.freePort(), null, tmp, 1, delayed).size(), 1);
    }

    @Test
    public void failedStartShowsTheEmulatorLog() throws Exception {
        // When an emulator can't start, the error names it and shows the last lines of the emulator log.
        // Stopping closes the log file, and the emulators are gone as soon as stop() returns.
        Path tmp = TestSupport.tempDir();
        AmmeterSpec broken = new AmmeterSpec("broken", TestSupport.freePort(), "MEASURE", "127.0.0.1", "no_such_emulator",
                null, null, null, null, null);
        EmulatorException e = expectThrows(EmulatorException.class,
                () -> new EmulatorProcess(List.of(broken), tmp.resolve("emulators.log")).start());
        assertTrue(Pattern.compile("(?s)did not start: broken.*unknown emulator").matcher(e.getMessage()).find(), e.getMessage());

        AmmeterSpec spec = new AmmeterSpec("entes", TestSupport.freePort(), ENTES_COMMAND, "127.0.0.1", "entes",
                null, null, null, null, null);
        try (EmulatorProcess ignored = new EmulatorProcess(List.of(spec), tmp.resolve("stop.log")).start()) {
            assertTrue(EmulatorProcess.portIsOpen(spec.host(), spec.port()));
        }
        assertFalse(EmulatorProcess.portIsOpen(spec.host(), spec.port()));
    }

    @DataProvider(name = "physics")
    public Object[][] physics() {
        return new Object[][] {
            {"greenlee", 4.96, 0.25}, // long tail, so its mean and CV settle slowly
            {"entes", 0.608, 0.03},
            {"circutor", 0.500, 0.03},
        };
    }

    private static Random constant(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test(dataProvider = "physics")
    public void expectedValuesInConfigMatchTheEmulatorPhysics(String name, double expectedCv, double tolerance) {
        // The expected values in the config match the emulators: min, max and mean exactly, from the formulas (also with
        // every input at its bounds), and CV, geometric mean and log spread from 20,000 readings of measureCurrent().
        AmmeterSpec spec = Settings.load().ammeter(name);
        AmmeterEmulator emulator = Emulators.create(name, 0);
        int n = 20000;
        double[] values = new double[n];
        try (Capture ignored = new Capture(false)) {
            Utils.seed(1);
            for (int i = 0; i < n; i++) {
                values[i] = emulator.measureCurrent();
            }

            double[] logs = new double[n];
            for (int i = 0; i < n; i++) {
                logs[i] = Math.log(values[i]);
            }
            double[] formula = FORMULAS.get(name);
            assertApprox(spec.expectedMin(), formula[0], 1e-6);
            assertApprox(spec.expectedMax(), formula[1], 1e-6);
            assertApprox(spec.expectedMean(), formula[2], 1e-3);

            double min = values[0];
            double max = values[0];
            for (double v : values) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            assertTrue(spec.expectedMin() <= min && max <= spec.expectedMax());
            double mean = mean(values);
            assertApprox(mean, spec.expectedMean(), tolerance / 2);
            assertApprox(stdev(values) / mean, expectedCv, tolerance);
            assertApprox(Math.exp(mean(logs)), spec.expectedGeoMean(), 0.03);
            assertApprox(stdev(logs), spec.expectedLogStd(), 0.03);

            double[] bounds = AT_INPUT_BOUNDS.get(name);
            Utils.setRandom(constant(0.0));
            assertApprox(emulator.measureCurrent(), bounds[0], 1e-6);
            Utils.setRandom(constant(1.0));
            assertApprox(emulator.measureCurrent(), bounds[1], 1e-6);
        } finally {
            Utils.setRandom(new Random());
        }
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double stdev(double[] values) {
        double mean = mean(values);
        double squares = 0;
        for (double v : values) {
            squares += (v - mean) * (v - mean);
        }
        return Math.sqrt(squares / (values.length - 1));
    }
}
