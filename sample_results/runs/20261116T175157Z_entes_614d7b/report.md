## entes: FAIL

Run `20261116T175157Z_entes_614d7b`, 127.0.0.1:5002, started 2026-11-16T17:51:57.323+00:00
30 valid readings of 50 planned, 5 failed, 15 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 2.195e+06 A |
| Median | 50.5 A |
| Std deviation | 1.202e+07 A |
| Min | 10.13 A |
| Max | 6.585e+07 A |
| CV (std / mean) | 5.477 |
| Geometric mean | 79.66 A |
| 95% CI of the mean | 49.9 to 6.585e+06 A |
| Outliers (1.5 IQR rule) | 2 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 7 Hz (requested 10 Hz), 35 of 50 readings taken |
| Interval jitter (std) | 2.178 ms |
| Max delay vs schedule | 5.054 ms |
| Response time mean / p95 / max | 58.74 / 2.083 / 2002 ms |
| Drift over the run | -296 % (t = -0.90) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | FAIL | 1 of 30 outside [5, 200] A |
| error_rate | FAIL | 14.3% failed (limit 5%) |
| sampling_rate | FAIL | 35 of 50 planned readings taken, 7 Hz for 10 Hz requested (30.0% short, limit 10%) |
| not_stuck | FAIL | longest run of identical readings: 5 (stuck from 3) |
| matches_model | PASS | geometric mean 79.66 A, model 55.51 A (z = +2.82, limit ±5) |
| no_drift | PASS | -296 % over the run (t = -0.90, limit ±5) |

Errors: AmmeterResponseException x4, AmmeterTimeoutException x1

Fault injection was on: {'rates': {'timeout': 0.02, 'no_response': 0.04, 'garbage': 0.04, 'spike': 0.04, 'stuck': 0.02}, 'seed': 7, 'delay_s': 3.0}
