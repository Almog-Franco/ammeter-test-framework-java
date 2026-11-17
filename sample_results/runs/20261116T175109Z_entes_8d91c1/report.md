## entes: PASS

Run `20261116T175109Z_entes_8d91c1`, 127.0.0.1:5002, started 2026-11-16T17:51:09.613+00:00
50 valid readings of 50 planned, 0 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 71.06 A |
| Median | 64.94 A |
| Std deviation | 41.67 A |
| Min | 11.94 A |
| Max | 193.4 A |
| CV (std / mean) | 0.5864 |
| Geometric mean | 58.87 A |
| 95% CI of the mean | 60.13 to 82.27 A |
| Outliers (1.5 IQR rule) | 1 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 10 Hz (requested 10 Hz), 50 of 50 readings taken |
| Interval jitter (std) | 1.748 ms |
| Max delay vs schedule | 5.062 ms |
| Response time mean / p95 / max | 1.599 / 1.839 / 2.033 ms |
| Drift over the run | -2.05 % (t = -0.07) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | PASS | 0 of 50 outside [5, 200] A |
| error_rate | PASS | 0.0% failed (limit 5%) |
| sampling_rate | PASS | 50 of 50 planned readings taken, 10 Hz for 10 Hz requested (0.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | PASS | geometric mean 58.87 A, model 55.51 A (z = +0.59, limit ±5) |
| no_drift | PASS | -2.05 % over the run (t = -0.07, limit ±5) |
