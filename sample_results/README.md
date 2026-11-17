# Sample results

Three sessions with the Java port. All use the shipped `config/config.yaml` and `--seed 1`, so the readings can be
reproduced with the same Java build (the readings differ from those in the Python version's
[sample_results](https://github.com/Almog-Franco/ammeter-test-framework/tree/main/sample_results), because the random generator is different). The numbers, run ids and
charts are in the files in this folder: see `index.jsonl`, `runs/*/report.md` and `comparisons/*/comparison.md`.

Run these commands in the repository root, after `./mvnw package -DskipTests`:

```sh
java -jar target/ammeter.jar --results-dir sample_results run --seed 1                            # 50 readings at 10 Hz
java -jar target/ammeter.jar --results-dir sample_results run --seed 1 --count 400 --frequency 40 # long run
java -jar target/ammeter.jar --results-dir sample_results run --seed 1 --faults                   # with error simulation
java -jar target/ammeter.jar --results-dir sample_results history entes                           # history_entes.png
```

To look at a run, list the archive and show a run by its id:

```sh
java -jar target/ammeter.jar --results-dir sample_results list
java -jar target/ammeter.jar --results-dir sample_results show <run_id>
```

## 1. Normal run, 50 readings: no single winner

Command: `run --seed 1`. The settings are those of `testing.sampling`: 50 readings at 10 Hz per ammeter.
Comparison: `comparisons/20261116T175119Z_c374da/`.

- All three ammeters PASS: 50 of 50 readings taken on schedule, all six checks pass.
- The comparison names no winner. CIRCUTOR's and ENTES's CV intervals overlap, so the result is "insufficient
  evidence". Greenlee is clearly less precise, because its readings have a long tail.
- The deviations from the model are within the noise of 50 readings (z between -0.6 and +0.6).

| Ammeter | CV (95% CI) | Geo. mean vs model |
|---|---|---|
| circutor | 0.5062 (0.4232 to 0.5781) | -5.0% |
| entes | 0.5864 (0.4737 to 0.6939) | +6.1% |
| greenlee | 4.157 (0.9051 to 4.72) | -8.8% |

## 2. Long run, 400 readings at 40 Hz: CIRCUTOR wins clearly

Command: `run --seed 1 --count 400 --frequency 40`. Comparison: `comparisons/20261116T175151Z_e9ab7c/`.

- All three ammeters PASS with 400 of 400 readings.
- With 400 readings the CV intervals of CIRCUTOR and ENTES no longer overlap, so the comparison names CIRCUTOR as the
  most reliable. The CVs the input ranges predict are 0.50 (CIRCUTOR) and 0.61 (ENTES).
- This ranks the emulators, not the methods. It doesn't mean Rogowski coils measure more accurately than Hall sensors.

| Ammeter | CV (95% CI) | Geo. mean vs model |
|---|---|---|
| circutor | 0.4958 (0.4629 to 0.53) | +1.6% |
| entes | 0.596 (0.5613 to 0.6314) | +0.7% |
| greenlee | 7.563 (1.794 to 8.606) | -7.2% |

## 3. Fault-injected run: every injected fault detected

Command: `run --seed 1 --faults`. The faults and their rates come from `fault_injection` in the config
(`timeout` 2%, `no_response` 4%, `garbage` 4%, `spike` 4%, `stuck` 2%, fault seed 7).
Comparison: `comparisons/20261116T175207Z_30824d/`.

The emulator log `logs/emulators.log` has one `FAULT INJECTED: <fault>` line for each of the 15 injected faults. Each
one shows up in the reports as described in [docs/DESIGN.md](../docs/DESIGN.md#error-simulation):

| Ammeter | Injected faults | Readings | Failed checks |
|---|---|---|---|
| greenlee | 3 spike, 2 no_response, 1 garbage, 1 stuck | 47 valid, 3 failed, 0 missed | values_in_range (3 of 47 outside), error_rate (6.0%), not_stuck (run of 5) |
| entes | 2 garbage, 2 no_response, 1 spike, 1 stuck, 1 timeout | 30 valid, 5 failed, 15 missed | values_in_range (1 of 30), error_rate (14.3%), sampling_rate (35 of 50 taken), not_stuck (run of 5) |
| circutor | 4 garbage, 2 spike, 1 timeout | 17 valid, 5 failed, 28 missed | values_in_range (2 of 17), error_rate (22.7%), sampling_rate (22 of 50 taken), matches_model (z = +12.10) |

- `garbage` and `no_response` give failed readings (`AmmeterResponseException`), `timeout` gives an
  `AmmeterTimeoutException`, a `spike` is a reading outside the expected range, `stuck` is 5 identical readings in a row.
- A timeout stalls the emulator for 3 s. The slots it covers are skipped and counted as missed, instead of stretching
  the run or firing a burst afterwards. The chart `plots/timeseries.png` of those runs shows the gap.
- All three runs FAIL, so there is no winner: fewer than two runs passed every check.
