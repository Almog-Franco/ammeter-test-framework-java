package ammeter;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Tests ammeters: samples, analyses and archives runs, and compares them. */
public class AmmeterTestFramework implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AmmeterTestFramework.class.getName());
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    /** The runs of {@link #runAll} and their comparison. */
    public record Result(List<Run> runs, Comparison comparison) {
    }

    private final Settings settings;
    private final ResultStore store;
    private EmulatorProcess emulators;
    private boolean emulatorsChecked;

    public AmmeterTestFramework(Path configPath, Settings.Overrides overrides) {
        settings = Settings.load(configPath, overrides);
        store = new ResultStore(settings.resultsDir());
    }

    public AmmeterTestFramework(Path configPath) {
        this(configPath, new Settings.Overrides());
    }

    public AmmeterTestFramework() {
        this(Path.of(Settings.DEFAULT_CONFIG));
    }

    public Settings settings() {
        return settings;
    }

    /** The config file as read, before overrides. */
    public Map<String, Object> config() {
        return settings.raw();
    }

    public ResultStore store() {
        return store;
    }

    @Override
    public synchronized void close() {
        if (emulators != null) {
            emulators.stop();
            emulators = null;
        }
        emulatorsChecked = false;
    }

    /** Samples one ammeter, analyses and archives the run, and returns it. */
    public Run runTest(String ammeterType) {
        AmmeterSpec spec = settings.ammeter(ammeterType);
        ensureEmulators();
        SamplingPlan plan = settings.sampling();
        String runId = ResultStore.newId(spec.name());
        try (TestLogger logger = new TestLogger(runId.split("_", 2)[1], settings.resultsDir().resolve("logs"))) {
            logger.info(spec.name() + " at " + spec.host() + ":" + spec.port() + ": " + plan.count()
                    + " readings at " + Fmt.g(plan.frequencyHz()) + " Hz");

            AmmeterClient client = AmmeterClient.fromSpec(spec, settings.timeoutS(), settings.retries());
            String startedAt = now();
            Sampler.Result result = new Sampler().run(plan, client::measure, settings.maxConsecutiveErrors());
            String finishedAt = now();

            List<Sample> samples = result.samples();
            List<Double> values = new ArrayList<>();
            Map<String, Integer> errors = new LinkedHashMap<>();
            int failed = 0;
            for (Sample s : samples) {
                if (s.value() != null) {
                    values.add(s.value());
                }
                if (s.error() != null && !s.error().isEmpty()) {
                    failed++;
                    logger.warning("reading " + s.index() + " failed: " + s.error());
                    errors.merge(s.error().split(":", 2)[0], 1, Integer::sum);
                }
            }
            double errorRate = samples.isEmpty() ? 0.0 : (double) failed / samples.size();
            Timing timing = Analysis.consistency(samples, plan);
            List<Check> checks = Analysis.evaluate(values, errorRate, timing, spec, settings.acceptance());
            int outOfRange = Analysis.countOutOfRange(values, spec.expectedMin(), spec.expectedMax());

            Run run = new Run(
                    runId,
                    Analysis.verdict(checks, values.size(), result.stopReason()),
                    spec,
                    plan,
                    startedAt,
                    finishedAt,
                    result.stopReason(),
                    result.missed(),
                    Analysis.summarize(values),
                    failed,
                    errorRate,
                    errors,
                    values.isEmpty() ? 0.0 : (double) outOfRange / values.size(),
                    timing,
                    checks,
                    faultOptions(),
                    settings.toMap(),
                    new Run.ConfigInfo(settings.configPath().toString(), settings.configSha256(), settings.profile(), settings.raw()),
                    environment(),
                    logger.logFile().toString());
            store.save(run, samples);
            if (settings.plotsEnabled()) {
                Plots.plotRun(run, samples, store.runDir(runId).resolve("plots"), settings.plotTypes());
            }
            store.writeFile(runId, "report.md", Reports.runReport(run, settings.metrics()));
            logger.info("finished with status " + run.status());
            return run;
        }
    }

    /** Runs every given (default: every configured) ammeter and compares them. */
    public Result runAll(List<String> ammeterTypes) {
        List<String> names = ammeterTypes == null || ammeterTypes.isEmpty() ? List.copyOf(settings.ammeters().keySet()) : ammeterTypes;
        for (String name : names) {
            settings.ammeter(name); // an unknown name fails now, not after testing the others
        }
        List<Run> runs = new ArrayList<>();
        for (String name : names) {
            runs.add(runTest(name));
            if (runs.get(runs.size() - 1).status() == Status.INTERRUPTED) {
                break;
            }
        }
        Comparison comparison = Analysis.compare(runs, settings.minReadingsToRank());
        Path folder = store.saveComparison(comparison, Reports.comparisonReport(comparison));
        if (settings.plotsEnabled() && settings.plotTypes().contains("comparison") && runs.size() > 1) {
            List<Plots.RunSamples> pairs = new ArrayList<>();
            for (Run run : runs) {
                pairs.add(new Plots.RunSamples(run, store.loadSamples(run.runId())));
            }
            Plots.plotComparison(pairs, folder.resolve("comparison.png"));
        }
        return new Result(runs, comparison.withFolder(folder.toString()));
    }

    public Result runAll() {
        return runAll(null);
    }

    public Comparison compareRuns(List<String> runIds) {
        List<Run> runs = new ArrayList<>();
        for (String runId : runIds) {
            runs.add(store.load(runId));
        }
        return Analysis.compare(runs, settings.minReadingsToRank());
    }

    /** Starts the emulators that aren't running yet. A port that answers with garbage belongs to someone else. */
    private synchronized void ensureEmulators() {
        if (emulatorsChecked || !settings.startEmulators()) {
            return;
        }
        List<AmmeterSpec> missing = new ArrayList<>();
        for (AmmeterSpec spec : settings.ammeters().values()) {
            if (spec.emulator() == null) {
                continue;
            }
            if (!EmulatorProcess.portIsOpen(spec.host(), spec.port())) { // a quick check: on Windows a refusal takes about 2 s
                missing.add(spec);
                continue;
            }
            try {
                AmmeterClient.fromSpec(spec, 1.0, 0).measure();
            } catch (AmmeterException e) {
                String hint = spec.port() == 5000 && System.getProperty("os.name").startsWith("Mac") ? " (on macOS this is AirPlay Receiver)" : "";
                throw new EmulatorException("port " + spec.port() + " for " + spec.name() + " is used by another program"
                        + hint + ", pick another port in the config");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (settings.faultsEnabled() || settings.emulatorSeed() != null) {
                LOG.warning(spec.name() + " is already running, so its faults and seed can't be set");
            }
        }
        if (!missing.isEmpty()) {
            Path logPath = settings.resultsDir().resolve("logs").resolve("emulators.log");
            emulators = new EmulatorProcess(missing, logPath, faultOptions(), settings.emulatorSeed()).start();
        }
        emulatorsChecked = true;
    }

    private Run.FaultOptions faultOptions() {
        if (!settings.faultsEnabled()) {
            return null;
        }
        return new Run.FaultOptions(settings.faultRates(), settings.faultSeed(), settings.timeoutS() + 1);
    }

    private static Map<String, String> environment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("java", System.getProperty("java.version"));
        environment.put("platform", System.getProperty("os.name") + "-" + System.getProperty("os.version") + "-" + System.getProperty("os.arch"));
        return environment;
    }

    private static String now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(ISO_MILLIS);
    }
}
