## circutor: PASS

Run `20261116T175114Z_circutor_5bed0b`, 127.0.0.1:5003, started 2026-11-16T17:51:14.594+00:00
50 valid readings of 50 planned, 0 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 0.02845 A |
| Median | 0.02583 A |
| Std deviation | 0.0144 A |
| Min | 0.006667 A |
| Max | 0.05678 A |
| CV (std / mean) | 0.5062 |
| Geometric mean | 0.02454 A |
| 95% CI of the mean | 0.0247 to 0.03206 A |
| Outliers (1.5 IQR rule) | 0 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 10 Hz (requested 10 Hz), 50 of 50 readings taken |
| Interval jitter (std) | 2.265 ms |
| Max delay vs schedule | 5.096 ms |
| Response time mean / p95 / max | 1.725 / 2.663 / 3.459 ms |
| Drift over the run | -12.1 % (t = -0.49) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | PASS | 0 of 50 outside [0.001, 0.1] A |
| error_rate | PASS | 0.0% failed (limit 5%) |
| sampling_rate | PASS | 50 of 50 planned readings taken, 10 Hz for 10 Hz requested (0.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | PASS | geometric mean 0.02454 A, model 0.02583 A (z = -0.60, limit ±5) |
| no_drift | PASS | -12.1 % over the run (t = -0.49, limit ±5) |
