## entes: PASS

Run `20261116T175130Z_entes_5f86e9`, 127.0.0.1:5002, started 2026-11-16T17:51:30.887+00:00
400 valid readings of 400 planned, 0 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 68.49 A |
| Median | 58.91 A |
| Std deviation | 40.82 A |
| Min | 8.639 A |
| Max | 186.1 A |
| CV (std / mean) | 0.596 |
| Geometric mean | 55.88 A |
| 95% CI of the mean | 64.47 to 72.63 A |
| Outliers (1.5 IQR rule) | 0 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 40 Hz (requested 40 Hz), 400 of 400 readings taken |
| Interval jitter (std) | 2.378 ms |
| Max delay vs schedule | 12.06 ms |
| Response time mean / p95 / max | 0.9566 / 1.241 / 4.594 ms |
| Drift over the run | +1.42 % (t = +0.14) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | PASS | 0 of 400 outside [5, 200] A |
| error_rate | PASS | 0.0% failed (limit 5%) |
| sampling_rate | PASS | 400 of 400 planned readings taken, 40 Hz for 40 Hz requested (0.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | PASS | geometric mean 55.88 A, model 55.51 A (z = +0.19, limit ±5) |
| no_drift | PASS | +1.42 % over the run (t = +0.14, limit ±5) |
