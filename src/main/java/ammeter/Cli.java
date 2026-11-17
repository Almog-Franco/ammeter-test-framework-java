package ammeter;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** The command line: {@code java -jar ammeter.jar [--config FILE] [--results-dir DIR] run|list|show|compare|history ...}. */
public final class Cli {
    static final String PROG = "java -jar ammeter.jar";
    private static final List<String> COMMANDS = List.of("run", "list", "show", "compare", "history");
    private static final String USAGE = "usage: " + PROG + " [-h] [--config CONFIG] [--results-dir RESULTS_DIR] {run,list,show,compare,history} ...";
    private static final String HELP = USAGE + "\n\nAmmeter test framework\n\n"
            + "commands:\n"
            + "  run [AMMETER ...]  test one or more ammeters (all of them by default)\n"
            + "                     [--profile PROFILE] [--count N] [--duration SECONDS] [--frequency HZ]\n"
            + "                     [--faults] [--seed SEED] [--no-plots]\n"
            + "  list               list archived runs [--ammeter AMMETER] [--status PASS|FAIL|ERROR|INTERRUPTED]\n"
            + "  show RUN_ID        show the report of an archived run\n"
            + "  compare RUN_ID ... compare archived runs\n"
            + "  history AMMETER    all runs of one ammeter, with a trend plot\n\n"
            + "options:\n"
            + "  -h, --help         show this help message and exit\n"
            + "  --config CONFIG    config file (default: config/config.yaml)\n"
            + "  --results-dir RESULTS_DIR\n"
            + "                     where to store and read results (default: from the config)\n";

    /** Options with a value, per command; "" for the global ones. */
    private static final Map<String, Set<String>> VALUE_OPTIONS = Map.of(
            "", Set.of("--config", "--results-dir"),
            "run", Set.of("--profile", "--count", "--duration", "--frequency", "--seed"),
            "list", Set.of("--ammeter", "--status"),
            "show", Set.of(), "compare", Set.of(), "history", Set.of());
    private static final Map<String, Set<String>> FLAGS = Map.of(
            "", Set.of(), "run", Set.of("--faults", "--no-plots"),
            "list", Set.of(), "show", Set.of(), "compare", Set.of(), "history", Set.of());

    private Cli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /** Parsed arguments: option name to value ("true" for flags), and the positionals. */
    private record Args(String command, Map<String, String> options, List<String> positionals) {
        String get(String option) {
            return options.get(option);
        }
    }

    /** Runs the command line and returns the exit code: 0 all passed, 1 a test failed, 2 an error, 130 interrupted. */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        Args global;
        Args args;
        try {
            List<String> rest = new ArrayList<>(List.of(argv));
            global = parse("", rest, true);
            if (global.options().containsKey("--help")) {
                out.print(HELP);
                return 0;
            }
            if (rest.isEmpty()) {
                throw new UsageException("the following arguments are required: command");
            }
            String command = rest.remove(0);
            if (!COMMANDS.contains(command)) {
                throw new UsageException("argument command: invalid choice: " + Fmt.repr(command)
                        + " (choose from " + String.join(", ", COMMANDS) + ")");
            }
            args = parse(command, rest, false);
            if (args.options().containsKey("--help")) {
                out.print(HELP);
                return 0;
            }
            checkPositionals(args);
        } catch (UsageException e) {
            err.println(USAGE);
            err.println(PROG + ": error: " + e.getMessage());
            return 2;
        }

        Settings.Overrides overrides = new Settings.Overrides();
        if (global.get("--results-dir") != null) {
            overrides.resultsDir(Path.of(global.get("--results-dir")));
        }
        if (args.command().equals("run")) {
            Map<String, Object> sampling = new LinkedHashMap<>();
            try {
                putIfGiven(sampling, "measurements_count", args.get("--count") == null ? null : parseInt("--count", args.get("--count")));
                putIfGiven(sampling, "total_duration_seconds", args.get("--duration") == null ? null : parseFloat("--duration", args.get("--duration")));
                putIfGiven(sampling, "sampling_frequency_hz", args.get("--frequency") == null ? null : parseFloat("--frequency", args.get("--frequency")));
                overrides.profile(args.get("--profile"))
                        .sampling(sampling.isEmpty() ? null : sampling)
                        .faults(args.options().containsKey("--faults") ? Boolean.TRUE : null)
                        .plots(args.options().containsKey("--no-plots") ? Boolean.FALSE : null)
                        .emulatorSeed(args.get("--seed") == null ? null : (long) parseInt("--seed", args.get("--seed")));
            } catch (UsageException e) {
                err.println(USAGE);
                err.println(PROG + ": error: " + e.getMessage());
                return 2;
            }
        }

        String config = global.get("--config") == null ? Settings.DEFAULT_CONFIG : global.get("--config");
        try (AmmeterTestFramework framework = new AmmeterTestFramework(Path.of(config), overrides)) {
            return switch (args.command()) {
                case "run" -> withInterruptHandler(() -> cmdRun(framework, args, out));
                case "list" -> cmdList(framework, args, out);
                case "show" -> cmdShow(framework, args, out);
                case "compare" -> cmdCompare(framework, args, out);
                default -> cmdHistory(framework, args, out);
            };
        } catch (ConfigException | EmulatorException | ArchiveException e) {
            err.println("error: " + e.getMessage());
            return 2;
        }
    }

    private static int cmdRun(AmmeterTestFramework framework, Args args, PrintStream out) {
        AmmeterTestFramework.Result result = framework.runAll(args.positionals());
        for (Run run : result.runs()) {
            out.println(Reports.runReport(run, framework.settings().metrics()));
        }
        if (result.runs().size() > 1) {
            out.println(Reports.comparisonReport(result.comparison()));
        }
        out.println("Results saved in " + framework.store().root());
        return result.runs().stream().allMatch(run -> run.status() == Status.PASS) ? 0 : 1;
    }

    private static int cmdList(AmmeterTestFramework framework, Args args, PrintStream out) {
        out.println(Reports.runsTable(framework.store().listRuns(args.get("--ammeter"), args.get("--status"))));
        return 0;
    }

    private static int cmdShow(AmmeterTestFramework framework, Args args, PrintStream out) {
        out.println(Reports.runReport(framework.store().load(args.positionals().get(0)), framework.settings().metrics()));
        return 0;
    }

    private static int cmdCompare(AmmeterTestFramework framework, Args args, PrintStream out) {
        out.println(Reports.comparisonReport(framework.compareRuns(args.positionals())));
        return 0;
    }

    private static int cmdHistory(AmmeterTestFramework framework, Args args, PrintStream out) {
        String ammeter = args.positionals().get(0);
        List<ResultStore.IndexEntry> entries = framework.store().listRuns(ammeter, null);
        out.println(Reports.runsTable(entries));
        if (!entries.isEmpty() && framework.settings().plotsEnabled()) {
            Path path = Plots.plotHistory(entries, framework.store().root().resolve("history_" + ammeter + ".png"));
            if (path != null) {
                out.println("Trend plot: " + path);
            }
        }
        return 0;
    }

    /**
     * The first Ctrl-C interrupts the test, which then reports what it measured so far; the second one exits.
     * Without a console (e.g. in tests) the handler can't be installed and nothing changes.
     */
    @SuppressWarnings("removal")
    private static int withInterruptHandler(java.util.function.IntSupplier command) {
        Thread main = Thread.currentThread();
        AtomicInteger presses = new AtomicInteger();
        sun.misc.SignalHandler previous = null;
        sun.misc.Signal signal = null;
        try {
            signal = new sun.misc.Signal("INT");
            previous = sun.misc.Signal.handle(signal, s -> {
                if (presses.incrementAndGet() > 1) {
                    Runtime.getRuntime().halt(130);
                }
                main.interrupt();
            });
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            signal = null;
        }
        try {
            return command.getAsInt();
        } finally {
            if (signal != null) {
                sun.misc.Signal.handle(signal, previous);
            }
            Thread.interrupted();
        }
    }

    private static Args parse(String command, List<String> rest, boolean stopAtPositional) throws UsageException {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        Set<String> valueOptions = VALUE_OPTIONS.get(command);
        Set<String> flags = FLAGS.get(command);
        List<String> unknown = new ArrayList<>();
        while (!rest.isEmpty()) {
            String arg = rest.get(0);
            if (arg.equals("-h") || arg.equals("--help")) {
                rest.remove(0);
                options.put("--help", "true");
                continue;
            }
            if (!arg.startsWith("-") || arg.equals("-") || isNumber(arg)) {
                if (stopAtPositional) {
                    break;
                }
                positionals.add(rest.remove(0));
                continue;
            }
            rest.remove(0);
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (valueOptions.contains(name)) {
                if (value == null) {
                    if (rest.isEmpty() || (rest.get(0).startsWith("-") && !isNumber(rest.get(0)))) {
                        throw new UsageException("argument " + name + ": expected one argument");
                    }
                    value = rest.remove(0);
                }
                options.put(name, value);
            } else if (flags.contains(name) && value == null) {
                options.put(name, "true");
            } else {
                unknown.add(arg);
            }
        }
        if (!unknown.isEmpty()) {
            throw new UsageException("unrecognized arguments: " + String.join(" ", unknown));
        }
        return new Args(command, options, positionals);
    }

    private static void checkPositionals(Args args) throws UsageException {
        int n = args.positionals().size();
        switch (args.command()) {
            case "show" -> exactlyOne(n, "run_id", args);
            case "history" -> exactlyOne(n, "ammeter", args);
            case "compare" -> {
                if (n == 0) {
                    throw new UsageException("the following arguments are required: run_ids");
                }
            }
            case "list" -> {
                if (n > 0) {
                    throw new UsageException("unrecognized arguments: " + String.join(" ", args.positionals()));
                }
            }
            default -> {
            }
        }
    }

    private static void exactlyOne(int n, String name, Args args) throws UsageException {
        if (n == 0) {
            throw new UsageException("the following arguments are required: " + name);
        }
        if (n > 1) {
            throw new UsageException("unrecognized arguments: " + String.join(" ", args.positionals().subList(1, n)));
        }
    }

    private static boolean isNumber(String text) {
        return text.matches("-\\d+(\\.\\d*)?|-\\.\\d+");
    }

    private static int parseInt(String option, String text) throws UsageException {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new UsageException("argument " + option + ": invalid int value: " + Fmt.repr(text));
        }
    }

    private static double parseFloat(String option, String text) throws UsageException {
        try {
            String t = text.strip().toLowerCase();
            if (t.matches("[+-]?(inf|infinity|nan)")) {
                return Double.parseDouble(t.replace("infinity", "Infinity").replace("inf", "Infinity").replace("nan", "NaN"));
            }
            if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?")) {
                throw new NumberFormatException(text);
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw new UsageException("argument " + option + ": invalid float value: " + Fmt.repr(text));
        }
    }

    private static void putIfGiven(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
