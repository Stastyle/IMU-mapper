# CLAUDE.md

IMU Mapper is an Android app (Galaxy S26 Ultra) that records a walk with the IMU, barometer and
optionally ARCore, and shows the route as a 3D line, for mapping places without GPS such as caves.

- `docs/CONVENTIONS.md` holds the frames, units, raw-log and style rules. Read it before changing
  code. Its ownership rules (work items, "the integrator", "say so in your report") date from the
  parallel build in `docs/PLAN.md` section 9. They no longer limit which directories you may edit,
  but the build-file rule still applies (see "Ask first").
- `docs/PLAN.md` is the original design, and parts of it are stale. Where it disagrees with the
  code, the code is right.
- Area rules live in `.claude/rules/` (`pipeline.md`, `app.md`, `tools.md`) and load when you work
  on those files.

## Layout

- `pipeline/`: pure Kotlin/JVM processing, its own Gradle build, which the root includes as a
  composite build. Packages:
  - `core`: models, `PipelineConfig`, `PathResult`, `Quat`.
  - `log`: the binary `.imul` format.
  - `pdr` and `vio`: the two processors.
  - `post`: loop closure, smoothing, `PathBuilder`.
  - `tuning`: the assisted-tuning prompt, parser and score.
  - `survey`: Survey mode's pure math (time placement on a path, measurements, corners, the north
    solve and frame, the traverse, the CSV text). The processor never runs it, so a change there
    needs no `PIPELINE_VERSION` bump and no `replay.py` port.

  `DefaultProcessor` runs VIO when any ARCore pose is TRACKING, otherwise PDR.
- `app/`: Android app `com.stastyle.imumapper`:
  - `capture/`: the recorder and ARCore.
  - `data/`: Room, trip files, ZIP export/import, and `SurveyStore` for
    `files/trips/<id>/survey.json`: the per-trip Survey mode facts (stations, compass readings, the
    manual rotation, the corner detail level). It is user state, not a run, and travels in the ZIP
    export. It stores field and enum names, and a renamed one silently resets the stored value to its
    default, so follow the `survey.json` rules in `.claude/rules/pipeline.md`.
  - `process/TripProcessor`.
  - `render/`: pure scene, camera and survey-layer code (`PathScene`, `OrbitCamera`, `SurveyLayer`,
    `ProjectedSurvey`), the colour ramp, elevation profile and thumbnail helpers (`PathProgress`,
    `PathProfile`, `PathThumbnail`), and the Compose drawing in `PathRenderer` and `SurveyRenderer`.
  - `ui/theme/` (the dark navy colour scheme and extra tokens), `ui/common/` (shared components and
    the unit formatters), `ui/nav/` (routes, the graph and the bottom tabs), `ui/<screen>/`,
    `update/` (the GitHub Releases updater) and `debug/CrashLog`. `docs/UI-REDESIGN.md` records the
    design decisions behind the current look.
- `tools/replay.py`: a Python port of the PDR pipeline for offline experiments.
- `.github/workflows/`: `ci.yml` (tests) and `release.yml` (signed APK to GitHub Releases).

## Build and test

Gradle 8.11.1 runs on **JDK 17 to 23**. JDK 24 fails with `Type T not present`, and CI uses
Temurin 17. The `:app` tasks also need the Android SDK, through `ANDROID_HOME` or `sdk.dir` in
`local.properties`. Machine-specific paths go in `CLAUDE.local.md`, which is git-ignored.

```bash
(cd pipeline && ../gradlew test)                      # pipeline tests, no Android SDK needed
(cd pipeline && ../gradlew test --tests '*PdrProcessorTest')   # one class, or '*Class.method'
./gradlew :app:assembleDebug :app:testDebugUnitTest   # what CI runs for the app
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.render.*'
```

- In the Bash tool (Git Bash on Windows) use `./gradlew`; `gradlew.bat` is for PowerShell. Shell
  variables do not persist between tool calls, so set `JAVA_HOME` and `ANDROID_HOME` on the same
  command line when they are needed.
- A root `./gradlew test` does **not** run the pipeline tests. Run them from `pipeline/`.
- Only failing tests print. Full reports are in `pipeline/build/reports/tests/test/` and
  `app/build/reports/tests/`.

## Merging to main ships to phones

Every push to `main` that touches `app/`, `pipeline/`, `gradle/`, a root `*.gradle.kts`,
`gradle.properties` or `gradlew*` runs `release.yml`. The workflow:
1. Bumps the minor version.
2. Re-runs the unit tests.
3. Signs the APK.
4. Publishes a GitHub Release, which every installed app then offers as an update.

Changes to docs, `tools/`, `scripts/` and `.github/` do not release; dispatch the workflow by hand
if a release is wanted after one of those. So:

- Keep `main` releasable. A bug merged there reaches every phone.
- **PR titles are user-facing, even for docs-only PRs.** The release body is GitHub's generated
  notes, which list every PR merged since the previous tag, and Settings shows them when it offers
  the update. Write titles as plain sentences for the user.
- **A Room entity change can wipe user data.** `AppDatabase` uses
  `fallbackToDestructiveMigration(dropAllTables = true)`, so an entity change needs a version bump
  and a Migration. Nothing in this repo can test a Migration: there is no androidTest and
  `exportSchema` is false. Ask the user before changing an entity (details in
  `.claude/rules/app.md`).
- The signing key never changes: installed apps accept only APKs signed with the same key. It lives
  in four repository secrets. `*.jks` and `*.jks.base64` in the repo root are the real key and are
  git-ignored, so **never read, print, stage or commit them.**

## Rules everywhere

- **Pipeline purity:** `pipeline/` has no Android imports, no clock, no randomness and no I/O
  outside `log/`. Output must be deterministic, because tests compare `toJson()` of two runs.
- **Frames:** the world frame is ENU in metres (+Y is magnetic north when the diagnostic
  `northReference` is "magnetic"), and headings are radians clockwise from north in (-pi, pi]. Only
  `vio/` converts ARCore coordinates to ENU.
- **Timestamps:** record and sample timestamps (`tNs`) are on the
  `SystemClock.elapsedRealtimeNanos()` clock.
  - Sensor records keep `SensorEvent.timestamp`, which is already on that clock, so do not
    re-stamp them.
  - ARCore records are stamped on arrival.
  - Never use `System.nanoTime` or wall time for `tNs`. Wall-clock fields are named `*EpochMs` and
    use `System.currentTimeMillis()`.
- **Pauses:** the recorder keeps logging through PAUSE..RESUME. The pipeline still runs
  orientation, step detection and carry-change detection across a pause, but takes no step,
  altitude change or VIO pose from inside one. New code that turns samples into displacement must
  do the same with `PauseIntervals`, as `PdrSolver.excludePaused`, `altitudeDelta`, `Climbs` and
  `VioProcessor` do. Do not filter the raw streams themselves.
- **`PipelineConfig`:** every field needs a default. Old calibration rows, `results/run-<n>.json`
  files and `LogMeta` must keep decoding, and unknown keys are ignored, so a rename silently drops
  the stored value. Adding a field touches several files; the checklist is in
  `.claude/rules/pipeline.md`.
- **`PIPELINE_VERSION`** (`core/Processor.kt`): bump it whenever pipeline output changes, and add a
  line to its KDoc.
- **Runs are never overwritten:** each processing run is a new `results/run-<n>.json`.
- **Network:** only `update/` touches the network.

## Ask first

- Ask before adding a dependency or editing `gradle/libs.versions.toml`, the build files or the
  workflows.
- `pipeline/build.gradle.kts` hard-codes the Kotlin and serialization versions. Keep them equal to
  the catalog.

## Known drift

- `tools/replay.py` lags the Kotlin PDR and does not port VIO. `tools/README.md` lists the gaps
  under "Not covered", and `--compare` differences in those areas are expected.
- `ConfigSchema` and `ProposalParser` (assisted tuning) do not know `autoReorient`,
  `carryChangeTiltRad` or `carryChangeSettleS`. They do know `northFromCompass`, marked as not
  tunable.
- The Debug config editor (`ConfigFields`) lacks `baroStillGapS`. Saving there, and every run
  started there (Re-process, PDR only, VIO only), uses 4.0 whatever the calibration holds.
- `docs/PLAN.md` names classes that do not exist (`LogCodec`, `VioFuser`, `ProcessTrip`,
  `ArCoreSession`), calls `release.yml` manual-only, and puts ZIP export on the Debug screen. Export
  is on the trip cards' menu and the viewer's Share.

## Git

- Branch off `main`.
- Commit subjects are plain-English sentences about the effect ("Fix the viewer crash: a label near
  the right edge made drawText throw"), and bodies explain why in prose.
