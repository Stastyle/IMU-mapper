# Conventions

Short rules every contributor (human or agent) follows. See `PLAN.md` for the design.

## Modules and ownership

- `pipeline/` is a standalone Kotlin/JVM Gradle build with **no Android imports**. Build and
  test it with `cd pipeline && ../gradlew test`. It must stay deterministic: no wall-clock
  time, no randomness, no I/O outside `log/`.
- `app/` is the Android module. It depends on the pipeline as `com.stastyle.imumapper:pipeline`.
- Build files (`*.gradle.kts`, `gradle/libs.versions.toml`, workflows) are owned by the
  integrator. If you need a new dependency, say so in your report instead of adding it.
- Work items own disjoint directories (table in `PLAN.md` section 9). Do not edit files outside
  your directories; if an interface in `pipeline/core`, `data/` or `ui/nav` needs a change,
  describe the change in your report.

## Frames, units, time

- Pipeline world frame is **ENU**: x = east, y = north, z = up, metres, origin at the trip start.
- Headings are radians **clockwise from north**, range (-pi, pi]. Use `Quat.headingOf`.
- Android sensor frame: x = right edge of screen, y = top of phone, z = out of the screen.
  `Quat.rotate` takes sensor-frame vectors to ENU when the quaternion comes from a rotation
  vector sensor.
- ARCore frame is right-handed, **+Y up**, -Z forward at session start. `VioProcessor`
  converts it to ENU; nothing else should see ARCore coordinates.
- Timestamps are `Long` nanoseconds from `SystemClock.elapsedRealtimeNanos()`. Sensor events
  already use this clock. ARCore frames get a timestamp taken when the frame arrives.
- Pressure in hPa, acceleration in m/s^2, angular rate in rad/s, magnetic field in microtesla.

## Raw log

`pipeline/log/LogFormat.kt` documents the binary format. The recorder writes through
`LogWriter`; everything downstream reads through `LogReader` into a `RawLog`. The first record
of a log is `LogMeta` JSON. Add new record types only by extending `LogFormat` and both codecs
together with a round-trip test.

A `RawLog` holds the sensor streams as columns of primitives (`SampleColumns.kt`) and builds the
sample data class on each `get`: ten streams at up to 500 Hz are millions of samples per
half-hour, and as objects they do not fit the app's heap. Iterate or index the lists; do not
copy a long stream into another collection. Readers that only process pass
`LogReader.UNCALIBRATED_TYPES` as `skipTypes` so the uncalibrated streams, which no processor
reads, are never loaded.

## Results

A processing run produces a `PathResult` (JSON) stored as `results/run-<n>.json` next to the
trip's `raw.imul`, plus a `PathResultEntity` row. Never overwrite an earlier run.

## Android

- minSdk 30, targetSdk 35, Kotlin 2.1, Jetpack Compose with Material 3, no Hilt: dependencies
  come from `AppContainer`.
- Screens are `@Composable` functions taking callbacks, wired in `ui/nav/AppNavGraph.kt`.
  State lives in a `ViewModel` created with `viewModel { }` and the container.
- The recording foreground service uses type `health`, so request `ACTIVITY_RECOGNITION`
  (and `POST_NOTIFICATIONS`) before starting it. Camera modes need `CAMERA`.
- No network access except the updater (`update/`).
- Strings that users see go in `res/values/strings.xml` when they are shared; screen-local
  literals are acceptable in stubs.

## Style

- Kotlin official code style, 120-column lines.
- Prefer small pure functions with unit tests in `pipeline`. UI code is tested by compiling and
  by the reviewer reading it; keep logic out of composables.
- Comments say why, not what. No TODOs left behind without an owner in the report.
