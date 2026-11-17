## greenlee: PASS

Run `20261116T175120Z_greenlee_62891c`, 127.0.0.1:5001, started 2026-11-16T17:51:20.524+00:00
400 valid readings of 400 planned, 0 failed, 0 missed (their time slot had passed)

| Metric | Value |
|---|---|
| Mean | 0.4618 A |
| Median | 0.09907 A |
| Std deviation | 3.492 A |
| Min | 0.01147 A |
| Max | 67.78 A |
| CV (std / mean) | 7.563 |
| Geometric mean | 0.1191 A |
| 95% CI of the mean | 0.2311 to 0.8184 A |
| Outliers (1.5 IQR rule) | 47 |

| Timing and stability | Value |
|---|---|
| Sampling rate | 40 Hz (requested 40 Hz), 400 of 400 readings taken |
| Interval jitter (std) | 2.33 ms |
| Max delay vs schedule | 5.094 ms |
| Response time mean / p95 / max | 1.316 / 1.849 / 3.525 ms |
| Drift over the run | -5.41 % (t = -0.04) |

| Check | Result | Detail |
|---|---|---|
| values_in_range | PASS | 0 of 400 outside [0.01, 100] A |
| error_rate | PASS | 0.0% failed (limit 5%) |
| sampling_rate | PASS | 400 of 400 planned readings taken, 40 Hz for 40 Hz requested (0.0% short, limit 10%) |
| not_stuck | PASS | longest run of identical readings: 1 (stuck from 3) |
| matches_model | PASS | geometric mean 0.1191 A, model 0.1283 A (z = -1.31, limit ±5) |
| no_drift | PASS | -5.41 % over the run (t = -0.04, limit ±5) |
