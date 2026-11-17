package ammeter;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The whole framework against real emulators, a real command line and real child processes. */
public class EndToEndTest {
    private static final Set<String> ALL_CHECKS =
            Set.of("values_in_range", "error_rate", "sampling_rate", "not_stuck", "matches_model", "no_drift");
    // On a busy machine a 20 ms slot is sometimes missed; the sampling rules have their own exact tests
    private static final double LOADED_MACHINE = 0.5;

    private Path tmp;

    @BeforeMethod
    public void setUp() {
        tmp = TestSupport.tempDir();
    }

    private static Map<String, Object> sampling(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> fast() {
        return sampling("measurements_count", 30, "sampling_frequency_hz", 50);
    }

    private static Map<String, Object> seeded() {
        return sampling("measurements_count", 10, "sampling_frequency_hz", 20);
    }

    /** A copy of the same readings, or one that ended a slot or two early on a busy machine. */
    private static boolean sameReadings(List<Double> a, List<Double> b) {
        int n = Math.min(a.size(), b.size());
        return n >= Math.max(a.size(), b.size()) - 2 && a.subList(0, n).equals(b.subList(0, n));
    }

    /** Config that points at the shared emulators. */
    private Map<String, Object> runningConfig() {
        Map<String, Object> raw = TestSupport.config(tmp);
        TestSupport.ammeters().forEach((name, spec) -> TestSupport.section(raw, "ammeters", name).put("port", spec.port()));
        TestSupport.section(raw, "testing").put("sampling", fast());
        TestSupport.section(raw, "testing", "acceptance").put("max_frequency_error", LOADED_MACHINE);
        return raw;
    }

    /** Config on fresh ports, so the framework starts (and can configure) its own emulators. */
    private Map<String, Object> ownEmulatorsConfig() {
        Map<String, Object> raw = TestSupport.config(tmp);
        freshPorts(raw);
        TestSupport.section(raw, "testing").put("sampling", fast());
        TestSupport.section(raw, "testing", "acceptance").put("max_frequency_error", LOADED_MACHINE);
        TestSupport.section(raw, "analysis", "visualization").put("enabled", false);
        return raw;
    }

    private static void freshPorts(Map<String, Object> raw) {
        for (String name : TestSupport.section(raw, "ammeters").keySet()) {
            TestSupport.section(raw, "ammeters", name).put("port", TestSupport.freePort());
        }
    }

    private static Map<String, Object> ammeter(Map<String, Object> raw, String name) {
        return TestSupport.section(raw, "ammeters", name);
    }

    private static void disablePlots(Map<String, Object> raw) {
        TestSupport.section(raw, "analysis", "visualization").put("enabled", false);
    }

    private Path write(Map<String, Object> raw) {
        return TestSupport.writeConfig(tmp, raw);
    }

    private static Map<String, Boolean> checks(Run run) {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        run.checks().forEach(c -> checks.put(c.name(), c.passed()));
        return checks;
    }

    private static List<Double> values(List<Sample> samples) {
        return samples.stream().map(Sample::value).collect(Collectors.toList());
    }

    private record CliResult(int code, String out, String err) {
    }

    private static CliResult cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = Cli.run(args, outStream, errStream);
        }
        return new CliResult(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Jvm(int exit, String out, String err) {
    }

    /** Runs a main class in a child JVM on this JVM's class path. */
    private Jvm runJvm(Charset console, List<String> jvmArgs, int timeoutS, String mainClass, String... args)
            throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dammeter.home=" + Settings.PROJECT_ROOT));
        command.addAll(jvmArgs);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), mainClass));
        command.addAll(List.of(args));
        Path out = Files.createTempFile(tmp, "stdout", ".txt");
        Path err = Files.createTempFile(tmp, "stderr", ".txt");
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("child JVM did not finish within " + timeoutS + " s: " + mainClass);
        }
        return new Jvm(process.exitValue(), new String(Files.readAllBytes(out), console),
                new String(Files.readAllBytes(err), console));
    }

    /** Creates a framework, runs one test and exits without closing it. */
    public static final class ExitWithoutClose {
        public static void main(String[] args) {
            AmmeterTestFramework framework = new AmmeterTestFramework(Path.of(args[0]));
            framework.runTest("entes");
            ProcessHandle.current().children().forEach(child -> System.out.println("PID " + child.pid()));
        }
    }

    @Test
    public void runTestReturnsAndArchivesACompleteResult() throws IOException {
        Map<String, Object> raw = runningConfig();
        Path path = write(raw);
        try (AmmeterTestFramework framework = new AmmeterTestFramework(path)) {
            Run result = framework.runTest("entes");
            Path folder = framework.store().runDir(result.runId());

            assertEquals(result.status(), Status.PASS);
            assertEquals(result.statistics().count(), 30);
            assertEquals(result.missed(), 0);
            for (String key : List.of("mean", "median", "std_dev", "min", "max")) {
                assertNotNull(result.statistics().get(key), key);
            }
            assertEquals(result.checks().stream().map(Check::name).collect(Collectors.toSet()), ALL_CHECKS);
            assertFalse(result.checks().stream().anyMatch(Check::skipped));
            for (String name : List.of("run.json", "samples.csv", "report.md", "plots/timeseries.png",
                    "plots/histogram.png")) {
                assertTrue(Files.exists(folder.resolve(name)), name);
            }
            assertEquals(framework.store().load(result.runId()), result);
            List<ResultStore.IndexEntry> entes = framework.store().listRuns("entes", null);
            assertEquals(entes.get(entes.size() - 1).runId(), result.runId());
        }

        try (AmmeterTestFramework framework = new AmmeterTestFramework(path, new Settings.Overrides().profile("smoke"))) {
            Run stored = framework.store().load(framework.runTest("circutor").runId());
            // the profile, not the file's sampling
            assertEquals(((Number) TestSupport.section(stored.settings(), "sampling").get("measurements_count")).intValue(), 10);
            assertEquals(((Number) stored.settings().get("timeout_s")).doubleValue(),
                    ((Number) TestSupport.section(raw, "testing").get("timeout_seconds")).doubleValue());
            assertEquals(((Number) TestSupport.section(stored.settings(), "acceptance").get("stuck_after")).intValue(), 3);
            Object onDisk = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(path));
            assertEquals(stored.config().content(), onDisk);
            assertEquals(stored.config().profile(), "smoke");
        }
    }

    @Test
    public void runAllComparesEveryAmmeter() {
        Map<String, Object> raw = runningConfig();
        disablePlots(raw);
        Comparison comparison;
        List<Run> runs;
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            AmmeterTestFramework.Result result = framework.runAll();
            comparison = result.comparison();
            runs = result.runs();
        }
        assertEquals(runs.stream().map(run -> run.ammeter().name()).toList(), List.of("greenlee", "entes", "circutor"));
        assertTrue(runs.stream().allMatch(run -> run.status() == Status.PASS));
        assertEquals(comparison.rows().size(), 3);
        List<Double> cvs = comparison.rows().stream().map(Comparison.Row::cv).toList();
        assertEquals(cvs, cvs.stream().sorted().toList()); // most precise first
        assertTrue(comparison.mostReliable() == null || Set.of("entes", "circutor").contains(comparison.mostReliable()));
        assertTrue(comparison.mostReliable() != null || comparison.conclusion().contains("insufficient evidence"));

        TestSupport.section(raw, "analysis").put("min_readings_to_rank", 31);
        TestSupport.section(raw, "analysis", "visualization").put("enabled", true);
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            AmmeterTestFramework.Result result = framework.runAll(List.of("entes", "circutor"));
            comparison = result.comparison();
            Path root = framework.store().root().toAbsolutePath().normalize();
            assertEquals(comparison.rows(), List.of());
            assertEquals(comparison.notRanked().stream().map(Comparison.Row::reason).toList(),
                    List.of("only 30 readings, 31 needed", "only 30 readings, 31 needed"));
            assertTrue(Files.exists(Path.of(comparison.folder()).resolve("comparison.json")));
            assertTrue(Files.exists(Path.of(comparison.folder()).resolve("comparison.png")));
            assertTrue(result.runs().stream()
                    .allMatch(run -> Path.of(run.logFile()).toAbsolutePath().normalize().startsWith(root)));
        }

        List<String> calls = new ArrayList<>();
        disablePlots(raw);
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw)) {
            @Override
            public Run runTest(String name) {
                calls.add(name);
                Status status = switch (name) {
                    case "greenlee" -> Status.FAIL;
                    case "entes" -> Status.INTERRUPTED;
                    default -> Status.PASS;
                };
                return new Run(name, status, new AmmeterSpec(name, 1, "x"), null, null, null, null, 0,
                        Statistics.empty(), 0, 0.0, Map.of(), 0.0, null, List.of(), null, Map.of(), null, Map.of(), null);
            }
        }) {
            AmmeterTestFramework.Result result = framework.runAll();
            assertEquals(calls, List.of("greenlee", "entes")); // goes on after a FAIL, stops on Ctrl-C
            assertEquals(result.comparison().notRanked().stream().map(Comparison.Row::reason).toList(),
                    List.of("status FAIL", "status INTERRUPTED"));
        }
    }

    @Test
    @SuppressWarnings("try")
    public void unknownNamesFailBeforeAnythingRuns() throws Exception {
        Map<String, Object> raw = ownEmulatorsConfig();
        try (AutoCloseable windows = TestSupport.windowsLoopback()) {
            AmmeterTestFramework framework = new AmmeterTestFramework(write(raw));
            try (framework) {
                ConfigException single = expectThrows(ConfigException.class, () -> framework.runTest("fluke"));
                assertTrue(single.getMessage().contains("unknown ammeter 'fluke'"), single.getMessage());
                ConfigException all = expectThrows(ConfigException.class, () -> framework.runAll(List.of("entes", "fluke")));
                assertTrue(all.getMessage().contains("unknown ammeter 'fluke'"), all.getMessage());
                assertEquals(framework.store().listRuns(), List.of());
                for (String name : TestSupport.section(raw, "ammeters").keySet()) {
                    assertFalse(EmulatorProcess.portIsOpen("127.0.0.1", (int) ammeter(raw, name).get("port")));
                }
            }
            framework.runTest("entes");
            framework.close();
            try (framework) { // a closed framework starts its emulators again when it's used
                assertEquals(framework.runTest("entes").errorCount(), 0);
            }
        }

        // a program that exits without close(): its emulators stop with it
        Jvm child = runJvm(StandardCharsets.UTF_8, List.of(), 60, ExitWithoutClose.class.getName(),
                write(raw).toString());
        List<Long> pids = child.out().lines().filter(line -> line.startsWith("PID "))
                .map(line -> Long.parseLong(line.split(" ")[1])).toList();
        assertFalse(pids.isEmpty(), child.out() + child.err());
        int entesPort = (int) ammeter(raw, "entes").get("port");
        try {
            assertFalse(EmulatorProcess.portIsOpen("127.0.0.1", entesPort)); // the emulators stopped when the program ended
        } finally {
            if (EmulatorProcess.portIsOpen("127.0.0.1", entesPort)) {
                pids.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
            }
        }
    }

    @Test
    public void injectedFaultsAreDetected() throws IOException {
        Map<String, Object> raw = ownEmulatorsConfig();
        TestSupport.section(raw, "testing").put("sampling", sampling("measurements_count", 40, "sampling_frequency_hz", 50));
        raw.put("fault_injection", sampling("enabled", true, "seed", 1, "rates", sampling("garbage", 0.2, "spike", 0.2)));
        Run result;
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            result = framework.runTest("circutor");
        }
        Map<String, Boolean> checks = checks(result);
        assertEquals(result.status(), Status.FAIL);
        assertTrue(result.errors().get("AmmeterResponseException") > 0);
        assertFalse(checks.get("values_in_range"));
        assertFalse(checks.get("error_rate"));
        double timeout = ((Number) TestSupport.section(raw, "testing").get("timeout_seconds")).doubleValue();
        assertEquals(result.faultInjection().delayS(), timeout + 1, 1e-9);
        Path emulatorLog = Path.of((String) TestSupport.section(raw, "result_management").get("results_dir"))
                .resolve("logs").resolve("emulators.log");
        assertTrue(Files.readString(emulatorLog, StandardCharsets.UTF_8).contains("FAULT INJECTED"));

        TestSupport.section(raw, "testing").put("sampling", fast());
        TestSupport.section(raw, "fault_injection").put("rates", sampling("stuck", 0.3));
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            result = framework.runTest("entes");
        }
        checks = checks(result);
        assertEquals(result.errorCount(), 0);
        assertFalse(checks.get("not_stuck"));
        assertEquals(result.status(), Status.FAIL);

        TestSupport.section(raw, "testing").put("timeout_seconds", 0.3);
        TestSupport.section(raw, "testing").put("sampling", sampling("measurements_count", 3, "sampling_frequency_hz", 10));
        TestSupport.section(raw, "fault_injection").put("rates", sampling("timeout", 1.0));
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            assertEquals(framework.runTest("entes").errors(), Map.of("AmmeterTimeoutException", 1));
        }

        TestSupport.section(raw, "testing").put("sampling", sampling("measurements_count", 20, "sampling_frequency_hz", 50));
        TestSupport.section(raw, "fault_injection").put("rates", sampling("spike", 0.5));
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            double outOfRange = framework.runTest("entes").outOfRangeRate();
            assertTrue(0 < outOfRange && outOfRange <= 1, "out_of_range_rate " + outOfRange);
        }
    }

    @Test
    public void slowAmmeterCannotStretchTheRun() throws Exception {
        try (TestSupport.TcpServer server = TestSupport.TcpServer.start(
                TestSupport.answer("60.0".getBytes(StandardCharsets.UTF_8), 0.25))) {
            Map<String, Object> raw = TestSupport.config(tmp);
            ammeter(raw, "entes").put("port", server.port());
            ammeter(raw, "entes").put("emulator", null);
            TestSupport.section(raw, "testing").put("start_emulators", false);
            TestSupport.section(raw, "testing").put("sampling",
                    sampling("total_duration_seconds", 1, "sampling_frequency_hz", 10));
            disablePlots(raw);
            Run result;
            double elapsed;
            try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
                long start = System.nanoTime();
                result = framework.runTest("entes");
                elapsed = (System.nanoTime() - start) / 1e9;
            }
            assertTrue(elapsed < 1 + 0.25 + 0.5, "took " + elapsed + " s"); // duration + the last reading + bookkeeping
            assertTrue(result.missed() >= 5, "missed " + result.missed());
            assertEquals(result.statistics().count() + result.missed(), 10);
            assertFalse(checks(result).get("sampling_rate"));
        }
    }

    private record Readings(List<Double> values, Run run) {
    }

    private Readings readings(Map<String, Object> raw, long seed) {
        TestSupport.section(raw, "testing").put("emulator_seed", (int) seed);
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            Run run = framework.runTest("entes");
            return new Readings(values(framework.store().loadSamples(run.runId())), run);
        }
    }

    @Test
    public void seededRunsRepeatExactly() throws Exception {
        Map<String, Object> raw = ownEmulatorsConfig();
        TestSupport.section(raw, "testing").put("sampling", seeded()); // a missed slot would shorten one run

        Readings first = readings(raw, 5);
        assertEquals(((Number) first.run().settings().get("emulator_seed")).longValue(), 5L);
        assertTrue(sameReadings(readings(raw, 5).values(), first.values()));
        assertFalse(readings(raw, 6).values().equals(first.values()));

        AtomicInteger probes = new AtomicInteger();
        TestSupport.Handler counted = conn -> {
            if (conn.getInputStream().read(new byte[1024]) > 0) { // a request, not just a check that the port is open
                probes.incrementAndGet();
                conn.getOutputStream().write("1.5".getBytes(StandardCharsets.UTF_8));
            }
        };
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(AmmeterTestFramework.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        ammeter(raw, "circutor").put("port", TestSupport.ammeters().get("circutor").port()); // already running
        try (TestSupport.TcpServer server = TestSupport.TcpServer.start(counted)) {
            ammeter(raw, "greenlee").put("port", server.port()); // answers like a running emulator
            logger.addHandler(handler);
            try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
                framework.runTest("circutor");
            } finally {
                logger.removeHandler(handler);
            }
        }
        assertTrue(String.join("\n", warnings).contains("circutor is already running, so its faults and seed can't be set"),
                warnings.toString());
        assertEquals(probes.get(), 1); // checking a running emulator takes a single reading
    }

    @Test
    public void unreachableAmmeterEndsInError() throws Exception {
        Map<String, Object> raw = TestSupport.config(tmp);
        TestSupport.section(raw, "testing").put("start_emulators", false);
        TestSupport.section(raw, "testing").put("sampling", fast());
        Run result;
        try (TestSupport.TcpServer server = TestSupport.TcpServer.start(TestSupport::reset)) {
            ammeter(raw, "entes").put("port", server.port()); // refuses at once on every platform
            ammeter(raw, "entes").put("emulator", null);
            try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
                result = framework.runTest("entes");
            }
        }
        assertEquals(result.status(), Status.ERROR);
        assertTrue(result.stopReason().contains("in a row"), result.stopReason());
        assertEquals(result.errors(), Map.of("AmmeterConnectionException", 5));

        AtomicInteger connections = new AtomicInteger();
        List<Sample> samples;
        String reportText;
        try (TestSupport.TcpServer server = TestSupport.TcpServer.start(conn -> {
            connections.incrementAndGet();
            TestSupport.reset(conn);
        })) {
            ammeter(raw, "entes").put("port", server.port());
            TestSupport.section(raw, "testing").put("retries", 0);
            TestSupport.section(raw, "testing").put("max_consecutive_errors", 2);
            TestSupport.section(raw, "testing").put("sampling", seeded());
            disablePlots(raw);
            try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
                result = framework.runTest("entes");
                samples = framework.store().loadSamples(result.runId());
                reportText = Files.readString(framework.store().runDir(result.runId()).resolve("report.md"),
                        StandardCharsets.UTF_8);
            }
        }
        assertEquals(connections.get(), 2); // retries: 0, and the run stops after max_consecutive_errors: 2
        assertEquals(result.errors(), Map.of("AmmeterConnectionException", 2));
        assertEquals(result.errorRate(), 1.0);
        assertEquals(samples.stream().map(s -> s.error() != null).toList(), List.of(true, true));
        assertTrue(Files.readString(Path.of(result.logFile()), StandardCharsets.UTF_8).contains("reading 1 failed"));
        assertTrue(reportText.contains("## entes: ERROR"));
        assertTrue(result.startedAt().compareTo(result.finishedAt()) < 0);
        assertEquals(result.environment().get("java"), System.getProperty("java.version"));

        // a bug in the code (not an ammeter error) is raised, not hidden as an ERROR run
        Sockets.setConnector((host, port, timeoutMs) -> {
            throw new ArithmeticException("/ by zero");
        });
        try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
            expectThrows(ArithmeticException.class, () -> framework.runTest("entes"));
        } finally {
            Sockets.setConnector(Sockets.DIRECT);
        }
    }

    @Test
    @SuppressWarnings("try")
    public void portTakenByAnotherProgram() throws Exception {
        Map<String, Object> raw = TestSupport.config(tmp);
        List<Socket> held = new CopyOnWriteArrayList<>();
        ServerSocket other = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        ServerSocket silent = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        int port = other.getLocalPort();
        Thread webServer = new Thread(() -> {
            while (true) {
                try (Socket conn = other.accept()) {
                    conn.getInputStream().read(new byte[1024]);
                    conn.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                    if (other.isClosed()) {
                        return;
                    }
                }
            }
        }, "web-server");
        Thread mute = new Thread(() -> {
            while (true) {
                try {
                    held.add(silent.accept());
                } catch (IOException e) {
                    return;
                }
            }
        }, "mute-server");
        webServer.setDaemon(true);
        mute.setDaemon(true);
        webServer.start();
        mute.start();
        try (AutoCloseable windows = TestSupport.windowsLoopback()) {
            ammeter(raw, "greenlee").put("port", port);
            EmulatorException error = null;
            try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
                for (int i = 0; i < 2; i++) { // checked again on the next run, not only once
                    error = expectThrows(EmulatorException.class, () -> framework.runTest("greenlee"));
                    assertTrue(error.getMessage().contains("port " + port + " for greenlee is used by another"),
                            error.getMessage());
                }
            }
            assertFalse(error.getMessage().contains("AirPlay")); // that hint is only for port 5000 on macOS

            ammeter(raw, "greenlee").put("port", TestSupport.freePort());
            ammeter(raw, "entes").put("port", TestSupport.freePort());
            ammeter(raw, "circutor").put("port", silent.getLocalPort());
            long start = System.nanoTime();
            try (AmmeterTestFramework framework = new AmmeterTestFramework(write(raw))) {
                // every port is checked, not only the one being tested
                EmulatorException all = expectThrows(EmulatorException.class, () -> framework.runTest("entes"));
                assertTrue(all.getMessage().contains("for circutor is used by another program"), all.getMessage());
            }
            assertTrue((System.nanoTime() - start) / 1e9 < 3);
        } finally {
            other.close();
            silent.close();
            for (Socket conn : held) {
                conn.close();
            }
        }
    }

    private static long logCount(Path results) {
        Path logs = results.resolve("logs");
        if (!Files.isDirectory(logs)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(logs)) {
            return files.count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String runIdOf(String listing, String ammeter) {
        return listing.lines().filter(line -> line.contains("_" + ammeter + "_"))
                .map(line -> line.split("\\|")[1].strip()).findFirst().orElseThrow();
    }

    @Test
    public void commandLine() throws Exception {
        Map<String, Object> raw = ownEmulatorsConfig();
        TestSupport.section(raw, "fault_injection").put("rates", sampling("garbage", 0.3)); // only used with --faults
        Path path = write(raw);
        Path other = tmp.resolve("other_results");
        String configFile = path.toString();
        String otherDir = other.toString();

        CliResult run = cli("--config", configFile, "run", "circutor", "--profile", "smoke", "--no-plots");
        assertEquals(run.code(), 0, run.out() + run.err());
        assertTrue(run.out().contains("## circutor: PASS"), run.out());
        assertTrue(run.out().contains("10 valid readings of 10 planned"), run.out());
        CliResult list = cli("--config", configFile, "list", "--ammeter", "circutor");
        assertEquals(list.code(), 0);
        String runId = runIdOf(list.out(), "circutor");
        CliResult show = cli("--config", configFile, "show", runId);
        assertEquals(show.code(), 0);
        assertTrue(show.out().contains(runId));
        assertEquals(cli("--config", configFile, "show", "no-such-run").code(), 2);

        for (int i = 0; i < 2; i++) {
            CliResult seededRun = cli("--config", configFile, "--results-dir", otherDir, "run", "entes", "--seed", "4",
                    "--count", "10", "--frequency", "20", "--no-plots");
            assertEquals(seededRun.code(), 0, seededRun.out() + seededRun.err());
            assertFalse(seededRun.out().contains("## Comparison"));
        }
        ResultStore store = new ResultStore(other);
        List<ResultStore.IndexEntry> stored = store.listRuns();
        assertEquals(stored.size(), 2);
        assertTrue(sameReadings(values(store.loadSamples(stored.get(0).runId())),
                values(store.loadSamples(stored.get(1).runId()))));
        CliResult failed = cli("--config", configFile, "--results-dir", otherDir, "list", "--status", "FAIL");
        assertEquals(failed.code(), 0);
        assertTrue(failed.out().contains("No runs found"));
        CliResult shown = cli("--config", configFile, "--results-dir", otherDir, "show", stored.get(0).runId());
        assertEquals(shown.code(), 0);
        assertTrue(shown.out().contains("| Median |"));

        ammeter(raw, "circutor").put("emulator", null); // nothing there
        ammeter(raw, "circutor").put("port", TestSupport.freePort());
        configFile = write(raw).toString();
        CliResult both = cli("--config", configFile, "--results-dir", otherDir, "run", "entes", "circutor", "--no-plots");
        assertEquals(both.code(), 1, both.out() + both.err());
        List<String> everyRun = store.listRuns().stream().map(ResultStore.IndexEntry::runId).toList();
        List<String> compareArgs = new ArrayList<>(List.of("--config", configFile, "--results-dir", otherDir, "compare"));
        compareArgs.addAll(everyRun);
        CliResult compared = cli(compareArgs.toArray(String[]::new));
        assertEquals(compared.code(), 0);
        assertTrue(compared.out().contains("circutor (status ERROR)"), compared.out()); // every run given is compared
        CliResult history = cli("--config", configFile, "--results-dir", otherDir, "history", "entes");
        assertEquals(history.code(), 0);
        assertTrue(history.out().contains("_entes_") && !history.out().contains("_circutor_"), history.out());
        CliResult entesList = cli("--config", configFile, "--results-dir", otherDir, "list", "--ammeter", "entes");
        assertEquals(entesList.code(), 0);
        assertTrue(entesList.out().contains("_entes_") && !entesList.out().contains("_circutor_"), entesList.out());
        CliResult faulty = cli("--config", configFile, "--results-dir", otherDir, "run", "entes", "--duration", "0.5",
                "--frequency", "20", "--faults", "--no-plots");
        assertTrue(faulty.out().contains("of 10 planned") && faulty.out().contains("Fault injection was on"),
                faulty.out() + faulty.err());

        // Ctrl-C: the thread running the command is interrupted once the run is sampling
        Thread runner = Thread.currentThread();
        long logsBefore = logCount(other);
        Thread interrupter = new Thread(() -> {
            try {
                while (logCount(other) <= logsBefore) {
                    Thread.sleep(20);
                }
                Thread.sleep(150);
            } catch (InterruptedException e) {
                return;
            }
            runner.interrupt();
        }, "ctrl-c");
        interrupter.setDaemon(true);
        interrupter.start();
        CliResult interrupted;
        try {
            interrupted = cli("--config", configFile, "--results-dir", otherDir, "run", "entes", "--count", "100",
                    "--frequency", "20", "--no-plots");
        } finally {
            interrupter.interrupt();
            interrupter.join();
            Thread.interrupted();
        }
        assertEquals(interrupted.code(), 1, interrupted.out() + interrupted.err());
        // the report of an interrupted run is shown
        assertTrue(interrupted.out().contains("## entes: INTERRUPTED"), interrupted.out());
    }

    @Test
    public void mainGetsAReadingFromEveryAmmeter() throws Exception {
        Map<String, Object> raw = TestSupport.config(tmp);
        freshPorts(raw);
        Jvm utf8 = runJvm(StandardCharsets.UTF_8, List.of("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"), 30,
                Main.class.getName(), write(raw).toString());
        assertEquals(count(utf8.out(), "Received current measurement"), 3, utf8.out() + utf8.err());
        assertFalse(utf8.out().contains("No data received"));

        freshPorts(raw);
        try (TestSupport.TcpServer device = TestSupport.TcpServer.start(TestSupport.answer("60.5"))) {
            ammeter(raw, "entes").put("port", device.port()); // a real device
            ammeter(raw, "entes").put("emulator", null);
            Charset cp1252 = Charset.forName("windows-1252");
            // Greenlee prints 'Ω', which cp1252 lacks
            Jvm old = runJvm(cp1252, List.of("-Dstdout.encoding=windows-1252", "-Dstderr.encoding=windows-1252"), 30,
                    Main.class.getName(), write(raw).toString());
            assertEquals(count(old.out(), "Received current measurement"), 3, old.out() + old.err());
            assertFalse(old.err().contains("Exception"), old.err());
        }
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
