## greenlee: FAIL

Run `20261116T175152Z_greenlee_4c3261`, 127.0.0.1:5001, started 2026-11-16T17:51:52.051+00:00
47 valid readings of 50 planned, 3 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 1.046e+04 A |
| Median | 0.09424 A |
| Std deviation | 5.355e+04 A |
| Min | 0.01708 A |
| Max | 3.549e+05 A |
| CV (std / mean) | 5.12 |
| Geometric mean | 0.2772 A |
| 95% CI of the mean | 0.4885 to 2.682e+04 A |
| Outliers (1.5 IQR rule) | 5 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 10 Hz (requested 10 Hz), 50 of 50 readings taken |
| Interval jitter (std) | 2.363 ms |
| Max delay vs schedule | 5.056 ms |
| Response time mean / p95 / max | 1.88 / 2.615 / 5.532 ms |
| Drift over the run | +152 % (t = +0.60) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | FAIL | 3 of 47 outside [0.01, 100] A |
| error_rate | FAIL | 6.0% failed (limit 5%) |
| sampling_rate | PASS | 50 of 50 planned readings taken, 10 Hz for 10 Hz requested (0.0% short, limit 10%) |
| not_stuck | FAIL | longest run of identical readings: 5 (stuck from 3) |
| matches_model | PASS | geometric mean 0.2772 A, model 0.1283 A (z = +4.64, limit ±5) |
| no_drift | PASS | +152 % over the run (t = +0.60, limit ±5) |

Errors: AmmeterResponseException x3

Fault injection was on: {'rates': {'timeout': 0.02, 'no_response': 0.04, 'garbage': 0.04, 'spike': 0.04, 'stuck': 0.02}, 'seed': 7, 'delay_s': 3.0}
