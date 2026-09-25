# IMU Mapper — Design and Build Plan

Native Android app for the Galaxy S26 Ultra that records a walk (for example through a cave)
using the phone's sensors and shows the route afterwards as a 3D line you can rotate, zoom
and pan on the phone.

## 1. Goals and non-goals

**Goals**

- Record a route with three capture modes, from "phone in pocket, no light" up to
  "camera on, room is lit".
- Show the route as a 3D polyline in the app with orbit / zoom / pan and a floor grid.
- Keep every raw sensor sample so a trip can be re-processed later with a better algorithm.
- Calibration and debug screens so accuracy can be measured and improved over time.
- Typical trip: up to ~5 minutes of walking, sometimes longer with stops.

**Non-goals (for now)**

- Wall / cave geometry meshing. The output is the path, not the walls.
- Sharing or export beyond screenshots. (A GLTF/CSV export is cheap and listed as a later phase.)
- Multi-trip merging, GPS, cloud sync.

## 2. Accuracy reality check

| Method | Works in dark | Typical error | Notes |
|---|---|---|---|
| Raw IMU double integration | yes | unusable after ~20 s | Never used for position. |
| Pedestrian dead reckoning (PDR) | yes | 2–5 % of distance, heading drift over time | Step detection + stride + heading + barometer. |
| ARCore visual-inertial odometry | needs light | ~0.5–1 % of distance | Loses tracking on dark / featureless / wet rock; must fall back to PDR. |

PDR is the floor; ARCore is the ceiling. Every mode records the full IMU stream so PDR
is always available as a fallback and as a baseline to compare against.

## 3. Capture modes

### Mode A — Pocket (IMU only, no light)

- Phone in pocket, hand or chest pocket. Screen off allowed.
- Inputs: accelerometer, gyroscope, magnetometer, barometer, game rotation vector,
  hardware step detector.
- Output: 3D path from PDR. Vertical from barometer.
- Hands-on: big annotation buttons on the recording screen (junction, chamber, waypoint,
  "back at start" for loop closure). Volume-key shortcut for "waypoint" so it works blind.

### Mode B — Flashlight (ARCore + torch)

- Phone held in hand, camera facing forward, torch driven by ARCore
  (`Config.setFlashMode(FlashMode.TORCH)`).
- Inputs: ARCore 6-DoF pose at camera rate, plus the same IMU stream as Mode A.
- When ARCore reports tracking lost (`TrackingState != TRACKING`), the pipeline continues
  from the last good pose with PDR. When tracking resumes, the new ARCore frame is
  re-anchored to the PDR-estimated pose so the path stays continuous.
- Fully hands-off: no annotation buttons needed while walking. Waypoint via volume key
  still available.

### Mode C — Illuminated (ARCore + auto photos)

- Same as Mode B without the torch (or with, user choice).
- Auto-captures a photo keyframe every N metres of travel or on a heading change > X°.
  Each photo is stored with its pose and shows as a marker on the 3D path; tapping a marker
  opens the photo.
- Optionally records ARCore's sparse feature point cloud and renders it as faint dots around
  the path, which gives a crude sense of the walls for free.

Mode selection is a single choice on the "new trip" screen. Mode A is the default.

## 4. Architecture

```
app/
  ui/            Jetpack Compose screens
    TripListScreen        list of recorded trips, new trip, delete
    RecordScreen          per-mode recording UI, annotation buttons, live stats
    ViewerScreen          3D path viewer (orbit/zoom/pan), markers, photo popup
    CalibrationScreen     stride, heading offset, still-bias, loop test
    DebugScreen           live sensor plots, log export, re-process
  capture/
    SensorLogger          writes raw samples to a binary log via a foreground service
    ArCoreSession         ARCore session, torch, pose + point cloud + keyframe capture
    RecordingService      foreground service + wake lock (Mode A survives screen-off)
  pipeline/      pure Kotlin, no Android deps, unit-testable, deterministic
    LogReader             raw log -> typed sample streams
    Orientation           quaternion from rotation vector (+ own Madgwick filter for comparison)
    StepDetector          peak detection on vertical accel; hardware step detector as cross-check
    StrideModel           fixed calibrated stride, then Weinberg (accel-amplitude) model
    HeadingEstimator      gyro yaw fused with magnetometer, with anomaly gating
    Altitude              barometer -> relative height, low-pass, still-segment hold
    PdrSolver             steps + heading + altitude -> 3D positions
    VioFuser              ARCore poses + PDR gap filling + re-anchoring
    LoopClosure           distribute end-to-start error linearly along the path
    PathBuilder           final polyline + annotations + keyframes -> Trip result
  data/
    Room DB               trip metadata, annotations, calibration values
    Files                 raw logs (binary), processed path (JSON), photos (JPEG)
  render/
    PathRenderer          3D projection + drawing on a Compose Canvas
tools/
  replay.py             offline runner: raw log -> path, plots, error metrics (for algorithm work)
```

Key decisions

- **Kotlin + Jetpack Compose, minSdk 30, targetSdk latest.** Single-module app.
- **Raw log first, processing second.** Recording only writes samples. Processing is a
  separate step that can be re-run on any old trip. This is what makes calibration and
  algorithm iteration possible.
- **3D viewer on a Compose Canvas.** A polyline of a few thousand points needs no GPU
  scene graph. A small orbit camera projects 3D points to 2D; the path is drawn with
  depth-sorted segments, a floor grid, a north arrow and start/end markers. This can be
  swapped for Filament/OpenGL later if wall geometry ever appears.
- **ARCore used directly** with a minimal background renderer for the camera preview
  (copied from the ARCore sample) rather than a full scene-graph library.
- **Sensor rates.** IMU at `SENSOR_DELAY_FASTEST` (200–500 Hz on this device), no batching,
  timestamps from the sensor event (monotonic ns). ARCore poses at frame rate.
- **Debug APK built by GitHub Actions** on every push and attached as a workflow artifact,
  so a build can be installed without Android Studio.

## 5. PDR pipeline detail (Mode A and the fallback for B/C)

1. **Orientation.** Use Android's game rotation vector (gyro + accel, no magnetometer) as the
   primary device-to-world orientation. Compute a parallel Madgwick estimate from raw
   uncalibrated gyro + accel for comparison in the debug screen.
2. **Gravity removal.** Rotate accel into world frame; vertical component drives step
   detection; horizontal component is used by the stride model.
3. **Step detection.** Band-pass 0.5–3 Hz on vertical accel, peak/valley pairs with
   amplitude and timing thresholds. Compare against the hardware step detector and report
   disagreement in debug.
4. **Stride length.** Start with a calibrated constant (from the 20 m calibration walk).
   Then the Weinberg model `k * (a_max - a_min)^(1/4)` with `k` fitted from the same walk.
5. **Heading.** Yaw from the rotation vector plus a per-mode **heading offset** (device yaw vs
   walking direction), calibrated by walking straight for ~10 steps at the start of a trip.
   The magnetometer corrects slow gyro drift only when its magnitude and dip are within
   tolerance of the values seen at calibration; otherwise it is ignored (iron-rich rock,
   metal gear).
6. **Altitude.** Barometer relative to the trip start, low-passed, held constant during
   still segments. Stairs/climbs are visible as vertical steps in the path.
7. **Loop closure.** If the user marks "back at start", the end-to-start error vector is
   distributed linearly along the path (a crude but effective correction).
8. **Smoothing.** Light Savitzky–Golay or moving average on the positions.

The whole pipeline is pure Kotlin with a `Config` object, so the same code runs in unit tests,
in the app's re-process button, and can be mirrored in `tools/replay.py`.

## 6. Calibration and debug

Calibration screen (values stored per user, applied to every trip):

- **Still bias:** hold still 10 s → gyro bias and accel noise floor.
- **Stride walk:** walk a measured distance (default 20 m) → step count → stride and Weinberg `k`.
- **Heading offset:** walk straight 10 steps with the phone in its carry position.
- **Square test:** walk a 5 m × 5 m square and return → app reports closure error in metres
  and as a percentage of distance walked. This is the baseline number to improve.
- **ARCore vs PDR:** run Mode B in a lit corridor; the app computes PDR from the same log
  and reports the distance and heading error between the two.

Debug screen:

- Live plots of accel magnitude, vertical accel, detected steps, heading, pressure.
- ARCore tracking state and pose rate when relevant.
- Export raw log + processed path as a ZIP (share sheet) for offline analysis.
- Re-process any trip with the current pipeline version; results are versioned so old and
  new paths can be overlaid in the viewer.

## 7. Viewer

- Orbit camera: one finger drag rotates, two fingers pinch zooms, two-finger drag pans.
- Floor grid at 1 m spacing, north arrow, axis triad.
- Path coloured by elapsed time (default) or by altitude.
- Markers: start, end, annotations, photo keyframes. Tap a marker for details / photo.
- Toggle overlays: previous processing versions, ARCore point cloud, raw PDR vs fused.
- Top-down and side presets. Numbers panel: distance, duration, vertical range, closure error.

## 8. Phases

1. **Skeleton** — project setup, CI building a debug APK, trip list, empty screens.
2. **Recorder** — foreground service, raw logger, Mode A recording, annotations, debug plots,
   log export.
3. **PDR + viewer** — pipeline, calibration screen, square test, 3D viewer, re-process,
   `tools/replay.py`.
4. **Mode B** — ARCore session, torch, pose logging, tracking-loss fallback and re-anchoring,
   fused path.
5. **Mode C** — auto keyframe photos, point cloud overlay, photo markers in the viewer.
6. **Polish** — GLTF/CSV export, altitude colouring, presets, battery tuning.

Each phase ends with a pushed commit and an installable APK from CI.

## 9. Risks

- **Magnetometer in caves.** Handled by gating; worst case heading is gyro-only, which drifts
  slowly over minutes. Acceptable for ≤5 min trips.
- **Carry position changes mid-trip** (pocket → hand) break the heading offset. Mitigation:
  a "re-orient" annotation that restarts the offset calibration for the next 10 steps.
- **ARCore in caves.** Torch helps, but wet, uniform rock can still lose tracking. PDR
  fallback keeps the path continuous; the viewer shows which segments were VIO vs PDR.
- **Battery / heat** with ARCore and torch on. Fine for minutes, not hours.
- **Google Play Services for AR** must be installed (it is, on Galaxy devices).

## 10. References

- ARCore flash / torch: https://developers.google.com/ar/develop/camera/flash/java
- ARCore `Config.FlashMode`: https://developers.google.com/ar/reference/java/com/google/ar/core/Config
