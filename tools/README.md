# tools

Offline helpers for algorithm work. `replay.py` reads a raw `.imul` log, runs the processing
steps of the Kotlin PDR pipeline (`pipeline/.../pdr` and `pipeline/.../post`), and writes the
path, plots and statistics. The log can come from the app or from `tools/samples/`. To get one
from the app, long-press the trip in the trip list and pick **Export as ZIP**. Save or send the ZIP
from the share sheet, then unzip it: `raw.imul` and `results/run-<n>.json` are inside. The tool exists so an algorithm change can
be tried on a recorded log in seconds, plotted, and compared with what the app produced, without
building the app. It lags the Kotlin code in a few places, listed under "Not covered".

## Setup

Python 3.8 or newer with numpy, plus matplotlib for the plots:

```
python3 -m pip install --user -r tools/requirements.txt
```

On Windows use `python` (or `py`) instead of `python3`, which is the Microsoft Store stub there.
Add `-X utf8` when the log or the `--compare` result contains non-ASCII annotation notes. Without
it, `--compare` reads the JSON in the Windows ANSI code page, and redirected output can fail to
encode the notes.

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

By default the config comes from the log's `LogMeta`, which holds the calibration that was in
effect when the trip was recorded. The app processes with the calibration saved at processing
time, so after a recalibration the two differ. To get as close to an app run as the replay can,
pass that run's `config` values from `results/run-<n>.json` with `--set`. Leave out `headingAxis`
and `baroStillGapS`, which `--set` rejects. The differences listed under "Not covered" remain.
`--set` is also the knob for algorithm experiments.

### What it prints

1. **Summary** of the log: record counts per type with the measured rate, duration, annotations,
   events, and the `LogMeta` fields (app, device, mode, carry position, notes). Truncated files and
   unknown record types are reported, not fatal, just like `LogReader`.
2. The **config** in effect.
3. The pipeline **diagnostics** (same keys as `PathResult.diagnostics` from the Kotlin code:
   orientation source, magnetometer gate pass fraction, where north comes from (`northReference`,
   `northOffsetDeg`), software vs hardware step counts, stride model, heading axis and offsets,
   barometer reference, loop closure).
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
| `signals.png` | Four panels over time: vertical acceleration (raw and band-passed) with the detected steps and the hardware step events; device heading and the walking heading of each step (each move of the phone shaded, the heading segment after it starting at the dashed line); pressure-derived height, the low-passed altitude track and the path z; the interval between consecutive steps against `stepMinIntervalS`. Annotations are vertical lines. |

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
| `pdr/HeadingEstimator` (`DeviceHeading`) | `DeviceHeading.choose_axis/heading_rad/mean_heading_rad` |
| `pdr/CarryChangeDetector`, `CarryChange` | `CarryChangeDetector.detect`, `CarryChange` (same 10 Hz sampling, low-pass and settle rule) |
| `pdr/AltitudeTrack` | `AltitudeTrack.from_baro/at` |
| `pdr/PdrSolver`, `PdrContext`, `HeadingSegment` | `PdrSolver.prepare/solve_segment/solve/build_headings/axis_for`, `PdrContext`, `HeadingSegment.walking_heading` |
| `pdr/PdrProcessor` | `PdrProcessor.process` (keeps `context`, `raw_points`, `closed_points` for plots) |
| `post/LoopClosure`, `Smoothing`, `PathBuilder` | `LoopClosure.apply`, `Smoothing.moving_average`, `PathBuilder.build/stats/nearest_index/...` |

Sequential filters (Madgwick, the step band-pass, the barometric low-pass, the yaw-correction
average and the carry-change low-pass) are plain Python loops that follow the Kotlin code line by line; everything else is
vectorised with numpy. When it was written, the replay reproduced the Kotlin `PdrProcessor` to
floating-point rounding on the samples, the Madgwick fallback (a log without rotation vectors),
the fused-only source, hardware steps with the Weinberg stride, and a REORIENT annotation.

The Kotlin pipeline has since gained the features listed under "Not covered", so parity is now
close but not exact. Measured on 2026-09-28 against `DefaultProcessor` (pipeline version 5):

- `synthetic_square.imul`: 30 steps and distance 20.0064 m in both. The closure error is
  0.7525 m in the replay and 0.7526 m in Kotlin.
- `synthetic_carry_change.imul`: 30 steps and distance 19.9948 m in both.
- In both samples x and y match. z differs by up to 2.3 mm, because of the barometer still-gap
  below.
- Both samples report `northReference` "magnetic" in both. Their game and fused rotation vectors
  agree at the start, so the turn onto north is tiny (`northOffsetDeg` -0.1 and 0.0). To check
  the turn itself, scratch logs whose game vector starts 40° off (`SyntheticWalk(gameYawOffsetRad
  = ...)`, not committed) were compared with every combination of `useMagnetometer` and
  `northFromCompass`, with and without magnetometer samples: x and y match to 1e-15 m, and the
  diagnostics have the same keys, values and order apart from `headingAxisMode`.
- `--compare` reports `headingAxisMode` as a diagnostics key that only Kotlin emits.

When you change the Kotlin pipeline, get a Kotlin result and check the replay against it with
`--compare`. Either export one from the app, or add a temporary pipeline test that writes
`DefaultProcessor().process(log, log.meta!!.config).toJson()` for a sample log to a file (no
existing test does this). When they disagree, the Kotlin code is the reference
and `replay.py` is the one to fix. Nothing runs this check automatically: CI does not run
`replay.py`.

### Conventions

World frame ENU (x east, y north, z up, metres), headings in radians clockwise from north in
(-pi, pi], timestamps in nanoseconds of the elapsed-realtime clock, see `docs/CONVENTIONS.md`.

### Not covered

- ARCore poses (`POSE`, `POINT_CLOUD`) are parsed and counted but the VIO fusion
  (`pipeline/.../vio`) is not mirrored; a VIO log is replayed as pure PDR from its IMU stream,
  which is exactly the fallback baseline the app compares against. For a VIO trip, compare with
  the app's "PDR only" run from the Debug screen, not with its default VIO run.
- Trip pauses: Kotlin drops the steps and the barometric change between `PAUSE` and `RESUME`,
  but the replay keeps them.
- Barometer still-gap: Kotlin freezes the altitude only in the middle of step gaps longer than
  `baroStillGapS` (default 4 s). The replay uses a fixed 2 s gap and freezes the whole gap.
- Hardware-step fallback: when the software detector finds no steps (no or stalled
  accelerometer), Kotlin falls back to the hardware step detector. The replay returns 0 steps
  unless you pass `--set preferHardwareSteps=true`.
- `CONFIG_DEFAULTS` has no `headingAxis` or `baroStillGapS`. Their values in `LogMeta` are
  dropped, `--set` rejects them, and a calibrated `headingAxis` is treated as `AUTO`.
- Memory: Python loads the whole file, including the uncalibrated streams that the app skips, so
  a long log at 500 Hz means millions of records and a lot of memory.

## samples/

See `samples/README.md` for the reference logs and how they were generated.
