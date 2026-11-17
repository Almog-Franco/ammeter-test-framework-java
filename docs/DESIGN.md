# Design decisions

This is the design of the Java port. The reasoning is the same as in the Python version; names, libraries and
platform notes are the Java ones. Where the Python design has no direct Java counterpart, the text says what the Java
port does instead.

## Overview

```
config.yaml ─► Settings ─► AmmeterTestFramework.runTest("entes")
                              │
                              ├─ EmulatorProcess  start missing emulators in a child JVM
                              ├─ AmmeterClient    one TCP request per reading, typed errors
                              ├─ Sampler          one time slot per reading, run ends on time
                              ├─ Analysis         statistics, timing, checks, verdict
                              ├─ ResultStore      results/runs/<run_id>/ + index.jsonl
                              └─ Reports, Plots
```

All classes are in the package `ammeter`, except the emulators (`ammeter.emulators`), the example
(`ammeter.examples`) and the tool (`ammeter.tools`).

| Class | Responsibility |
|---|---|
| `Settings`, `AmmeterSpec`, `Acceptance`, `SamplingPlan` | `Settings.load()` reads `config.yaml` into records and validates it, reporting all errors at once |
| `AmmeterClient` | `measure()`, the same interface for every ammeter type |
| `SamplingPlan`, `Sampler`, `Sample` | The plan (count / duration / frequency), the scheduler, and one reading |
| `Analysis` (with `Statistics`, `Timing`, `Check`, `Comparison`, `Status`) | Statistics, timing and drift, acceptance checks, verdict, comparison |
| `Run`, `ResultStore`, `Json` | What is recorded about a run, and the results archive: save, list, load |
| `Reports`, `Plots`, `Fmt` | Markdown reports (also used for the console), charts, number formatting |
| `EmulatorProcess` | Runs the emulators in a child JVM |
| `ammeter.emulators.Emulators`, `FaultyAmmeter` | The emulator registry and the child process's entry point; error simulation |
| `AmmeterTestFramework` | Ties everything together |
| `Cli` | The command line |
| `ammeter.tools.CalibrateChecks` | Derives the expected values and check limits by simulation |
| `TestLogger`, `Sockets` | The log file of a run; how TCP connections are opened (tests replace it) |

## The emulators are treated as devices

The framework talks to the emulators only over TCP, with their real protocol. It never calls `measureCurrent()`
directly, because that would skip the data-transfer link between ammeter and computer in the setup diagram, and
with it every error the framework is supposed to handle.

When the framework starts emulators itself, it starts them in a **separate process**: a child JVM running
`ammeter.emulators.Emulators` with the parent's class path. That is closer to a real device, the framework can stop
them cleanly, and their constant output goes to `results/logs/emulators.log` instead of mixing with the report.
Before starting an emulator the framework checks its port:

- a valid reading means it is already running (for example from `ammeter.Main`), so it is reused
- connection refused means it gets started
- anything else means another program owns the port, and the run stops with a clear error

The options (which emulators, ports, faults, seed) are sent to the child as JSON on stdin, because Windows mangles
quotes in command-line arguments. The child stops when its parent stops, even when the parent was killed: it watches
the parent process and exits when it ends. The parent also stops the child in a shutdown hook and in
`AmmeterTestFramework.close()`.

A client that resets or drops its connection no longer stops an emulator (see fix 14 in
[FIXES.md](https://github.com/Almog-Franco/ammeter-test-framework/blob/main/docs/FIXES.md) in the Python repo). In `AmmeterEmulator.startServer()` only an `IOException` while handling
one connection is survived. Any other error stops the emulator thread, so a real bug is not hidden.

## Configuration

`config.yaml` is the only place that has ports, commands, sampling and limits. It is the same file as in the Python
version. It is parsed with SnakeYAML's `SafeConstructor` and checked before anything starts. Every problem is listed
with its key name, instead of failing on the first problem or on a `null` somewhere in the middle of a run. The
problems are reported together as one `ConfigException`. The checks cover:

- **Structure:** every section must be `key: value` pairs. A list or a word where a section belongs is reported,
  not left to crash later.
- **Numbers:** they must be finite and in range, so `.inf` and `.nan` are rejected.
- **Switches:** they must be real `true`/`false`. The text `"false"` would otherwise count as on.
- **Field types:** ports, hosts, commands, emulator names and seeds. An ammeter name may only use letters, digits, `_`
  and `-`, because it becomes part of run ids and folder names. Two ammeters can't share a host and port.
- **Expected values:** min < max, the means lie between them, and `geo_mean` and `log_std` come as a pair.

`SettingsTest.wrongTypesAnywhereGiveAConfigErrorNeverACrash` sets every key of the shipped config to a list, a word, a
number, a boolean, infinity, a section and null. In every case it expects either a valid config or a `ConfigException`,
never another exception.

The command line can override sampling, profile, fault injection, the emulator seed, plots and the results folder
(`Settings.Overrides`). To add an ammeter, add an entry to `config.yaml`. For a new emulator type, also add its
class to `Emulators.EMULATORS`. A real device is added with
`emulator: null`, so the framework only connects to it.

Relative paths in the config are taken from the project folder, so a run gives the same result from any working
folder. The project folder is found, in this order, from the `ammeter.home` system property, from the first folder
above the jar or classes that has `config/config.yaml`, or else the current folder. Paths given on the command line
(`--config`, `--results-dir`) are taken from the current folder, as usual for command-line arguments. A relative
config path that doesn't exist in the current folder (such as the default `config/config.yaml`) is looked up in the
project folder.

## Sampling

- **Count, duration and frequency depend on each other** (count = duration × frequency), so the config sets any two
  and the third is calculated (`SamplingPlan.fromConfig`). If all three are given, they must agree (within 1%).
- **One time slot per reading:** reading *i* belongs to `[start + i/f, start + (i+1)/f)`, measured with
  `System.nanoTime()` (a monotonic clock). It starts at the beginning of its slot, or right after the previous reading
  if that one ran late. Sleeping `1/f` after every reading would add each reading's own time on top, and the run would
  drift.
- **Missed slots and the deadline:**
  - If a reading takes so long that the next slots have already passed, those slots are **skipped and counted as
    missed**. They are not retried, so there are no catch-up bursts.
  - No reading starts after the planned duration, because the last slot ends at count / f. A reading that is still in
    progress may finish, but only within the client's timeout. So a run never takes longer than its duration plus one
    timeout.
  - Example: "1 s at 10 Hz" against an ammeter that needs 1 s per answer gives one reading and nine missed slots, and
    is done after 1 s.
- **Timing is measured, not assumed.** Every reading records when it started (`time_s`), how late it was within its
  slot (`lag_s`) and how long it took (`latency_s`). The report shows the achieved rate, interval jitter, delay and
  response times, and the `sampling_rate` check fails when more than 10% of the planned readings didn't happen.
- **A failed reading is data, not a crash.** An `AmmeterException` is stored with its class name and message, and the
  run continues. Any other exception is a bug and is not caught. After 5 failures in a row the device is clearly gone
  and the run stops (0 means never stop early); that run gets the verdict FAIL.
- **Ctrl-C** interrupts the main thread. The sampler checks the interrupt flag after every reading and while sleeping,
  and ends the run with stop reason `interrupted`. What was collected is kept and archived, as `INTERRUPTED`. A blocking
  socket read can't be interrupted, so the reading in progress finishes first, within the timeout. A second Ctrl-C ends
  the program at once with exit code 130.

## Talking to the ammeter

`AmmeterClient.measure()` sends the command over a fresh TCP connection and reads until the ammeter closes it.

- **The timeout covers the whole reading:** connect, send, the complete answer and any retries. The client works with
  one deadline and sets the socket timeout to the time that is left before each read. An ammeter that sends one byte at
  a time, or keeps dropping the connection, can't keep the client waiting beyond it.
- **Answers longer than 1024 bytes are rejected.** A reading is a short number.
- **Only plain decimal numbers count as readings,** such as `12.5`, `-3` or `1e-3`. The text is first matched against
  a number pattern and only then parsed. Java's `Double.parseDouble` alone would also accept `NaN`, `Infinity`, `1.5f`
  and `0x1p3`, which no ammeter sends. A value that overflows to infinity (`1e999`) is rejected as not finite.
- **Typed errors** (all subclasses of `AmmeterException`):
  - `AmmeterConnectionException`: nothing listening, the connection attempt timed out, or the connection was reset.
    Retried once by default, within the same timeout.
  - `AmmeterTimeoutException`: no complete answer within the timeout. Not retried, because a slow device stays slow.
  - `AmmeterResponseException`: empty, not a number, not finite, or too long.

## Statistics

- **Plain Java, no statistics library.** `Analysis` computes the mean, median, sample standard deviation (n−1), min
  and max. Also CV, geometric mean, 95% confidence intervals of the mean and of the CV, quartiles and outliers. Sums use
  Neumaier's compensated summation, which is as exact as Python's `math.fsum` for this data. Quartiles use the same
  "inclusive" method as Python's `statistics.quantiles`.
- **Bootstrap confidence intervals:** 1000 resamples with `new Random(0)`, so a report is repeatable. Greenlee's
  readings have a long tail, and `mean ± 1.96·SE` gave an interval reaching below zero. A current can't be negative.
- **Outliers are counted, not removed** (1.5 IQR rule). For Greenlee they are real readings from the tail, and dropping
  them would bias the mean.

## What the emulators should produce

The emulators don't measure a shared current, and their ranges differ by about 1000×. What is known is
the physics and the input ranges of each emulator, and from those the possible range and the expected distribution
follow. These are the `expected` values in `config.yaml`:

| Ammeter | Formula | Inputs | Range | Mean | CV | Geometric mean | Std of log |
|---|---|---|---|---|---|---|---|
| Greenlee | I = V / R | V 1–10 V, R 0.1–100 Ω | 0.01–100 A | 0.3803 A | 4.96 | 0.1283 A | 1.139 |
| ENTES | I = B · K | B 0.01–0.1 T, K 500–2000 | 5–200 A | 68.75 A | 0.61 | 55.51 A | 0.701 |
| CIRCUTOR | I = Δt · ΣVᵢ | 10 × V 0.1–1 V, Δt 1–10 ms | 0.001–0.1 A | 0.03025 A | 0.50 | 0.02583 A | 0.607 |

- **The means follow on paper.** All inputs are uniform and independent, so E[V/R] = E[V]·E[1/R], with
  E[1/R] = ln(100/0.1)/(100 − 0.1).
- **Greenlee's long tail:** 1/R explodes near R = 0.1 Ω, so its mean is about three times its median and its CV is
  about 5.
- **The log-scale values are simulated.** They come from 4 million simulated readings per ammeter in
  `ammeter.tools.CalibrateChecks`, which simulates the emulators' physics independently of the emulator code.
  It uses `SplittableRandom` with a fixed seed.
- **The values are tested against the emulators.** `EmulatorsTest.expectedValuesInConfigMatchTheEmulatorPhysics`
  checks the minimum, maximum and mean against the formulas (also with every input at its bounds), and the CV,
  geometric mean and log spread against 20,000 readings from each emulator.
- **The CIRCUTOR Rogowski formula is incomplete.** `I = ∫V dt` leaves out the coil constant: a Rogowski coil gives
  V = M·dI/dt, so I = (1/M)∫V dt. The emulator uses the formula as written, so its "current" is really in V·s. It is
  kept, because the goal is to test the existing emulators, not to correct them.

## Acceptance checks and verdict

Each run gets checks that come from the requirements and the physics, not from the data itself:

| Check | Fails when |
|---|---|
| `values_in_range` | Any reading is outside the physically possible range |
| `error_rate` | More than 5% of readings failed |
| `sampling_rate` | More than 10% of the planned readings didn't happen (missed slots) |
| `not_stuck` | The same reading comes 3 or more times in a row |
| `matches_model` | The mean of log(readings) is more than 5 standard errors from the model (needs 10 readings) |
| `no_drift` | The readings trend over the run: slope t-statistic ≥ 5, or an exact sloped line (needs 30 readings) |

The limits are the defaults of `testing.acceptance` in the config (`Acceptance`). A check without enough readings is
reported as `SKIP`, never as a silent pass. The verdict (`Analysis.verdict`) is one of:
- `PASS`: every check passed, and the run was not stopped early
- `FAIL`: at least one check failed, or the run stopped after too many failed readings in a row
- `ERROR`: there wasn't a single valid reading
- `INTERRUPTED`: the run was stopped with Ctrl-C

**Why `not_stuck` works here:** the emulators return random values with about 15 significant digits, so identical
readings in a row mean a frozen sensor. A real meter with coarse resolution would need a higher `stuck_after`.

**Why the model check uses logarithms:** Greenlee's readings span four decades, so a check on the plain mean would
raise false alarms. On a log scale all three ammeters are close to normal. The check catches readings that are stuck
or have a calibration error, even when they stay within the range.

### How the limits were chosen

`CalibrateChecks` simulated 200,000 clean runs per case (the default of `--runs`). The false-alarm and detection
figures below come from the original calibration of the Python version. They depend on the physics and the limits, not
on the language, and the Java tool measures the same things. Most of the table is "share of runs that falsely failed";
the last two rows are detection rates:

| | Model check (\|z\| > 5) | Drift check (\|t\| ≥ 5) |
|---|---|---|
| False alarms, 10 readings | ≤ 4 in 100,000 | up to 1 in 1,000, so the check needs 30 readings |
| False alarms, 30 readings | ≤ 1 in 100,000 | ≤ 5 in 100,000 |
| False alarms, 50+ readings | ≤ 1 in 100,000 | ≤ 1 in 100,000 |
| Caught: ×0.5 calibration error, 50 readings | ENTES 98%, CIRCUTOR 100%, Greenlee 24% | - |
| Caught: exact straight line | - | always |

The checks have real limits, and the reports and docs say so:

- **A 20% calibration error can't be detected with 50 readings** (≤ 1%). The readings vary by 50–60% each, so the
  error hides in that spread. Catching it needs a few hundred readings.
- **A gradual drift buried in that spread is rarely caught.** For example, +100% over 50 readings is caught in 0–2% of
  runs. The drift check is meant for clear trends, like a sensor ramping or an exact line.

## Comparing the ammeters

**What it can and can't say:**
- **It compares the emulators, not the methods.** Their spread comes from the random input ranges in their code,
  not from how good Ohm's law, the Hall effect or a Rogowski coil is as a measurement method.
- **Method accuracy needs a shared reference.** It would take every meter measuring the same known current (for
  example from a calibrated source) and comparing each reading against it. These emulators can't do that, because
  each one generates its own current.
- **So the claim is narrow:** which emulator gave the most repeatable readings while also behaving the way its own
  specification says.

**How it works** (`Analysis.compare`; all ammeter names are checked before any run starts, so a typo doesn't waste the
others' runs):

1. **Only runs that passed every check, with at least 30 readings, take part.** The limit is
   `analysis.min_readings_to_rank`. The others are listed as "not ranked", with the reason. Range, errors, agreement
   with the model and drift are already pass/fail checks, so a failed run, or one with a single reading (whose CV is
   0), can't win.
2. **The rest are ranked by precision: the CV, with its bootstrap 95% interval.** CV is relative, so ranges 1000×
   apart compare fairly.
3. **A winner is named only if its CV interval doesn't overlap any other run's.** Otherwise the result is
   *insufficient evidence*, with the names of the runs that are tied. The same happens with fewer than two runs that
   qualify.

The physics predicts CIRCUTOR (CV 0.50) ahead of ENTES (0.61), with Greenlee (≈5) far behind. How many readings it
takes to show that, from 20 simulated comparisons each (measured with the Python version; the Java comparison uses the
same rule):

| Readings per ammeter | 50 | 200 | 400 | 800 |
|---|---|---|---|---|
| CIRCUTOR named the winner | 2 of 20 | 11 of 20 | 19 of 20 | 20 of 20 |
| Wrong winner | 0 | 0 | 0 | 0 |

With the default 50 readings the honest result is usually "CIRCUTOR and ENTES are tied, Greenlee is clearly less
precise". The 400-reading sample scenario in [../sample_results/](../sample_results/README.md) shows CIRCUTOR winning
clearly.

## Results archive

```
results/
  index.jsonl                      one line per run, for listing and filtering
  runs/<run_id>/run.json           settings, config, statistics, timing, checks, verdict
  runs/<run_id>/samples.csv        every reading: index, time_s, lag_s, latency_s, value, error
  runs/<run_id>/report.md
  runs/<run_id>/plots/*.png
  comparisons/<id>/comparison.{md,json,png}
  logs/<time>_<ammeter>_<id>.log, logs/emulators.log
  history_<ammeter>.png            written by the history command
```

- **Run IDs** look like `20261026T120623Z_greenlee_d05946`: UTC time, ammeter and a random part (6 hex digits of a
  UUID). They are unique and sort by time.
- **What `run.json` stores** (JSON with snake_case keys, written with Jackson):
  - `settings`: the **effective** settings after command-line overrides, including timeouts, retries, every
    acceptance limit, the profile and the seeds
  - `config.content`: the **full content** of the config file, plus its path and SHA-256
  - timestamps, and in `environment` the Java version (`java`) and the OS (`platform`)

  A run can be traced and repeated even after the config file has changed.
- **Writes can't be half done:** files are written to a temporary name and then renamed (`ATOMIC_MOVE`), and the index
  line is added only after the run's files exist.
- **Retrieval:** `ResultStore.listRuns/load/loadSamples/latest`, `AmmeterTestFramework.compareRuns`, and the `list`,
  `show`, `compare` and `history` commands.
- **Bad input to the archive gives a clear `ArchiveException`, not a stack trace:**
  - A run id must be letters, digits, `_` and `-`, so `show ../../x` can't read files outside the archive.
  - A damaged `run.json` or `samples.csv` is reported as damaged.
  - Index lines that aren't complete entries are skipped.
  - The command line prints `error: <message>` and exits with code 2.

### Repeating a run exactly

`--seed N` (or `testing.emulator_seed`) seeds the emulators' readings.

- **Why the framework does the seeding:** all emulators draw their random inputs from one shared generator,
  `Utils.generateRandomFloat`. The framework seeds it in the emulator process (`Utils.seed`, called from
  `Emulators.serve`) after creating the emulators and before the first reading, without changing the emulator code.
  In Python the emulators reseed the shared generator with the clock whenever one is created, which is why the seeding
  has to happen after creation. The Java generator is not reseeded on creation, but the framework keeps the same order.
- **Limits:**
  - **Same command, same readings.** All emulators in the process draw from the one generator, in the order the runs
    happen. Running `entes` alone gives different readings than `entes` as part of all three.
  - **Only emulators the framework starts can be seeded.** An already-running one is reused unseeded, with a warning.
  - **The timing isn't repeated, just the readings.** With a slow device, the number of missed slots can differ
    between runs.
  - **The values are those of `java.util.Random`.** The same seed gives other readings than the Python version.

`EmulatorsTest.seedsMakeReadingsAndFaultsRepeatable` checks that the same seed gives the same readings and faults,
and other seeds give others.

## Error simulation

`FaultyAmmeter` wraps any emulator and breaks some of its answers at configured, seeded rates:

| Fault | What the emulator does | Expected detection |
|---|---|---|
| `timeout` | Answers after the client gave up | `AmmeterTimeoutException`, missed slots |
| `no_response` | Sends nothing back | `AmmeterResponseException` (empty response) |
| `garbage` | Sends text that is not a number | `AmmeterResponseException` |
| `spike` | Sends a reading a million times too big | `values_in_range` (and usually `matches_model`) |
| `stuck` | Sends the same reading 5 times in a row | `not_stuck` |

- **The emulators themselves stay unchanged.** `FaultyAmmeter` extends `AmmeterEmulator`, passes the command and
  `measureCurrent()` through to the wrapped emulator, and only overrides the method that builds the reply
  (`reply()`).
- **The timeout fault** waits the client timeout plus 1 s (3 s with the default timeout of 2 s) before it answers.
- **Seeds:** the fault sequence uses its own `java.util.Random`. The seed from `fault_injection.seed` is combined with
  the ammeter name (for example `7:entes`) and hashed, so every ammeter has its own sequence, and the same seed always
  gives the same faults.
- In the sample scenario with faults, every injected fault is detected (see
  [../sample_results/README.md](../sample_results/README.md)).

## Cross-platform

- `java.nio.file.Path` paths, and UTF-8 for every file the framework writes or reads.
- `127.0.0.1` instead of `localhost` as the default host. On Windows, `localhost` may be tried as IPv6 first, which can
  add about a second per connection.
- The emulator process gets UTF-8 output (`-Dstdout.encoding=UTF-8`, and its own UTF-8 `PrintStream`s). Greenlee
  prints `Ω`, which would turn into `?` when output is redirected to a file on Windows.
- **A refused connection is slow on Windows.** Connecting to a port nothing listens on fails at once on macOS and
  Linux, but Windows retries for about 2 s first. So a connection attempt that times out counts as "cannot reach",
  not as a slow answer. And before starting its emulators, the framework checks each port with a quick 0.2 s
  connection test (`EmulatorProcess.portIsOpen`), instead of asking for a reading and waiting for the refusal. All
  connections go through `Sockets.connect`, which tests replace. `TestSupport.windowsLoopback()` makes refusals as slow
  as on Windows, so the tests check this on every platform.
- `SO_REUSEADDR` only outside Windows. The tests' connection reset uses `setSoLinger(true, 0)`, which sends a reset
  on every platform.
- Charts are drawn with XChart in headless mode (`java.awt.headless=true`), so no display is needed.
- The logger closes its file so Windows can delete it.
- The Maven wrapper has a `mvnw` script and a `mvnw.cmd`, so the build needs only a JDK on every platform.

## Tests of the framework itself

`./mvnw test` runs the TestNG tests in `src/test/java/ammeter/`. Each test checks one behaviour that can be named in a
sentence:

- **Unit tests** need no network and no real waiting.
  - `SamplerTest` runs the sampler on a fake clock, so timing tests are exact.
  - `SamplerTest.everyReadingStaysInItsOwnSlot` is a property test over 20 seeded runs with random response times: one
    reading per slot, no reading outside its slot, and a slot is only skipped when it really had passed.
  - `AnalysisTest` covers the statistics, timing and drift, every check and its exact limits, the verdict rules and
    the comparison.
- **Contract tests** run the same checks against every configured ammeter, on emulators started on free ports
  (`EmulatorsTest.eachAmmeterAnswersOnlyItsExactCommand`).
- **Reference tests** check the expected values in the config against 20,000 readings from each emulator
  (`EmulatorsTest.expectedValuesInConfigMatchTheEmulatorPhysics`).
- **Robustness tests** use scripted TCP servers (`TestSupport`):
  - slow, dribbling and endless ammeters (`AmmeterClientTest`)
  - connection resets on both sides, with recovery
  - exact retry counts, and a check that sockets are closed
  - an emulator that keeps working after a client reset, but stops on its own bugs (`EmulatorsTest`)
- **Config tests** (`SettingsTest`) set every key to every wrong type, and expect a `ConfigException` rather than a
  crash. They also cover profiles, command-line overrides and config paths.
- **Archive tests** (`ResultStoreTest`) cover saving and loading, listing with filters and broken index lines, unique
  and sortable ids, damaged and unsafe run ids, and atomic writes.
- **Fault tests** (`FaultyAmmeterTest`) check that each injected fault is detected as the right error, and that fault
  rates and seeds behave.
- **A golden-file test** (`ReportsTest.reportsMatchTheSavedCopies`) compares the full run report, comparison report and
  runs table, word for word, with copies saved in `src/test/resources/golden/`. After an intended change to a report,
  `UPDATE_GOLDEN=1 ./mvnw test -Dtest=ReportsTest` saves new copies.
- **End-to-end tests** (`EndToEndTest`) run the framework and the command line against real emulators: a run and its
  archive, `runAll`, fault-injected and stuck runs, a slow ammeter that can't stretch the run, seeded runs that repeat
  exactly, an unreachable ammeter, ports taken by other programs, and `ammeter.Main`.

Random behaviour is seeded wherever a test depends on it, and tolerances are wide enough for the rest, so the suite
gives the same result every run.

## Dependencies

- JDK 17 or newer. Everything not listed here is the JDK.
- SnakeYAML for the config.
- Jackson databind for `run.json`, `comparison.json`, `index.jsonl`, and the child process options.
- XChart for the charts. It is not optional: the charts are always drawn, unless `--no-plots` or the config turns them
  off.
- TestNG for the tests (test scope).
- Maven, through the wrapper, builds the project. The shade plugin builds the runnable jar `target/ammeter.jar` with
  main class `ammeter.Cli`.
- No numeric library. `CalibrateChecks` uses `SplittableRandom` where the Python tool used numpy.

## Limitations

- A reading needs a new TCP connection, as the emulator protocol requires. That puts the practical sampling limit at
  a few hundred Hz on one machine.
- When the framework reuses an emulator that is already running (for example started by `ammeter.Main`), it can't
  inject faults into it or seed it, and logs a warning.
- Small calibration errors and gradual drifts can't be told from the emulators' large random spread with tens of
  readings (see the detection table). More readings help: the needed count grows with the square of the spread
  divided by the error.
- The comparison ranks emulator behaviour, not measurement-method accuracy (see above).
- A reading in progress can't be cancelled by Ctrl-C, because a blocking socket read is not interruptible. The run
  stops after that reading, so at most one timeout later.
