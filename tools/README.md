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
   `northOffsetDeg`, `northReferenceAtS`), software vs hardware step counts, stride model, heading
   axis and offsets, barometer reference, the climbs whose height change reaches the path
   (`baroClimbs`, `baroClimbsHeld`, `baroHeldM`, `baroLimitM`, `baroClimbTimesS`), loop closure).
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
| `pdr/AltitudeTrack` | `AltitudeTrack.from_baro/at/mean_over` |
| `pdr/Climbs` | `Climbs.detect/changes/first_step/last_step`, `Climbs.Run.of/taken/jump_shaped/is_jump_against/typical_step` (same runs and trimming, no climb from a jump-shaped run, the way-back rule with its jump test against the climb's median step, the `baroMaxHeldM` band per path segment in `changes` and over the whole trip for `baroLimitM`; no pauses, see "Not covered") |
| `pdr/PdrSolver`, `PdrContext`, `HeadingSegment` | `PdrSolver.prepare/solve_segment/solve/altitude_delta/add_climb_diagnostics/build_headings/axis_for` (`max_of_zero` is Kotlin's `maxOf(0.0, baroMaxHeldM)`), `PdrContext`, `HeadingSegment.walking_heading` |
| `pdr/Diag` | `Diag.num` (rounds like Java's `String.format`: the shortest decimal form, half up, so 14.95 prints 15.0 as in Kotlin) |
| `pdr/PdrProcessor` | `PdrProcessor.process` (keeps `context`, `raw_points`, `closed_points` for plots) |
| `post/LoopClosure`, `Smoothing`, `PathBuilder` | `LoopClosure.apply`, `Smoothing.moving_average`, `PathBuilder.build/stats/nearest_index/...` |

Sequential filters (Madgwick, the step band-pass, the barometric low-pass, the yaw-correction
average and the carry-change low-pass) are plain Python loops that follow the Kotlin code line by line; everything else is
vectorised with numpy. When it was written, the replay reproduced the Kotlin `PdrProcessor` to
floating-point rounding on the samples, the Madgwick fallback (a log without rotation vectors),
the fused-only source, hardware steps with the Weinberg stride, and a REORIENT annotation.

The Kotlin pipeline has since gained the features listed under "Not covered", so parity is now
close but not exact. Measured on 2026-09-29 against `DefaultProcessor` (pipeline version 6):

- `synthetic_square.imul`: 30 steps and distance 20.0059 m in both, closure error 0.7523 m in
  both.
- `synthetic_carry_change.imul`: 30 steps and distance 19.9943 m in both.
- The samples' `LogMeta` predates `baroConfirmSteps` and `baroMaxHeldM`, so both run with the
  defaults (4 steps of 2 cm, 1.5 m). Both implementations report `baroClimbs` 0,
  `baroClimbsHeld` 0, `baroHeldM` 0.00 and `baroLimitM` 0.00 and no `baroClimbTimesS`: no run of
  the flat walk's barometer noise has four steps of 2 cm, none moves the height by the 0.25 m
  that `baroClimbsHeld` counts, and the barometer never gets 1.5 m from the path. z is 0 at every
  point, and x and y match to 2e-15 m.
- Four scratch logs from `SyntheticWalk` (not committed) cover the climb filter:
  - The climb log goes up a flight of 18 stairs (3 m), meets a 10 Pa pressure zone at 30-40 s on
    the upper floor, a door pulse at 45-47 s on a 12 % ramp down, then a 5 % ramp up and a drop
    of 0.6 m over 3 steps near the end (165 steps, 110 m).
  - The wobble log is a walk like the one in `PdrProcessorTest.wobbleOnFlatGroundStaysBounded`:
    4 Pa (0.34 m) of pressure wobble with a 5 s period on a flat 120 m walk (180 steps).
  - The slope log is a 3 % slope over 200 m rising 6 m (315 steps, 210 m), like the slope in
    `PdrProcessorTest.slowWobbleAndGentleSlopesStayWithinTheLimit`.
  - The door log climbs 9 stairs up 1.5 m and walks 3 steps down 0.5 m. From 13.5 s, on the flat
    walk after them, the pressure is 10 Pa higher, as in a zone held at a higher pressure, which
    reads as 0.84 m down (57 steps, 38 m).
- All six logs were compared in six configs: the default, `baroConfirmSteps` 0, 2 and 8,
  `baroConfirmStepM` 0.05 and `baroMaxHeldM` 0.5. With the filter on (every config but
  `baroConfirmSteps` 0), x and y match to 2e-15 m and `baroClimbs`, `baroClimbsHeld`,
  `baroHeldM`, `baroLimitM` and `baroClimbTimesS` are equal, in the same key order. z matches
  exactly in `points` and `rawPoints` on five logs. On the slope log it differs by up to 3.1e-13 m,
  because Java's `Math.pow` and the C runtime's `pow` that Python's `**` calls (measured on
  Windows) round 4 of its 4451 pressure samples one unit in the last place apart (4.9e-12 m of
  height). The climbs counted include the ways back, runs taken because they bring the height
  back after the run taken before them, which the wobble log exercises most. "Held" gives
  `baroClimbsHeld` with `baroHeldM`, the sizes of those runs added up; "limit" gives `baroLimitM`
  where it is not 0.00:

  | Log | Default | `baroConfirmSteps` 2 | `baroConfirmSteps` 8 | `baroConfirmStepM` 0.05 | `baroMaxHeldM` 0.5 |
  |---|---|---|---|---|---|
  | each sample | 0 climbs, 0 held | 2 climbs (1 way back), 0 held | as default | as default | as default |
  | climb | 4 climbs, 4 held (2.58 m) | 6 climbs, 3 held (2.02 m) | 3 climbs, 5 held (3.37 m) | 4 climbs, 4 held (2.58 m) | 4 climbs, 4 held (2.58 m), limit 0.33 m |
  | wobble | 39 climbs, 1 held (0.53 m) | as default | 0 climbs, 40 held (25.90 m) | 38 climbs, 2 held (1.13 m) | 39 climbs, 1 held (0.53 m), limit 0.03 m |
  | slope | 5 climbs, 0 held, limit 3.39 m | 32 climbs, 0 held, limit 0.27 m | 0 climbs, 2 held (0.56 m), limit 4.55 m | as `baroConfirmSteps` 8 | 5 climbs, 0 held, limit 4.39 m |
  | door | 2 climbs, 0 held | as default | 2 climbs (1 way back), 0 held | as default | as default |

  On the slope log the limit decides the end height. The barometer's own height (the step means)
  peaks at about 6.05 m and ends at about 6.02 m. The path stays `baroMaxHeldM` below the peak:
  it ends at 4.55 m, 1.5 m below the peak, by default and with `baroConfirmSteps` 2 or 8 or
  `baroConfirmStepM` 0.05, and at 5.55 m, 0.5 m below it, with `baroMaxHeldM` 0.5. The
  barometer's last few centimetres down are held out.

  On the door log the zone shows the documented limit that a pressure change the same way as a
  climb, right before or after it, joins the climb. In every config with the filter on, the
  flight up is one climb (6.1-11.6 s, 1.46 m) and the way down another, which runs on into the
  zone (11.6-14.4 s) and takes 1.33 m instead of 0.5 m. So the path ends at 0.13 m instead of
  1.0 m. With `baroConfirmSteps` 8 the way down has too few large changes to be a climb and is
  taken as the way back from the flight.
- With `baroConfirmSteps` 0 (the filter off, every change taken through the low-pass and the
  hold, as before version 6), z differs because of the barometer still-gap below. On all six
  logs the first step comes 2.17 s after the start (2.16 s on the slope log, 2.18 s on the door
  log), a gap the replay freezes and Kotlin does not (it is under `baroStillGapS`, 4 s), and no
  step gap after the first step is longer than about 0.56 s. So `rawPoints` z differs by a
  constant from the first step on: the low-passed height at that step, 2.32 mm on both samples
  and the climb log, 5.80 cm on the wobble log, whose pressure already swings during the first
  2 s, and 3.10 cm on the slope log, all below the start, and 1.85 cm above it on the door log,
  whose barometer noise has another seed. In `points` the moving average gives the first step two
  thirds of it (1.55 mm, 3.87 cm, 2.07 cm, 1.23 cm) and every later point all of it, except on
  the square, whose loop closure spreads the difference so that it is at most 2.17 mm and 0 at the
  end. The closure spreads the end error by 3D path length, so the square's closed x and y also
  differ, by up to 1.1e-7 m; in `rawPoints` they match to 5e-16 m. The five climb diagnostics are
  absent in both.
- Both samples report `northReference` "magnetic" in both. Their game and fused rotation vectors
  agree at the start, so the turn onto north is tiny (`northOffsetDeg` -0.1 and 0.0).
- At pipeline version 5, scratch logs whose game vector starts 40° off
  (`SyntheticWalk(gameYawOffsetRad = ...)`, not committed) were compared with every combination
  of `useMagnetometer` and `northFromCompass`, with and without magnetometer samples: x and y
  match to 1e-15 m, and the diagnostics have the same keys, values and order apart from
  `headingAxisMode`.
- At pipeline version 5, scratch logs whose magnetometer is scattered for the first 0.5, 2 or
  3.5 s (readings alternately 1.6 and 0.4 times the field, so the reference second starts at the
  first reading that passes the gate and `northReferenceAtS` appears from 2 s on) matched the same
  way in four configs: x and y to 1e-14 m, and the same diagnostics apart from `headingAxisMode`.
  Their z differed by up to 2.8 cm because of the barometer still-gap below.
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
- `Climbs.changes` centres the `baroMaxHeldM` band where a path segment starts and gives the step
  whose interval the start cuts into its share of the change, which in Kotlin only a VIO gap
  (`PdrSolver.solveSegment` from `VioProcessor`) uses. The replay ports it, but solves every trip
  as one segment from the start, so the parity check does not exercise that part.
- Trip pauses: Kotlin drops the steps and the barometric change between `PAUSE` and `RESUME`.
  In `Climbs`, a step interval that touches a pause gives no height change, ends every run, keeps
  a run after the pause from counting as the way back from a climb before it, and leaves the
  paused change out of the barometer's own height that `baroMaxHeldM` measures against. The
  replay keeps the steps and the change, and its `Climbs` treats no interval as paused.
- Barometer still-gap, only with `baroConfirmSteps` 0: Kotlin freezes the altitude only in the
  middle of step gaps longer than `baroStillGapS` (default 4 s), keeping 3 × `baroSmoothingS` at
  each end. The replay uses a fixed 2 s gap and freezes the whole gap. With the filter on (the
  default), neither implementation uses the low-pass or the hold for z, so the gap makes no
  difference.
- Hardware-step fallback: when the software detector finds no steps (no or stalled
  accelerometer), Kotlin falls back to the hardware step detector. The replay returns 0 steps
  unless you pass `--set preferHardwareSteps=true`.
- `CONFIG_DEFAULTS` has no `headingAxis` or `baroStillGapS`. Their values in `LogMeta` are
  dropped, `--set` rejects them, and a calibrated `headingAxis` is treated as `AUTO`.
- Memory: Python loads the whole file, including the uncalibrated streams that the app skips, so
  a long log at 500 Hz means millions of records and a lot of memory.

## samples/

See `samples/README.md` for the reference logs and how they were generated.
