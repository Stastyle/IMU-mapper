# tools

Offline helpers for algorithm work. `replay.py` reads a raw `.imul` log exported from the app
(Debug screen → export, or a file from `tools/samples/`), runs the same processing steps as the
Kotlin PDR pipeline (`pipeline/.../pdr` and `pipeline/.../post`), and writes the path, plots and
statistics. It exists so an algorithm change can be tried on a recorded log in seconds, plotted,
and compared with what the app produced, without building the app.

## Setup

Python 3.8 or newer with numpy and matplotlib:

```
python3 -m pip install --user -r tools/requirements.txt
```

## Usage

```
python3 tools/replay.py tools/samples/synthetic_square.imul --out /tmp/square
```

Options:

| Option | Meaning |
|---|---|
| `--out DIR` | Output directory (default: `<log name>_replay` next to the log). |
| `--set KEY=VALUE` | Override one `PipelineConfig` field, repeatable, e.g. `--set strideLengthM=0.68 --set weinbergK=0.5 --set gyroBias=0.001,0,0`. Booleans take `true`/`false`. |
| `--defaults` | Ignore the config stored in the log's `LogMeta` and start from `PipelineConfig` defaults (then apply `--set`). |
| `--compare RESULT.json` | Overlay a `PathResult` JSON exported from the app and print how it differs from the replay. |
| `--summary-only` | Only parse the log and print the record summary. |
| `--no-plots` | Skip the PNG files (faster; no matplotlib needed). |
| `--quiet` | Print only the stats lines. |

By default the config comes from the log meta, exactly like the app's processing step, so the
replay reproduces the app's result; `--set` is then the knob for algorithm experiments.

### What it prints

1. **Summary** of the log: record counts per type with the measured rate, duration, annotations,
   events, and the `LogMeta` fields (app, device, mode, carry position, notes). Truncated files and
   unknown record types are reported, not fatal, just like `LogReader`.
2. The **config** in effect.
3. The pipeline **diagnostics** (same keys as `PathResult.diagnostics` from the Kotlin code:
   orientation source, magnetometer gate pass fraction, software vs hardware step counts, stride
   model, heading axis and offsets, barometer reference, loop closure).
4. **Stats**: distance, steps, duration, vertical range, closure error, and the end point.
5. With `--compare`: per-stat differences, end-point distance, and the mean and maximum position
   error over the points that share a timestamp.

### Output files

| File | Content |
|---|---|
| `path.json` | The `PathResult` with the same field names as the Kotlin JSON (`pipelineVersion`, `config`, `points[{tNs, p{x,y,z}, source, headingRad, stepIndex}]`, `annotations`, `keyframes`, `pointCloud`, `stats`, `diagnostics`, `rawPoints`). `rawPoints` is the path before loop closure and smoothing, empty when neither moved anything. |
| `path.csv` | One row per path point: `tNs, x, y, z, source, headingRad, stepIndex`. |
| `path_top.png` | Top-down path (east/north) with start, end, annotations, keyframes, the PDR path before loop closure (dashed) and the `--compare` overlay. |
| `path_side.png` | Side view: z against distance along the path. |
| `signals.png` | Four panels over time: vertical acceleration (raw and band-passed) with the detected steps and the hardware step events; device heading and the walking heading of each step (REORIENT boundaries dashed); pressure-derived height, the low-passed altitude track and the path z; the interval between consecutive steps against `stepMinIntervalS`. Annotations are vertical lines. |

Plots use the headless Agg backend, so the script runs on servers and in CI.

## How it mirrors the Kotlin pipeline

The classes and functions keep the Kotlin names so the two can be read side by side:

| Kotlin | Python |
|---|---|
| `log/LogFormat.kt`, `LogReader`, `RawLog` | `parse_imul`, `RawLog` (numpy arrays per record type) |
| `core/PipelineConfig` | `PipelineConfig` (`CONFIG_DEFAULTS` is the list of fields) |
| `core/Quat` | `quat_*`, `heading_of`, `forward_heading_rad`, `camera_heading_rad`, `euler_zxy_yaw` |
| `pdr/OrientationTrack` | `OrientationTrack` (vectorised `at`, same slerp and clamping) |
| `pdr/OrientationEstimator`, `MagGate`, `MadgwickFilter` | `OrientationEstimator.estimate/correct_yaw/madgwick`, `MagGate`, `MadgwickFilter` |
| `pdr/WorldAccel` | `WorldAccel.compute` |
| `pdr/StepDetector`, `DetectedSteps` | `StepDetector.detect/from_hardware/band_pass/filter`, `DetectedSteps` |
| `pdr/StrideModel` | `StrideModel.stride_m` |
| `pdr/HeadingEstimator` (`DeviceHeading`, `HeadingOffsetEstimator`) | `DeviceHeading.choose_axis/heading_rad`, `HeadingOffsetEstimator.estimate` |
| `pdr/AltitudeTrack` | `AltitudeTrack.from_baro/at` |
| `pdr/PdrSolver`, `PdrContext`, `HeadingSegment` | `PdrSolver.prepare/solve_segment/solve/build_headings`, `PdrContext`, `HeadingSegment` |
| `pdr/PdrProcessor` | `PdrProcessor.process` (keeps `context`, `raw_points`, `closed_points` for plots) |
| `post/LoopClosure`, `Smoothing`, `PathBuilder` | `LoopClosure.apply`, `Smoothing.moving_average`, `PathBuilder.build/stats/nearest_index/...` |

Sequential filters (Madgwick, the step band-pass, the barometric low-pass, the yaw-correction
average) are plain Python loops that follow the Kotlin code line by line; everything else is
vectorised with numpy. On `tools/samples/synthetic_square.imul` the replay reproduces the Kotlin
`PdrProcessor` output to floating-point rounding: 30 steps, distance 20.006 m after closure,
closure error 0.7525 m, every point within 1e-9 m. The same holds for the Madgwick fallback (log
without rotation vectors), the fused-only source, hardware steps with the Weinberg stride, and a
REORIENT annotation. When you change the Kotlin pipeline, export a result from the app (or dump
one from a pipeline test) and check the replay with `--compare`; when they disagree, the Kotlin
code is the reference and `replay.py` is the one to fix.

### Conventions

World frame ENU (x east, y north, z up, metres), headings in radians clockwise from north in
(-pi, pi], timestamps in nanoseconds of the elapsed-realtime clock, see `docs/CONVENTIONS.md`.

### Not covered

- ARCore poses (`POSE`, `POINT_CLOUD`) are parsed and counted but the VIO fusion
  (`pipeline/.../vio`) is not mirrored; a VIO log is replayed as pure PDR from its IMU stream,
  which is exactly the fallback baseline the app compares against.
- Loading a log through Python uses the whole file in memory; a 10-minute log at 500 Hz is a
  few hundred thousand records and takes a few seconds.

## samples/

See `samples/README.md` for the reference logs and how they were generated.
