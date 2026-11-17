## circutor: FAIL

Run `20261116T175202Z_circutor_9ce698`, 127.0.0.1:5003, started 2026-11-16T17:52:02.805+00:00
17 valid readings of 50 planned, 5 failed, 28 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 4095 A |
| Median | 0.03597 A |
| Std deviation | 1.219e+04 A |
| Min | 0.007965 A |
| Max | 4.574e+04 A |
| CV (std / mean) | 2.977 |
| Geometric mean | 0.1535 A |
| 95% CI of the mean | 0.03066 to 1.076e+04 A |
| Outliers (1.5 IQR rule) | 2 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 4.4 Hz (requested 10 Hz), 22 of 50 readings taken |
| Interval jitter (std) | 3.47 ms |
| Max delay vs schedule | 9.183 ms |
| Response time mean / p95 / max | 138.3 / 1004 / 2002 ms |
| Drift over the run | -147 % (t = -0.76) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | FAIL | 2 of 17 outside [0.001, 0.1] A |
| error_rate | FAIL | 22.7% failed (limit 5%) |
| sampling_rate | FAIL | 22 of 50 planned readings taken, 4.4 Hz for 10 Hz requested (56.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | FAIL | geometric mean 0.1535 A, model 0.02583 A (z = +12.10, limit ±5) |
| no_drift | SKIP | skipped: needs at least 30 readings |

Errors: AmmeterResponseException x4, AmmeterTimeoutException x1

Fault injection was on: {'rates': {'timeout': 0.02, 'no_response': 0.04, 'garbage': 0.04, 'spike': 0.04, 'stuck': 0.02}, 'seed': 7, 'delay_s': 3.0}
