## greenlee: PASS

Run `20261116T175104Z_greenlee_e87b01`, 127.0.0.1:5001, started 2026-11-16T17:51:04.305+00:00
50 valid readings of 50 planned, 0 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 0.465 A |
| Median | 0.09816 A |
| Std deviation | 1.933 A |
| Min | 0.01708 A |
| Max | 13.65 A |
| CV (std / mean) | 4.157 |
| Geometric mean | 0.117 A |
| 95% CI of the mean | 0.1294 to 1.032 A |
| Outliers (1.5 IQR rule) | 6 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 10 Hz (requested 10 Hz), 50 of 50 readings taken |
| Interval jitter (std) | 2.892 ms |
| Max delay vs schedule | 5.711 ms |
| Response time mean / p95 / max | 1.949 / 2.965 / 4.385 ms |
| Drift over the run | -329 % (t = -1.68) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | PASS | 0 of 50 outside [0.01, 100] A |
| error_rate | PASS | 0.0% failed (limit 5%) |
| sampling_rate | PASS | 50 of 50 planned readings taken, 10 Hz for 10 Hz requested (0.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | PASS | geometric mean 0.117 A, model 0.1283 A (z = -0.57, limit ±5) |
| no_drift | PASS | -329 % over the run (t = -1.68, limit ±5) |
