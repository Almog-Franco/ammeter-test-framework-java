## circutor: PASS

Run `20261116T175141Z_circutor_0c3ae5`, 127.0.0.1:5003, started 2026-11-16T17:51:41.021+00:00
400 valid readings of 400 planned, 0 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 0.03077 A |
| Median | 0.03029 A |
| Std deviation | 0.01526 A |
| Min | 0.003517 A |
| Max | 0.075 A |
| CV (std / mean) | 0.4958 |
| Geometric mean | 0.02625 A |
| 95% CI of the mean | 0.02927 to 0.03231 A |
| Outliers (1.5 IQR rule) | 0 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 40 Hz (requested 40 Hz), 400 of 400 readings taken |
| Interval jitter (std) | 1.768 ms |
| Max delay vs schedule | 5.226 ms |
| Response time mean / p95 / max | 0.9814 / 1.236 / 2.441 ms |
| Drift over the run | -10.7 % (t = -1.25) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | PASS | 0 of 400 outside [0.001, 0.1] A |
| error_rate | PASS | 0.0% failed (limit 5%) |
| sampling_rate | PASS | 400 of 400 planned readings taken, 40 Hz for 40 Hz requested (0.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | PASS | geometric mean 0.02625 A, model 0.02583 A (z = +0.53, limit ±5) |
| no_drift | PASS | -10.7 % over the run (t = -1.25, limit ±5) |
