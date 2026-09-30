# IMU Mapper

Android app for the Galaxy S26 Ultra that records a walk with the phone's sensors (IMU,
barometer, and optionally the camera through ARCore) and shows the route afterwards as a
3D line you can rotate, zoom and pan. Built for crude mapping of places without GPS,
such as caves.

## What it does

- **Three capture modes.**
  - **Pocket** uses the IMU only and works in the dark: pedestrian dead reckoning with the
    barometer for height. A height change counts only once it has lasted several steps, as on
    stairs or a slope, which hides pressure jumps indoors on flat ground; **Settings → Processing
    → Steps to confirm a height change** sets how many (4 by default, Off takes every change).
  - **Flashlight** adds ARCore visual-inertial odometry with the torch on.
  - **Illuminated** is for lit spaces. It uses ARCore with the torch optional and saves a photo
    keyframe every 2 m or 30° of turn.

  Every mode logs the full raw sensor stream, so dead reckoning covers any stretch where ARCore
  loses tracking.
- **Annotations while walking.** Buttons mark waypoints, junctions, chambers and notes, plus
  "re-orient" and "back at start", which closes the loop. Moving the phone into or out of a pocket
  is detected on its own and does not turn the path; "re-orient" is only for turning the phone in
  your hand without tilting it, and you tap it as you do so.
  While the app is on screen, a volume key marks a waypoint.
- **A dark navy app with four tabs:** Trips, Record, Calibrate and Settings. The Record tab picks the
  capture mode and shows the battery; the next step picks where the phone is carried and starts the
  recording. While a trip records, the tab shows a dot and offers a way back to the recording.
- **Recording screen.** A status line ("Sensors OK", "Paused", or which sensor has stopped
  delivering), the battery, tiles for the IMU, the step detector, the heading and the magnetometer's
  accuracy, the active time, the steps and marks, and an estimated distance (steps × the saved
  stride; the real one is computed when you stop). Pause, Stop and the waypoint flag stay at the
  bottom with the other marks.
- **Trips tab.** Each trip is a card with a thumbnail of its path, its status, and the duration,
  distance and steps of its latest processing run. Search by name, filter by mode or by whether a
  trip has been processed, and sort by date, name, distance or duration.
- **Trip viewer** in four tabs:
  - **Path:** the 3D path above a trip summary (distance, duration, steps, height range, loop
    closure error, share of VIO points) and an elevation profile.
  - **3D:** the same path at full height. Orbit, zoom and pan, with a floor grid and a north arrow.
  - **Graph:** the elevation profile with the lowest and highest point, the net change and the
    total climb and descent (rises and drops of 0.5 m or more).
  - **Details:** the processing runs, the raw-path switch and the trip's facts.

  North is magnetic north, read from the compass as the recording starts, unless the "North from
  compass" setting is off; then it is wherever the gyro started. The path is coloured by distance
  walked, blue at the start to red at the end, or by time, altitude or source. Markers and photos can
  be tapped. You can switch to the path before loop closure and smoothing, or overlay an earlier
  processing run.
- **Survey mode (beta).** The ruler button on the viewer's map turns the trip into a north-up plan of
  stations: the start, the end, the marks made while walking, and every turn of the path, found
  automatically. Tap a stretch or a chain of stations to read its length, azimuth and slope, add or
  move stations, and export the legs as a CSV. North can be corrected with a bearing from a hand
  compass ("this stretch is 045°") or by hand, and the whole map turns about the start without
  re-processing. The survey is kept in `survey.json` next to the trip and travels in its ZIP.
- **Re-processing.** Raw logs are kept, and every processing run is stored as a new version, so an
  old trip can be run again with a better algorithm or calibration.
- **Calibration** (the Calibrate tab). Guided flows measure still bias, stride (Weinberg `k`) and
  heading offset, run a square closure test, and compare ARCore with dead reckoning. **Assisted
  tuning** (in Calibrate and Settings → Tools) builds a prompt about a walk you describe, which you
  give to a chat model. The app checks the model's proposed settings and scores them before you can
  save them.
- **Debug screen** (from the Trips menu, Settings → Tools, or a trip's viewer). Live sensor plots, a
  raw-log summary, a config editor that re-processes the trip, and the last crash report with Copy
  and Share.
- **Export and import.** A trip (raw log, every run, photos, survey) is exported as a ZIP from its
  card's menu on the Trips tab or with Share in the viewer, and a ZIP or a bare `.imul` log can be
  imported from the Trips menu.
- **Self-update** from GitHub Releases.

## Install

Download `imu-mapper-vX.Y.Z.apk` from the
[latest release](https://github.com/Stastyle/IMU-mapper/releases/latest) and open it on the phone.
Android asks you to allow installs from that source once. After that the app finds new releases
itself, either from **Settings → Check for updates** or from a check at app start (at most once a
day, shown as a banner on the Trips tab and a dot on the Settings tab). To update:
1. Tap **Update** to download the release. The app verifies its sha256.
2. Tap **Install** to hand it to the system installer, and confirm there.

The first in-app update also asks you to let IMU Mapper install unknown apps. The camera modes need
Google Play Services for AR.

## Repository layout

| Path | What |
|---|---|
| `pipeline/` | Pure Kotlin/JVM processing pipeline (PDR, VIO, loop closure, smoothing, tuning). It is its own Gradle build, composite-included by the root. |
| `app/` | Android app: recorder, ARCore capture, viewer, calibration, tuning, debug, updater. |
| `tools/` | `replay.py`, the offline Python replay of the PDR pipeline, with plots ([tools/README.md](tools/README.md)). |
| `docs/PLAN.md` | Design: capture modes, pipeline, viewer, releases, build plan. |
| `docs/CONVENTIONS.md` | Frames, units, the raw log, module rules and style. |
| `scripts/` | `gen-keystore.sh` and `gen-keystore.ps1` create the release signing key. |
| `CLAUDE.md`, `.claude/` | Instructions for Claude Code: `CLAUDE.md` loads in every session, and `.claude/rules/` load when work touches `pipeline/`, `app/` or `tools/`. `.claude/settings.json` has shared permissions and denies Claude's file-read tools on the keystore files (a best-effort guard; CLAUDE.md also forbids reading them). Personal notes go in a git-ignored `CLAUDE.local.md`. |

## Building and testing

Requirements:
- **JDK 17 to 23.** The Gradle 8.11.1 wrapper does not run on JDK 24 or newer: it fails with
  `Type T not present`. The JBR bundled with Android Studio works; point `JAVA_HOME` at it.
- **Android SDK with platform 35**, for the `app` module only. Set `ANDROID_HOME`, or put
  `sdk.dir=...` in `local.properties`. Android Studio writes that file when it opens the project,
  and it is git-ignored.

```bash
# Pipeline: plain JVM, no Android SDK needed
(cd pipeline && ../gradlew test)

# App: debug APK and JVM unit tests (the same as CI)
./gradlew :app:assembleDebug :app:testDebugUnitTest

# Install the debug build on a connected phone
./gradlew :app:installDebug
```

On Windows PowerShell use `..\gradlew.bat test` and `.\gradlew.bat ...`. Some points to know:
- A root `./gradlew test` does not run the pipeline tests. Run them from `pipeline/`.
- To run a single test class, add `--tests '*ClassName'`.
- Reports are written to `pipeline/build/reports/tests/test/` and `app/build/reports/tests/`.
- The debug build is `com.stastyle.imumapper.debug`. It installs next to the release app, keeps
  its own trips, and does not self-update. Use export and import to move trips between the two.

CI (`.github/workflows/ci.yml`) runs the pipeline tests, `:app:assembleDebug` and
`:app:testDebugUnitTest` on JDK 17 for every pull request and every push to `main`. It builds no
release and uploads no APK. The test reports are attached to each run as the
`pipeline-test-reports` and `app-test-reports` artifacts.

### Offline replay

`tools/replay.py` replays a raw `.imul` log through a Python port of the PDR pipeline. It prints
diagnostics and writes the path, CSV and plots, so an algorithm change can be tried in seconds
without building the app.

```bash
python -m pip install -r tools/requirements.txt
python tools/replay.py tools/samples/synthetic_square.imul --out /tmp/square
```

See [tools/README.md](tools/README.md) for the options and for what the Python port does not cover.

## Releases and updates

The **Release** workflow (`.github/workflows/release.yml`) runs on every push to `main` that
changes `app/`, `pipeline/`, `gradle/`, a root `*.gradle.kts`, `gradle.properties` or `gradlew*`.
Changes to docs, `tools/`, `scripts/` and `.github/` (including this workflow) do not trigger
it. After changing only those, run it by hand if a release is wanted. The workflow then:
1. Takes the newest `vX.Y.Z` tag and bumps the minor version.
2. Runs the pipeline and app unit tests again on that exact commit.
3. Builds and signs the APK.
4. Publishes a GitHub Release with `imu-mapper-vX.Y.Z.apk` and `SHA256SUMS.txt`. The in-app updater
   checks the APK against GitHub's asset digest, or against `SHA256SUMS.txt` for a release without
   one.

Run the workflow by hand with an explicit version for a patch release or a major bump:

```bash
gh workflow run release.yml -f version=1.0.0
```

`versionCode` is `major*10000 + minor*100 + patch`, so minor and patch must stay below 100. The
build and the workflow both enforce this.

**Signing.** Android only updates an installed app from an APK signed with the same key, so the key
must never change. To set it up:
1. Create the key once with `scripts/gen-keystore.sh` (`scripts\gen-keystore.ps1` on Windows).
2. Add four repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. The workflow refuses to run without them.

The script writes `<name>.jks` and `<name>.jks.base64` into the current directory. Both are
git-ignored, and both contain the private key. Paste the `.base64` file into the secret, then keep
the `.jks` backed up somewhere private. Losing it means installed apps can no longer be updated.
