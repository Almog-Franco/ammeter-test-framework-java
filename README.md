# Ammeter Test Framework (Java)

Java port of the [Python ammeter test framework](https://github.com/Almog-Franco/ammeter-test-framework). Emulators for three ammeters (Greenlee, ENTES, CIRCUTOR) and a
framework that samples them, analyses the readings, decides PASS or FAIL, and keeps every run in a results archive. It
can also inject errors into the emulators to show that the error handling works.

The commands, options, config file, checks, reports and results layout are the same as in the Python version. The
[differences](#differences-from-the-python-version) are listed at the end.

- [docs/DESIGN.md](docs/DESIGN.md): design decisions, checks and their limits, results layout
- [sample_results/](sample_results/README.md): the three sample scenarios and the commands that produce them
- [FIXES.md](https://github.com/Almog-Franco/ammeter-test-framework/blob/main/docs/FIXES.md) in the Python repo: every change to the original Python code, and why (not repeated here)

## Setup

A JDK, version 17 or newer, on Windows, macOS or Linux. Maven is not needed: the Maven wrapper downloads it on first
use, so the first build needs internet access.

```sh
git clone https://github.com/Almog-Franco/ammeter-test-framework-java.git
cd ammeter-test-framework-java
./mvnw package -DskipTests         # Windows: mvnw.cmd package -DskipTests
```

This builds `target/ammeter.jar`, a single runnable jar with all libraries inside. Without `-DskipTests` the build also
runs the tests (see [below](#the-frameworks-own-tests)). All commands below run from the repository root.

Libraries used:

| Library | Version | Used for |
|---|---|---|
| SnakeYAML | 2.7 | Reading `config.yaml` |
| Jackson databind | 2.23.0 | `run.json`, `comparison.json`, `index.jsonl`, and the options sent to the emulator process |
| XChart | 4.0.4 | Charts (PNG files) |
| TestNG | 7.12.0 | The framework's own tests (test scope only) |

Everything else is the JDK. No library is optional: charts are always available.

## Usage

**Quick check**: start the emulators and get one reading from each:

```sh
java -cp target/ammeter.jar ammeter.Main
```

**Test the ammeters**: all of them by default. Emulators that aren't running are started automatically:

```sh
java -jar target/ammeter.jar run                          # all ammeters, sampling from config.yaml, plus a comparison
java -jar target/ammeter.jar run entes circutor           # only these two
java -jar target/ammeter.jar run --profile smoke          # quick run: 10 readings at 20 Hz
java -jar target/ammeter.jar run --count 400 --frequency 40
java -jar target/ammeter.jar run --duration 30 --frequency 5
java -jar target/ammeter.jar run --faults                 # inject errors and see them caught
java -jar target/ammeter.jar run --seed 1                 # repeatable readings
java -jar target/ammeter.jar run --no-plots               # skip the charts
```

The exit code of `run` is:

| Code | Meaning |
|---|---|
| 0 | every run passed |
| 1 | at least one run did not pass (FAIL, ERROR or INTERRUPTED) |
| 2 | config, emulator or archive error, or bad command-line usage |
| 130 | second Ctrl-C (see [below](#ctrl-c)) |

`list`, `show`, `compare` and `history` return 0, or 2 on an error.

**Look at past results**:

```sh
java -jar target/ammeter.jar list [--ammeter entes] [--status FAIL]
java -jar target/ammeter.jar show <run_id>
java -jar target/ammeter.jar --results-dir sample_results show <run_id>      # a run from sample_results/
java -jar target/ammeter.jar compare <run_id> <run_id> ...
java -jar target/ammeter.jar history entes                # every run of one ammeter, with a trend chart
```

`--config FILE` and `--results-dir DIR` go before the command. `--help` prints the usage.

### Ctrl-C

The first Ctrl-C during `run` interrupts the run. The reading in progress finishes (it takes at most the timeout), the
readings collected so far are analysed and archived with status `INTERRUPTED`, the remaining ammeters are not run, and
the exit code is 1. A second Ctrl-C exits at once with code 130.

**From Java** (see [RunTests.java](src/main/java/ammeter/examples/RunTests.java), run with
`java -cp target/ammeter.jar ammeter.examples.RunTests`):

```java
try (AmmeterTestFramework framework = new AmmeterTestFramework(Path.of("config/config.yaml"))) {
    Run run = framework.runTest("greenlee");            // status, statistics, timing, checks, settings, ...
    System.out.println(run.status() + " " + run.statistics().mean());
    AmmeterTestFramework.Result all = framework.runAll();   // all ammeters + comparison
}
```

**The tools**: re-derive the expected values and check limits by simulation:

```sh
java -cp target/ammeter.jar ammeter.tools.CalibrateChecks [--runs 200000]
```

### The framework's own tests

```sh
./mvnw test                                        # all tests; they start real emulators and take a while
./mvnw test -Dtest=AnalysisTest                    # one test class
UPDATE_GOLDEN=1 ./mvnw test -Dtest=ReportsTest     # after changing a report on purpose: save new copies
```

The saved report copies are in [src/test/resources/golden/](src/test/resources/golden/). On Windows, set the
variable first (`set UPDATE_GOLDEN=1` in cmd).

## What a run produces

The output has the same format as in the Python version. This example shows the format; the numbers are
illustrative.

```
## circutor: PASS

Run `20261026T135640Z_circutor_66d582`, 127.0.0.1:5003, started 2026-10-26T13:56:40.602+00:00
50 valid readings of 50 planned, 0 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 0.03317 A |
| Median | 0.03514 A |
| Std deviation | 0.01576 A |
...
| Check | Result | Detail |
|---|---|---|
| values_in_range | PASS | 0 of 50 outside [0.001, 0.1] A |
| error_rate | PASS | 0.0% failed (limit 5%) |
| sampling_rate | PASS | 50 of 50 planned readings taken, 10 Hz for 10 Hz requested (0.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | PASS | geometric mean 0.02794 A, model 0.02583 A (z = +0.91, limit ±5) |
| no_drift | PASS | -12.2 % over the run (t = -0.53, limit ±5) |
```

When readings fail, the report ends with the error counts, for example `Errors: AmmeterTimeoutException x1`.

The checks are explained, with their false-alarm rates and limits, in
[docs/DESIGN.md](docs/DESIGN.md#acceptance-checks-and-verdict).

A comparison names a winner only when the evidence is clear:
- **It ranks precision (CV) among runs that passed every check.** A run also needs at least 30 readings to take part.
- **It says "insufficient evidence" when the precision of the best runs overlaps.** With the default 50 readings,
  CIRCUTOR and ENTES usually tie; with 400 readings CIRCUTOR wins clearly.
- **It compares how the emulators behave, not which measurement method is more accurate.** That would need every
  meter measuring the same known current.

Each run is stored in `results/runs/<run_id>/`:

- `run.json`: the effective settings, the full config, all results
- `samples.csv`: every reading
- `report.md`: the report above
- `plots/`: the charts (`timeseries.png`, `histogram.png`)

`results/index.jsonl` lists all runs. Comparisons go to `results/comparisons/<id>/`, logs to `results/logs/`. See
[docs/DESIGN.md](docs/DESIGN.md#results-archive).

## Configuration

Everything is in [config/config.yaml](config/config.yaml), which is the same file as in the Python version:

| Section | What it sets |
|---|---|
| `testing.sampling` | Any two of `measurements_count`, `total_duration_seconds`, `sampling_frequency_hz`. The third is calculated |
| `testing.profiles` | Named sampling settings, used with `--profile` |
| `testing` | `timeout_seconds` (whole request), `retries`, `max_consecutive_errors` (0 = never stop early), `start_emulators`, `emulator_seed` |
| `testing.acceptance` | `max_error_rate`, `max_frequency_error` (missed readings), `stuck_after`, `max_model_z`, `max_drift_t` |
| `ammeters.<name>` | `port`, `command`, optional `host` and `emulator`, and `expected` min / max / mean / geo_mean / log_std |
| `analysis` | Which metrics the report shows, `min_readings_to_rank`, which charts are made |
| `result_management.results_dir` | Where results go. Relative paths are taken from the project folder (`--results-dir` from the current folder) |
| `fault_injection` | `enabled`, `seed`, and the rate of each fault (`timeout`, `no_response`, `garbage`, `spike`, `stuck`) |

The config is checked when it's loaded:
- every section must be key: value pairs
- numbers must be finite and in range
- switches must be real `true`/`false`

Every problem is listed at once. The expected values come from the emulators' physics (see
[docs/DESIGN.md](docs/DESIGN.md#what-the-emulators-should-produce)). They can be re-derived with
`java -cp target/ammeter.jar ammeter.tools.CalibrateChecks`.

The "project folder" is the first folder above the jar (or the compiled classes) that contains
`config/config.yaml`, so for the shipped build it is `java/`, and results go to `java/results/` whatever the current
folder is. If the jar is moved elsewhere, the current folder is used. `-Dammeter.home=DIR` sets it explicitly.

## Ammeter emulators

| Ammeter | Port | Command | Method |
|---|---|---|---|
| Greenlee | 5001 | `MEASURE_GREENLEE -get_measurement` | Ohm's law, I = V / R, with V 1–10 V and R 0.1–100 Ω |
| ENTES | 5002 | `MEASURE_ENTES -get_data` | Hall effect, I = B · K, with B 0.01–0.1 T and K 500–2000 |
| CIRCUTOR | 5003 | `MEASURE_CIRCUTOR -get_measurement -current` | Rogowski coil integration, I = ∫V dt, over 10 samples of 0.1–1.0 V, with a time step of 1–10 ms |

Each emulator answers one request per TCP connection and closes the connection without a reply when the command
doesn't match exactly. Port 5000 is avoided because macOS uses it for AirPlay Receiver.

When the framework starts the emulators itself, it runs them in a separate JVM (`ammeter.emulators.Emulators`) and
writes their output to `results/logs/emulators.log`.

## Project structure

```
pom.xml                         Maven build (Java 17, shaded jar target/ammeter.jar, main class ammeter.Cli)
mvnw, mvnw.cmd, .mvn/           Maven wrapper
config/config.yaml              all settings
src/main/java/ammeter/
  emulators/                    the emulators: AmmeterEmulator (base), GreenleeAmmeter, EntesAmmeter,
                                CircutorAmmeter, Utils (random helper), SimpleClient (the simple client),
                                FaultyAmmeter (error simulation), Emulators (registry and child-process entry point)
  Cli.java                      command line (java -jar target/ammeter.jar ...)
  AmmeterTestFramework.java     ties everything together
  Settings, AmmeterSpec,
    Acceptance                  load and validate the config
  AmmeterClient.java            one interface for every ammeter
  SamplingPlan, Sampler, Sample sampling plan and scheduler
  Analysis, Statistics, Timing,
    Check, Comparison, Status   statistics, timing, checks, comparison
  Run, ResultStore, Json        the results archive
  Reports, Plots, Fmt           reports and charts
  EmulatorProcess.java          runs the emulators in a child JVM
  TestLogger.java, Sockets.java log file per run; how TCP connections are opened (tests replace it)
  Main.java                     starts the emulators and reads each one once
  examples/RunTests.java        using the framework from Java
  tools/CalibrateChecks.java    derives the expected values and check limits by simulation
src/test/java/ammeter/          the framework's own tests (TestSupport has shared test servers and data)
src/test/resources/golden/      saved copies of the reports
docs/DESIGN.md
sample_results/                 the sample scenarios (README.md; output of real runs)
```

## Differences from the Python version

- **Error class names.** `AmmeterConnectionException`, `AmmeterTimeoutException` and `AmmeterResponseException`
  instead of `*Error`. Error counts in reports read, for example, `AmmeterTimeoutException x1`.
- **Random numbers** come from `java.util.Random`. A seeded run repeats in Java, but gives other values than the Python
  version with the same seed. The golden report files were regenerated.
- **Emulator process.** The emulators run in a child JVM (`ammeter.emulators.Emulators`) instead of
  `python -m src.emulators`. The options go through stdin, and the child exits when the parent exits.
- **`run.json`** has `java` and `platform` in the `environment` field instead of `python` and `platform`.
- **Charts** are drawn with XChart instead of matplotlib. The files and their content are the same. There is no
  optional dependency, so charts are always available.
- **Ctrl-C** during `run` is handled by a signal handler that interrupts the run. The partial run is kept, with status
  INTERRUPTED. A second Ctrl-C exits at once with code 130.
- **`CalibrateChecks`** uses `SplittableRandom` instead of numpy.
