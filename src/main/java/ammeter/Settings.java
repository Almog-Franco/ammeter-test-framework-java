package ammeter;

import ammeter.emulators.Emulators;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** The validated configuration, after command-line overrides. */
public record Settings(
        Map<String, AmmeterSpec> ammeters,
        SamplingPlan sampling,
        Acceptance acceptance,
        double timeoutS,
        int retries,
        int maxConsecutiveErrors,
        boolean startEmulators,
        Long emulatorSeed,
        List<String> metrics,
        int minReadingsToRank,
        boolean plotsEnabled,
        List<String> plotTypes,
        Path resultsDir,
        boolean faultsEnabled,
        Map<String, Double> faultRates,
        Object faultSeed,
        String profile,
        Path configPath,
        String configSha256,
        @JsonIgnore Map<String, Object> raw) {

    public static final Path PROJECT_ROOT = findProjectRoot();
    public static final String DEFAULT_CONFIG = "config/config.yaml";
    public static final List<String> METRICS =
            List.of("mean", "median", "std_dev", "min", "max", "cv", "geo_mean", "ci95", "outliers");
    public static final List<String> PLOT_TYPES = List.of("timeseries", "histogram", "comparison");
    public static final List<String> FAULT_TYPES = List.of("timeout", "no_response", "garbage", "spike", "stuck");
    private static final List<String> EMULATOR_NAMES = List.of("greenlee", "entes", "circutor");

    /** Values that replace the config file's, as given on the command line. Null means "use the config". */
    public static final class Overrides {
        String profile;
        Map<String, Object> sampling;
        Boolean faults;
        Path resultsDir;
        Boolean plots;
        Long emulatorSeed;

        public Overrides profile(String value) {
            profile = value;
            return this;
        }

        /** Replaces testing.sampling, e.g. {@code Map.of("measurements_count", 10, "sampling_frequency_hz", 20)}. */
        public Overrides sampling(Map<String, Object> value) {
            sampling = value;
            return this;
        }

        public Overrides faults(Boolean value) {
            faults = value;
            return this;
        }

        public Overrides resultsDir(Path value) {
            resultsDir = value;
            return this;
        }

        public Overrides plots(Boolean value) {
            plots = value;
            return this;
        }

        public Overrides emulatorSeed(Long value) {
            emulatorSeed = value;
            return this;
        }
    }

    public AmmeterSpec ammeter(String name) {
        AmmeterSpec spec = ammeters.get(String.valueOf(name).toLowerCase());
        if (spec == null) {
            throw new ConfigException("unknown ammeter " + Fmt.repr(name) + ", configured: " + String.join(", ", ammeters.keySet()));
        }
        return spec;
    }

    /** The settings a run actually used, after command-line overrides. Stored with every run. */
    public Map<String, Object> toMap() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = Json.MAPPER.convertValue(this, LinkedHashMap.class);
        return map;
    }

    public static Settings load() {
        return load(Path.of(DEFAULT_CONFIG), new Overrides());
    }

    public static Settings load(Path configPath) {
        return load(configPath, new Overrides());
    }

    /** A config path given by the user is taken from the current folder, the default one from the project. */
    public static Path resolveConfigPath(Path path) {
        return path.isAbsolute() || Files.exists(path) ? path.toAbsolutePath().normalize() : PROJECT_ROOT.resolve(path);
    }

    /** Loads and validates the config, reporting every problem at once. */
    public static Settings load(Path configPath, Overrides overrides) {
        Path path = resolveConfigPath(configPath);
        if (!Files.isRegularFile(path)) {
            throw new ConfigException("config file not found: " + path);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ConfigException("config file can't be read: " + path + " (" + e.getMessage() + ")");
        }
        Object loaded;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (YAMLException e) {
            throw new ConfigException(path + " is not valid YAML: " + e.getMessage());
        } catch (IOException e) {
            throw new ConfigException(path + " is not valid YAML: " + e);
        }
        loaded = loaded == null ? new LinkedHashMap<>() : loaded;
        if (!(loaded instanceof Map)) {
            throw new ConfigException(path + " must contain key: value sections, not a " + Fmt.typeName(loaded));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) loaded;

        List<String> errors = new ArrayList<>();
        Map<?, ?> testing = section(raw, "testing", errors, "");
        Map<?, ?> acceptance = section(testing, "acceptance", errors, "testing");
        Map<?, ?> analysis = section(raw, "analysis", errors, "");
        Map<?, ?> visualization = section(analysis, "visualization", errors, "analysis");
        Map<?, ?> results = section(raw, "result_management", errors, "");
        Map<?, ?> faultCfg = section(raw, "fault_injection", errors, "");

        Map<String, AmmeterSpec> ammeters = parseAmmeters(raw.get("ammeters"), errors);

        Map<?, ?> sampling = overrides.sampling;
        if (sampling == null && overrides.profile != null && !overrides.profile.isEmpty()) {
            Map<?, ?> profiles = section(testing, "profiles", errors, "testing");
            if (profiles.containsKey(overrides.profile)) {
                sampling = section(profiles, overrides.profile, errors, "testing.profiles");
            } else {
                String available = profiles.keySet().stream().map(Fmt::str).collect(Collectors.joining(", "));
                errors.add("unknown profile " + Fmt.repr(overrides.profile) + ", available: "
                        + (available.isEmpty() ? "none" : available));
                sampling = Map.of();
            }
        }
        if (sampling == null) {
            sampling = section(testing, "sampling", errors, "testing");
        }
        SamplingPlan plan = null;
        try {
            plan = SamplingPlan.fromConfig(sampling.get("measurements_count"), sampling.get("total_duration_seconds"),
                    sampling.get("sampling_frequency_hz"));
        } catch (ConfigException e) {
            errors.add(e.getMessage());
        }

        Map<String, Double> rates = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : section(faultCfg, "rates", errors, "fault_injection").entrySet()) {
            Object fault = entry.getKey();
            Object rate = entry.getValue();
            if (!FAULT_TYPES.contains(fault)) {
                errors.add("fault_injection.rates: unknown fault " + Fmt.repr(fault) + ", use " + String.join(", ", FAULT_TYPES));
            } else if (!isNumber(rate) || !(0 <= toDouble(rate) && toDouble(rate) <= 1)) {
                errors.add("fault_injection.rates." + fault + " must be between 0 and 1, got " + Fmt.repr(rate));
            } else {
                rates.put((String) fault, toDouble(rate));
            }
        }
        if (rates.values().stream().mapToDouble(Double::doubleValue).sum() > 1) {
            errors.add("fault_injection.rates add up to more than 1");
        }
        Object faultSeed = faultCfg.get("seed");
        if (faultSeed != null && !isWholeNumber(faultSeed) && !(faultSeed instanceof String)) {
            errors.add("fault_injection.seed must be a whole number or text, got " + Fmt.repr(faultSeed));
        }

        Object folder = results.get("results_dir");
        if (folder != null && (!(folder instanceof String) || ((String) folder).isBlank())) {
            errors.add("result_management.results_dir must be a folder name, got " + Fmt.repr(folder));
            folder = null;
        }

        Object seed = overrides.emulatorSeed == null ? testing.get("emulator_seed") : overrides.emulatorSeed;
        Long emulatorSeed = null;
        if (seed != null && !(isWholeNumber(seed) && fitsInLong((Number) seed))) {
            errors.add("testing.emulator_seed must be a whole number or null, got " + Fmt.repr(seed));
        } else if (seed != null) {
            emulatorSeed = ((Number) seed).longValue();
        }

        String where = "testing.acceptance";
        Settings settings = new Settings(
                ammeters,
                plan,
                new Acceptance(
                        number(acceptance, "max_error_rate", 0.05, errors, where, false, 0, 1.0),
                        number(acceptance, "max_frequency_error", 0.1, errors, where, false, 0, 1.0),
                        (int) number(acceptance, "stuck_after", 3, errors, where, true, 2, null),
                        number(acceptance, "max_model_z", 5.0, errors, where, false, 0.1, null),
                        number(acceptance, "max_drift_t", 5.0, errors, where, false, 0.1, null)),
                number(testing, "timeout_seconds", 2.0, errors, "testing", false, 0.01, null),
                (int) number(testing, "retries", 1, errors, "testing", true, 0, null),
                (int) number(testing, "max_consecutive_errors", 5, errors, "testing", true, 0, null),
                bool(testing, "start_emulators", true, errors, "testing"),
                emulatorSeed,
                choices(analysis, "statistical_metrics", METRICS, errors, "analysis"),
                (int) number(analysis, "min_readings_to_rank", 30, errors, "analysis", true, 2, null),
                overrides.plots == null ? bool(visualization, "enabled", true, errors, "analysis.visualization") : overrides.plots,
                choices(visualization, "plot_types", PLOT_TYPES, errors, "analysis.visualization"),
                // from the command line: relative to the current folder; from the config: relative to the project
                overrides.resultsDir != null
                        ? overrides.resultsDir.toAbsolutePath().normalize()
                        : PROJECT_ROOT.resolve(folder == null ? "results" : (String) folder),
                overrides.faults == null ? bool(faultCfg, "enabled", false, errors, "fault_injection") : overrides.faults,
                rates,
                faultSeed,
                overrides.profile,
                path,
                sha256(bytes),
                raw);
        if (!errors.isEmpty()) {
            throw new ConfigException("invalid configuration in " + path + ":\n  - " + String.join("\n  - ", errors));
        }
        return settings;
    }

    private static Map<String, AmmeterSpec> parseAmmeters(Object raw, List<String> errors) {
        Map<String, AmmeterSpec> specs = new LinkedHashMap<>();
        if (raw == null || isEmpty(raw)) {
            errors.add("ammeters: no ammeters configured");
            return specs;
        }
        if (!(raw instanceof Map)) {
            errors.add("ammeters must be a section with one entry per ammeter, got " + Fmt.typeName(raw));
            return specs;
        }
        Map<List<Object>, String> usedPorts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            String key = Fmt.str(entry.getKey());
            String name = key.toLowerCase();
            String where = "ammeters." + key;
            if (!name.matches("[a-z0-9_-]+")) { // the name becomes part of run ids and folder names
                errors.add(where + ": an ammeter name may only use letters, digits, _ and -");
                continue;
            }
            if (specs.containsKey(name)) {
                errors.add(where + " is defined twice");
                continue;
            }
            if (!(entry.getValue() instanceof Map)) {
                errors.add(where + " must be a section with port, command, ...");
                continue;
            }
            Map<?, ?> cfg = (Map<?, ?>) entry.getValue();
            Object port = cfg.get("port");
            Object command = cfg.get("command");
            Object host = cfg.get("host") == null ? "127.0.0.1" : cfg.get("host");
            boolean portOk = isWholeNumber(port) && 1 <= ((Number) port).doubleValue() && ((Number) port).doubleValue() <= 65535;
            if (!portOk) {
                errors.add(where + ".port must be a number from 1 to 65535, got " + Fmt.repr(port));
            }
            if (!(host instanceof String) || ((String) host).isBlank()) {
                errors.add(where + ".host must be a host name or IP address, got " + Fmt.repr(host));
            } else if (portOk && usedPorts.containsKey(List.of(host, ((Number) port).intValue()))) {
                errors.add(where + ".port " + port + " is already used by " + usedPorts.get(List.of(host, ((Number) port).intValue())));
            } else if (portOk) {
                usedPorts.put(List.of(host, ((Number) port).intValue()), name);
            }
            if (!(command instanceof String) || ((String) command).isBlank()) {
                errors.add(where + ".command is missing");
            }

            Object emulator = cfg.containsKey("emulator") ? cfg.get("emulator")
                    : Emulators.EMULATORS.containsKey(name) ? name : null;
            if (emulator != null && !(emulator instanceof String && Emulators.EMULATORS.containsKey(emulator))) {
                errors.add(where + ".emulator must be one of " + String.join(", ", EMULATOR_NAMES) + " or null, got "
                        + Fmt.repr(emulator));
            }

            Map<?, ?> expected = section(cfg, "expected", errors, where);
            Map<String, Double> values = new LinkedHashMap<>();
            for (String k : List.of("min", "max", "mean", "geo_mean", "log_std")) {
                Object value = expected.get(k);
                if (value != null && !isNumber(value)) {
                    errors.add(where + ".expected." + k + " must be a number, got " + Fmt.repr(value));
                    value = null;
                }
                values.put(k, value == null ? null : toDouble(value));
            }
            Double low = values.get("min");
            Double high = values.get("max");
            if (low != null && high != null) {
                if (low >= high) {
                    errors.add(where + ".expected.min must be smaller than max");
                }
                for (String k : List.of("mean", "geo_mean")) {
                    Double v = values.get(k);
                    if (v != null && !(low <= v && v <= high)) {
                        errors.add(where + ".expected." + k + " must be between min and max");
                    }
                }
            }
            if ((values.get("geo_mean") == null) != (values.get("log_std") == null)) {
                errors.add(where + ".expected: set both geo_mean and log_std, or neither");
            }
            for (String k : List.of("geo_mean", "log_std")) {
                if (values.get(k) != null && values.get(k) <= 0) {
                    errors.add(where + ".expected." + k + " must be above 0");
                }
            }

            specs.put(name, new AmmeterSpec(name, portOk ? ((Number) port).intValue() : 0,
                    command instanceof String ? (String) command : null,
                    host instanceof String ? (String) host : null,
                    emulator instanceof String ? (String) emulator : null,
                    low, high, values.get("mean"), values.get("geo_mean"), values.get("log_std")));
        }
        return specs;
    }

    /** A sub-section of the config. Missing or empty is fine; anything but key: value pairs is an error. */
    private static Map<?, ?> section(Map<?, ?> parent, String key, List<String> errors, String where) {
        Object value = parent.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            String name = where.isEmpty() ? key : where + "." + key;
            errors.add(name + " must be a section of key: value pairs, got " + Fmt.typeName(value) + " " + Fmt.repr(value));
            return Map.of();
        }
        return (Map<?, ?>) value;
    }

    private static boolean isNumber(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue());
    }

    private static boolean isWholeNumber(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof BigInteger;
    }

    private static boolean fitsInLong(Number value) {
        return !(value instanceof BigInteger) || ((BigInteger) value).bitLength() < 64;
    }

    private static double toDouble(Object value) {
        return ((Number) value).doubleValue();
    }

    private static boolean isEmpty(Object value) {
        return value instanceof Map ? ((Map<?, ?>) value).isEmpty()
                : value instanceof List ? ((List<?>) value).isEmpty()
                : value instanceof String ? ((String) value).isEmpty()
                : Objects.equals(value, Boolean.FALSE) || (value instanceof Number && ((Number) value).doubleValue() == 0);
    }

    private static double number(Map<?, ?> cfg, String key, double defaultValue, List<String> errors, String where,
                                 boolean integer, double low, Double high) {
        Object value = cfg.get(key);
        if (value == null) {
            return defaultValue;
        }
        boolean valid = isNumber(value) && toDouble(value) >= low && (high == null || toDouble(value) <= high);
        if (integer && valid) {
            valid = toDouble(value) == Math.floor(toDouble(value));
        }
        if (!valid) {
            String limits = ">= " + Fmt.g(low) + (high != null ? " and <= " + Fmt.g(high) : "");
            errors.add(where + "." + key + " must be a " + (integer ? "whole " : "") + "number " + limits + ", got "
                    + Fmt.repr(value));
            return defaultValue;
        }
        return integer ? Math.floor(toDouble(value)) : toDouble(value);
    }

    private static boolean bool(Map<?, ?> cfg, String key, boolean defaultValue, List<String> errors, String where) {
        Object value = cfg.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean)) {
            errors.add(where + "." + key + " must be true or false, got " + Fmt.repr(value));
            return defaultValue;
        }
        return (Boolean) value;
    }

    private static List<String> choices(Map<?, ?> cfg, String key, List<String> allowed, List<String> errors, String where) {
        Object value = cfg.get(key);
        if (value == null) {
            return new ArrayList<>(allowed);
        }
        if (!(value instanceof List)) {
            errors.add(where + "." + key + " must be a list, got " + Fmt.repr(value));
            return new ArrayList<>(allowed);
        }
        List<?> unknown = ((List<?>) value).stream().filter(v -> !allowed.contains(v)).collect(Collectors.toList());
        if (!unknown.isEmpty()) {
            errors.add(where + "." + key + ": unknown " + Fmt.repr(unknown) + ", choose from " + String.join(", ", allowed));
            return new ArrayList<>(allowed);
        }
        return ((List<?>) value).stream().map(String.class::cast).collect(Collectors.toList());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The folder with config/config.yaml: the {@code ammeter.home} system property, else the first folder above the
     * code (target/classes or target/ammeter.jar) that has one, else the current folder.
     */
    private static Path findProjectRoot() {
        String home = System.getProperty("ammeter.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home).toAbsolutePath().normalize();
        }
        try {
            Path code = Path.of(Settings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path dir = code; dir != null; dir = dir.getParent()) {
                if (Files.isRegularFile(dir.resolve(DEFAULT_CONFIG))) {
                    return dir;
                }
            }
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            // fall through to the current folder
        }
        return Path.of("").toAbsolutePath();
    }
}
