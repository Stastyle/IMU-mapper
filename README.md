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

Every merge to `main` that changes the app or the pipeline runs the **Release** GitHub Actions
workflow: it takes the newest `vX.Y.Z` tag, bumps the minor version, signs the APK with a key
stored in repository secrets (create it with `scripts/gen-keystore.sh`, or
`scripts/gen-keystore.ps1` on Windows) and publishes a GitHub Release. Run the workflow by hand
with an explicit version for a patch release or a major bump. The app checks the latest release
from **Settings → Check for updates** and installs the new APK itself.
