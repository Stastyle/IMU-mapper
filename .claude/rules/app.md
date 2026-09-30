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
  row. The viewer caches runs by id on the assumption that runs are never overwritten, and so do
  the trip cards' thumbnails (`data/TripThumbnails`): an in-memory map plus a
  `files/trips/<id>/thumb-run-<n>.json` sidecar in the trip folder, not under `results/`, so
  `TripArchive` never exports it. `TripThumbnails` builds paths from `TripFiles.root` without the
  `mkdirs` helpers, so a thumbnail made while its trip is deleted cannot recreate the folder.
- **Trip cards read the latest run through `TripDao.observeTripRows()`**, a LEFT JOIN into the plain
  `TripRow` (not an entity). Duration, distance and steps all come from that run's `PathStats`, so
  a card never mixes numbers from two runs.
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
- **A new screen** needs a route in `ui/nav/Routes.kt` and an entry in `ui/nav/AppNavGraph.kt`. A
  top-level screen (a tab: Trips, Record, Calibrate, Settings) also needs an `AppBottomBar` item and
  a `bottomBar` parameter that it passes to its `AppScaffold`; pushed screens (record, viewer,
  tuning, debug) have no bar. Push them with `launchSingleTop` from a click wrapped in
  `dropUnlessResumed`, because two record entries would both adopt the recording and both process
  it. The recording screen and the Record tab open the **running** recording's mode
  (`recordRouteFor`), never a default, or ARCore would write into a Pocket log.
- **Two themes:** every colour comes from `ui/theme/ThemePalette.kt` (the UI, both schemes) or
  `render/CanvasPalette.kt` (maps, thumbnails, the elevation chart, the calibration preview), never
  a literal in a screen. A new colour pair needs a value in both, and `ThemePaletteContrastTest` /
  `CanvasPaletteTest` must still pass. The camera overlay stays dark in both themes
  (`ImuMapperTheme(dark = true)` around it). The setting is `UpdatePreferences.themeMode`; on API
  31+ it is also handed to `UiModeManager.setApplicationNightMode`, so `values-night` and the splash
  follow it from cold start.
- **Look:** screens use the shared components in `ui/common` (`AppScaffold`, `ScreenHeader`,
  `AppTopBar`, `GlassCard`, `StatTile`/`StatGrid`, `StatusPill`, `BrandButton`, `BrandFilterChip`,
  `RoundIconButton`, `SegmentedTabs`) and the colour tokens in `ui/theme`
  (`MaterialTheme.colorScheme` and `MaterialTheme.imuColors`), not colour literals. `primary` and
  `error` are text colours; a white-on-blue fill uses `imuColors.brandFill`. Distances, durations,
  heights and clocks go through `ui/common/UnitFormat.kt`, so the cards, the viewer and the
  recording screen agree.
- **Right-to-left phones:** the theme's text styles take their direction from their own text and
  align to the layout's start, as Android views do. A number-only text (a value, a signed range)
  sets `TextDirection.Ltr`; a Text that must line up with the screen rather than its words uses
  `layoutTextAlign()`. Maps, thumbnails and charts are never mirrored: wrap them in
  `LocalLayoutDirection provides Ltr`, and place canvas buttons with `AbsoluteAlignment`.
- **Pure render and UI logic** uses no Compose or Android types, so it stays unit-testable. Colours
  there are ARGB `Int`s. The files: `render/PathScene.kt`, `render/OrbitCamera.kt`,
  `render/SurveyLayer.kt`, `render/ProjectedSurvey.kt`, `render/CanvasPalette.kt`,
  `render/PathProgress.kt` (the colour ramp by distance walked, shared by the viewer, the elevation
  chart, the thumbnails and the calibration preview), `render/PathProfile.kt`,
  `render/PathThumbnail.kt`, `ui/calibration/CalibrationMath.kt`, `ui/common/UnitFormat.kt`,
  `ui/common/BatteryFormat.kt`, `ui/common/StatColumns.kt`, `ui/theme/ThemePalette.kt`,
  `ui/theme/ThemeMode.kt`, `ui/nav/RecordRoute.kt`, `ui/nav/TabBadges.kt`,
  `ui/record/RecordStatus.kt`, `ui/settings/UpdatePill.kt`, `ui/triplist/TripListItem.kt`,
  `ui/triplist/TripListQuery.kt`, `ui/triplist/RecordingMode.kt`, `ui/viewer/ViewerText.kt` and
  `ui/viewer/SurveyFormat.kt`. `render/PathRenderer.kt` and `render/SurveyRenderer.kt` are the
  Compose drawing layer.

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
