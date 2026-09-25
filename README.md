# IMU Mapper

Android app for the Galaxy S26 Ultra that records a walk with the phone's sensors (IMU,
barometer, and optionally the camera through ARCore) and shows the route afterwards as a
3D line you can rotate, zoom and pan. Built for crude mapping of places without GPS,
such as caves.

- `docs/PLAN.md` — design, capture modes, pipeline, build plan
- `docs/CONVENTIONS.md` — frames, units, ownership, style
- `pipeline/` — pure Kotlin processing pipeline (`cd pipeline && ../gradlew test`)
- `app/` — Android app (`./gradlew :app:assembleDebug`, needs the Android SDK)
- `tools/` — offline replay and plotting

## Releases and updates

Releases are made manually with the **Release** GitHub Actions workflow, which signs the APK
with a key stored in repository secrets (create it with `scripts/gen-keystore.sh`) and
publishes a GitHub Release. The app checks that release from **Settings → Check for updates**
and installs the new APK itself.
