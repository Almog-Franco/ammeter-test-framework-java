package ammeter;

import static ammeter.TestSupport.config;
import static ammeter.TestSupport.section;
import static ammeter.TestSupport.writeConfig;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class SettingsTest {

    /** A fresh temp folder, with symlinks resolved. */
    private static Path tmp() {
        try {
            return TestSupport.tempDir().toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static Settings.Overrides with() {
        return new Settings.Overrides();
    }

    /** Sets a dotted path in the config, e.g. "testing.acceptance.stuck_after". */
    @SuppressWarnings("unchecked")
    private static void set(Map<String, Object> config, String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> node = config;
        for (int i = 0; i < keys.length - 1; i++) {
            node = (Map<String, Object>) node.get(keys[i]);
        }
        node.put(keys[keys.length - 1], value);
    }

    private static void assertConfigError(String regex, Runnable action) {
        try {
            action.run();
        } catch (ConfigException e) {
            assertTrue(Pattern.compile(regex).matcher(e.getMessage()).find(),
                    "expected a message matching '" + regex + "' but got: " + e.getMessage());
            return;
        }
        fail("expected a ConfigException matching '" + regex + "'");
    }

    private static void write(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> paths(Map<?, ?> node, String prefix) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? (String) entry.getKey() : prefix + "." + entry.getKey();
            found.add(path);
            if (entry.getValue() instanceof Map) {
                found.addAll(paths((Map<?, ?>) entry.getValue(), path));
            }
        }
        return found;
    }

    @Test
    public void shippedConfigIsValid() {
        Settings settings = Settings.load();
        Map<String, Integer> ports = new LinkedHashMap<>();
        settings.ammeters().forEach((name, spec) -> ports.put(name, spec.port()));
        assertEquals(ports, Map.of("greenlee", 5001, "entes", 5002, "circutor", 5003));
        assertEquals(settings.ammeter("circutor").command(), "MEASURE_CIRCUTOR -get_measurement -current");
        assertEquals(settings.sampling().count(), 50);
        assertEquals(settings.sampling().frequencyHz(), 10.0, 0.0);
        assertEquals(settings.ammeter("entes").expectedGeoMean().doubleValue(), 55.51, 0.0);
        assertEquals(settings.acceptance().stuckAfter(), 3);
        assertEquals(settings.acceptance().maxModelZ(), 5.0, 0.0);
        assertNull(settings.emulatorSeed());
        assertEquals(settings.minReadingsToRank(), 30);
        assertEquals(settings.ammeter("ENTES").name(), "entes");
        assertConfigError("unknown ammeter 'fluke'.*configured: greenlee, entes, circutor",
                () -> settings.ammeter("fluke"));
    }

    @Test
    public void allProblemsAreReportedTogether() {
        Path tmp = tmp();
        Map<String, Object> original = map(
                "testing", map("sampling", map("measurements_count", null, "total_duration_seconds", null,
                        "sampling_frequency_hz", null)),
                "ammeters", null,
                "analysis", map("statistical_metrics", null,
                        "visualization", map("enabled", true, "plot_types", null)),
                "result_management", null);
        try {
            Settings.load(writeConfig(tmp, original));
            fail("expected a ConfigException");
        } catch (ConfigException e) {
            assertTrue(e.getMessage().contains("no ammeters configured"), e.getMessage());
            assertTrue(e.getMessage().contains("at least two"), e.getMessage());
        }

        Map<String, Object> config = config(tmp);
        set(config, "ammeters.entes.port", section(config, "ammeters", "greenlee").get("port"));
        section(config, "ammeters", "circutor").remove("command");
        set(config, "ammeters.greenlee.expected.min", 1000);
        set(config, "testing.sampling", map("measurements_count", 10));
        set(config, "testing.timeout_seconds", -1);
        set(config, "fault_injection.rates.meltdown", 0.1);
        try {
            Settings.load(writeConfig(tmp, config));
            fail("expected a ConfigException");
        } catch (ConfigException e) {
            String message = e.getMessage();
            for (String problem : List.of("port 5001 is already used by greenlee", "circutor.command is missing",
                    "expected.min must be smaller", "at least two", "timeout_seconds", "unknown fault 'meltdown'")) {
                assertTrue(message.contains(problem), problem + " missing from: " + message);
            }
        }
    }

    @DataProvider(name = "wrongValues")
    public Object[][] wrongValues() {
        return new Object[][] {
            {"testing.sampling", List.of(1, 2), "testing.sampling must be a section"},
            {"ammeters.greenlee.expected", List.of(1, 2), "ammeters.greenlee.expected must be a section"},
            {"fault_injection.rates", List.of("spike"), "fault_injection.rates must be a section"},
            {"testing", "fast", "testing must be a section"},
            {"ammeters", List.of("greenlee"), "ammeters must be a section"},
            {"testing.timeout_seconds", Double.POSITIVE_INFINITY, "timeout_seconds must be a number"},
            {"testing.acceptance.max_model_z", Double.NaN, "max_model_z must be a number"},
            {"testing.sampling", map("total_duration_seconds", Double.POSITIVE_INFINITY, "sampling_frequency_hz", 10),
                "positive number"},
            {"testing.start_emulators", "false", "start_emulators must be true or false"},
            {"fault_injection.enabled", "false", "enabled must be true or false"},
            {"analysis.visualization.enabled", 1, "enabled must be true or false"},
            {"ammeters.greenlee.host", 123, "host must be a host name"},
            {"ammeters.greenlee.emulator", List.of("greenlee"), "emulator must be one of"},
            {"ammeters.entes.expected", map("geo_mean", 55.5), "set both geo_mean and log_std"},
            {"ammeters.entes.expected.mean", 500, "mean must be between min and max"},
            {"ammeters.entes.expected.log_std", 0, "log_std must be above 0"},
            {"testing.emulator_seed", "abc", "emulator_seed must be a whole number"},
            {"testing.acceptance.stuck_after", 1, "stuck_after must be a whole number >= 2"},
            {"analysis.statistical_metrics", "mean", "statistical_metrics must be a list"},
            {"result_management.results_dir", 5, "results_dir must be a folder name"},
            {"testing.acceptance.max_frequency_error", 5, "max_frequency_error"},
            {"fault_injection.rates", map("timeout", 0.7, "spike", 0.7), "add up to more than 1"},
            {"ammeters", map(), "no ammeters configured"},
            {"testing.timeout_seconds", 0, "timeout_seconds"},
            {"ammeters.entes.expected.min", 200.0, "min must be smaller than max"},
            {"ammeters.entes.command", "   ", "command is missing"},
            {"testing.start_emulators", "yes", "must be true or false"},
            {"testing.start_emulators", "no", "must be true or false"},
            {"ammeters.entes.expected.max", Double.POSITIVE_INFINITY, "expected.max must be a number"},
            {"ammeters.entes.host", "", "host must be"},
            {"ammeters.my meter", map("port", 5009, "command", "X", "emulator", null), "may only use letters"},
            {"ammeters.Greenlee", map("port", 5009, "command", "X", "emulator", null), "Greenlee is defined twice"},
            {"testing.retries", -1, "retries must be a whole number >= 0"},
            {"ammeters.entes.port", 0, "port must be a number from 1 to 65535"},
        };
    }

    @Test(dataProvider = "wrongValues")
    public void wrongValuesAreReportedClearly(String path, Object value, String message) {
        Path tmp = tmp();
        Map<String, Object> config = config(tmp);
        set(config, path, value);
        assertConfigError(message, () -> Settings.load(writeConfig(tmp, config)));
    }

    @Test
    public void wrongTypesAnywhereGiveAConfigErrorNeverACrash() {
        Path tmp = tmp();
        List<String> crashes = new ArrayList<>();
        for (String path : paths(config(tmp), "")) {
            for (Object bad : Arrays.asList(List.of(1, 2), "x", 7, true, Double.POSITIVE_INFINITY, map("a", 1), null)) {
                Map<String, Object> broken = config(tmp);
                set(broken, path, bad);
                try {
                    Settings.load(writeConfig(tmp, broken), with().profile("smoke"));
                } catch (ConfigException expected) {
                    // the right way to refuse a bad config
                } catch (Exception e) {
                    crashes.add(path + " = " + bad + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        assertTrue(crashes.isEmpty(), crashes.stream().collect(Collectors.joining("\n")));
    }

    @Test
    public void profilesAndCommandLineOverrides() {
        Path tmp = tmp();
        Map<String, Object> config = config(tmp);
        section(config, "testing").putAll(map("retries", 0, "max_consecutive_errors", 0, "emulator_seed", 3));
        Path path = writeConfig(tmp, config);
        Settings settings = Settings.load(path);
        assertEquals(settings.retries(), 0);
        assertEquals(settings.maxConsecutiveErrors(), 0);
        assertEquals(settings.emulatorSeed(), Long.valueOf(3));
        assertEquals(Settings.load(path, with().profile("smoke")).sampling().count(), 10);
        Settings custom = Settings.load(path,
                with().sampling(map("total_duration_seconds", 2, "sampling_frequency_hz", 4)));
        assertEquals(custom.sampling().count(), 8);
        Settings both = Settings.load(path,
                with().profile("smoke").sampling(map("measurements_count", 7, "sampling_frequency_hz", 7)));
        assertEquals(both.sampling().count(), 7);
        assertTrue(Settings.load(path, with().faults(true)).faultsEnabled());
        assertFalse(Settings.load(path, with().faults(false)).faultsEnabled());
        assertEquals(Settings.load(path, with().emulatorSeed(9L)).emulatorSeed(), Long.valueOf(9));
        assertFalse(Settings.load(path, with().plots(false)).plotsEnabled());
        assertConfigError("unknown profile 'nightly'", () -> Settings.load(path, with().profile("nightly")));
        section(config, "testing").remove("max_consecutive_errors");
        // the documented default
        assertEquals(Settings.load(writeConfig(tmp, config)).maxConsecutiveErrors(), 5);
    }

    @Test
    public void effectiveSettingsCanBeStored() throws IOException, NoSuchAlgorithmException {
        Path tmp = tmp();
        Map<String, Object> config = config(tmp);
        Path path = writeConfig(tmp, config);
        Map<String, Object> stored = Settings.load(path, with().profile("smoke").emulatorSeed(9L)).toMap();
        assertEquals(((Number) section(stored, "sampling").get("measurements_count")).intValue(), 10);
        assertEquals(((Number) stored.get("emulator_seed")).longValue(), 9L);
        assertEquals(((Number) section(stored, "acceptance").get("max_model_z")).doubleValue(), 5.0, 0.0);
        assertEquals(((Number) section(stored, "ammeters", "entes").get("port")).intValue(), 5002);
        assertEquals(section(stored, "ammeters", "entes").get("command"), "MEASURE_ENTES -get_data");
        assertEquals(((Number) section(stored, "ammeters", "entes").get("expected_geo_mean")).doubleValue(), 55.51, 0.0);
        assertEquals(stored.get("config_path"), path.toRealPath().toString());
        assertEquals(stored.get("fault_rates"), section(config, "fault_injection", "rates"));
        assertEquals(stored.get("profile"), "smoke");
        assertEquals(stored.get("config_sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        assertFalse(stored.containsKey("raw"));
    }

    @Test
    public void configFilesAndPaths() throws IOException {
        // Java can't change the working folder, so "relative to the current folder" is a path relative to the
        // test JVM's folder that leads to the temp folder.
        Path tmp = tmp();
        Path rel = Path.of("").toAbsolutePath().relativize(tmp);
        Map<String, Object> config = config(tmp);
        set(config, "result_management.results_dir", "my_results");
        Path path = writeConfig(tmp, config);
        // must not matter: the config is relative to the project
        Files.createDirectory(tmp.resolve("my_results"));
        assertEquals(Settings.load(path).resultsDir(), Settings.PROJECT_ROOT.resolve("my_results"));
        assertEquals(Settings.load(path, with().resultsDir(rel.resolve("cli_results"))).resultsDir(),
                tmp.resolve("cli_results"));
        assertEquals(Settings.load(rel.resolve("config.yaml")).configPath(), path);
        Files.createDirectory(tmp.resolve("config"));
        set(config, "analysis.min_readings_to_rank", 31);
        writeConfig(tmp.resolve("config"), config);
        // the current folder's, not the project's
        assertEquals(Settings.load(rel.resolve("config").resolve("config.yaml")).minReadingsToRank(), 31);

        assertConfigError("not found", () -> Settings.load(tmp.resolve("nope.yaml")));
        // a folder
        assertConfigError("not found", () -> Settings.load(tmp));
        write(tmp.resolve("broken.yaml"), "ammeters: [unclosed");
        assertConfigError("broken.yaml is not valid YAML", () -> Settings.load(rel.resolve("broken.yaml")));
        write(tmp.resolve("list.yaml"), "- just\n- a list\n");
        assertConfigError("must contain key: value sections", () -> Settings.load(tmp.resolve("list.yaml")));
        write(tmp.resolve("word.yaml"), "hello");
        assertConfigError("must contain key: value sections", () -> Settings.load(rel.resolve("word.yaml")));
        write(tmp.resolve("empty.yaml"), "");
        assertConfigError("no ammeters configured", () -> Settings.load(rel.resolve("empty.yaml")));
    }
}
