---
paths:
  - "app/**"
---

# App rules

## Data

- **Room:** `AppDatabase` is version 1 with `exportSchema = false` and
  `fallbackToDestructiveMigration(dropAllTables = true)`. An entity change needs a version bump
  **and** a Migration:
  - Without the bump, Room throws when it opens the database.
  - With the bump but no Migration, every trip, run and calibration row is wiped on every phone
    that updates.
  - Ask the user first, because nothing here can test a Migration.
- **A new `TripEntity` or `PathResultEntity` field** also goes into the ZIP manifests in
  `data/TripArchive.kt`, or export and import lose it.
- **Trip rows have concurrent writers** (recorder, processing, UI edits). Change them with the
  column-scoped `TripRepository` writes (`renameTrip`, `markProcessed`, `markFailed`, backed by
  `TripDao.rename`/`markProcessed`/`markFailed`). `updateTrip(copy)` overwrites whatever another
  writer stored in between; only the recorder and the importer, which own the row, use it. A new
  write needs:
  - A `@Query("UPDATE trips SET ...")` in `TripDao`.
  - A method on `TripRepository`, `RoomTripRepository` and `FakeTripRepository`.
- **Runs are numbered `max(runId) + 1`** under a per-trip mutex. The file is written before the DB
  row. The viewer caches runs by id on the assumption that runs are never overwritten.
- **The FileProvider authority is `<packageName>.fileprovider`.** Build it from
  `context.packageName`, because debug builds add `.debug`.

## Viewer and UI

- **A `drawText` origin must be finite, left of the right edge and above the bottom edge.** Past
  either edge Compose gets a negative layout size and throws (commit 5e8322e). Negative x or y is
  fine. Place labels with `PathRenderer.labelOrigin`, which applies the label offset and rejects
  NaN.
- **The viewer must not auto-reprocess a FAILED trip on open.** Re-running an out-of-memory trip on
  every open used to crash the app. In the viewer only the user's Retry reprocesses it. The trip
  list's Re-process and the Debug screen runs stay manual.
- **There is no Hilt.** Dependencies are lazy vals in `AppContainer`, and each screen builds its
  ViewModel with `viewModel { }`, passing constructor arguments. ViewModels never reach the
  container themselves.
- **A new screen** needs a route in `ui/nav/Routes.kt` and an entry in `ui/nav/AppNavGraph.kt`.
- **Pure render and calibration code** (`render/PathScene.kt`, `render/OrbitCamera.kt`,
  `ui/calibration/CalibrationMath.kt`) uses no Compose or Android types, so it stays unit-testable.
  Colours there are ARGB `Int`s. `render/PathRenderer.kt` is the Compose drawing layer.

## Updater and release

- **The version comes from the release tag `vX.Y.Z`** (`ReleaseParser`, `SemVer.parse`). Changing
  the tag format breaks update detection silently.
- **Asset choice:** the updater takes any `.apk` asset, preferring one whose name contains
  `imu-mapper`. It uses `SHA256SUMS.txt` only when GitHub gives the asset no digest.
- **Keep the debug `versionNameSuffix` "-debug":** `SemVer.isDebugBuild` keys on it to switch the
  updater off, because the debug build is a separate app (`com.stastyle.imumapper.debug`).
- **`versionCode = major*10000 + minor*100 + patch`,** so minor and patch must stay below 100.

## Tests

- **App unit tests run on the plain JVM,** with no Robolectric and no default return values, so any
  `android.*` call throws "not mocked". Keep testable logic in pure classes.
- **ViewModel tests** call `Dispatchers.setMain(UnconfinedTestDispatcher())` before building the
  VM.
- **Fakes:** `app/src/test/.../data/Fakes.kt` has the in-memory repositories and processor. Extend
  it when `TripRepository` changes.
