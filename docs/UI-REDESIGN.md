# UI redesign: the navy "instrument" look

This is the plan for restyling the app to match the target mockup: three phones (Trips, the trip
viewer and the recording screen) on a dark navy background with glassy cards and a rainbow path.
It records the decisions, the per-screen specifications, the work split and how the result is
verified. The plan was checked against the code by five independent reviewers (code facts,
navigation and lifecycle, data honesty, rules and ownership, fidelity and accessibility) and
revised; section 13 lists what that review changed. Where this document and the code disagree
after the redesign has merged, the code is right.

## 1. Goals and rules

Goals:

- One dark navy look on every screen: gradient background, translucent cards with a thin blue
  border, electric-blue accents, bold titles, pill badges.
- Bottom navigation between the top-level screens.
- Trip cards that show the trip's path, status and key numbers at a glance.
- A viewer in tabs (Path, 3D, Graph, Details) with a trip summary and an elevation profile.
- A recording screen with sensor health, battery and live counters as tiles, and large Pause /
  Stop / Flag controls.

Rules the redesign keeps (`CLAUDE.md`, `.claude/rules/app.md`, `docs/CONVENTIONS.md`):

- **No invented data.** Every number or status on screen comes from real data. Where the mockup
  shows something the app does not compute, the element changes or goes (section 3).
- No new dependency, no build-file change. `material-icons-extended` is already a dependency.
- No Room entity change. A new DAO `@Query` returning a plain row class is fine.
- `pipeline/` is untouched, so `PIPELINE_VERSION` stays.
- Pure code (`render/PathScene.kt`, `render/OrbitCamera.kt`, and every new pure helper listed in
  section 8) uses no Compose or Android types; colours there are ARGB `Int`s.
- Every `drawText` origin goes through `PathRenderer.labelOrigin`.
- Every existing feature stays reachable (section 9).
- The viewer never auto-reprocesses a FAILED trip.

## 2. Decisions

| # | Question | Decision | Why |
|---|---|---|---|
| D1 | Colour scheme | One fixed dark brand scheme, dynamic colour removed, dark-only | Dynamic colour replaces any palette on Android 12+, so without this nothing changes on the S26. The mockup is dark; a dark screen suits caves. |
| D2 | Bottom tabs | **Trips / Record / Calibrate / Settings** | "Maps" has nothing honest behind it: each trip has its own origin and possibly relative north, so trips cannot share a map. Calibration is the first thing a new user needs. |
| D3 | Record tab | A top-level **New trip** page: mode cards, device card, **Continue**. Continue pushes the recording screen, whose setup step ("Ready to record") holds the carry chips, the start instructions and **Start recording**. While a recording runs the page only offers **Return to recording**. | The mode picks the route; the carry is chosen in one place (the setup step already loads and saves it), so nothing has to be saved and awaited before navigating. |
| D4 | Bar hosting | Each top-level screen gets a `bottomBar` slot for its own scaffold | No double insets; the snackbar stays above the bar; the bar moves with its screen. |
| D5 | Path colours | New `ColorMode.PROGRESS`: 3D distance along the path, 6 stops (blue, cyan, green, yellow, orange, red), defined once in `render/PathProgress.kt`; the viewer's default. Thumbnails, the elevation chart and the calibration preview use it too. | The map, chart and thumbnails agree. `SceneOptions()` keeps TIME as its default, so `PathSceneTest` stays valid. |
| D6 | Viewer tabs | **Path** = canvas (about 42 % of the height) + summary + elevation; **3D** = the same canvas at full height; **Graph** = large elevation profile and height tiles; **Details** = runs, view options, trip facts, actions | One canvas at a time; the 3D tab is the full-height view. |
| D7 | Live path while recording | Not in this change. Camera modes keep the camera preview; Pocket shows the stats card led by a large clock and "The path is computed when you stop." | No live path exists; a stride × heading preview would disagree with the processed path. |
| D8 | Card thumbnails | Generated lazily from the latest run's points, cached in memory and as a sidecar `thumb-run-<n>.json` in the trip folder (not under `results/`) | Decoding multi-MB run files per row per launch would repeat the out-of-memory history; the sidecar makes it a one-time cost. |
| D9 | Card numbers | Steps, distance and duration from the latest run's `PathStats` through a join query | No entity change; all three describe the same run. |
| D10 | RTL (Hebrew phones) | Layout mirrors as usual; canvas overlays use absolute alignment; charts are laid out LTR; numeric texts use `TextDirection.Ltr` | The drawn map never mirrors, so its controls should not; `-0.7 … +5.0` must not reorder. |
| D11 | Launcher icon | Redrawn: angular cyan path with round nodes inside the safe zone, plus a monochrome layer | Matches the mockup's icon; today's start dot is clipped on round masks. |

## 3. Mockup elements that change on purpose

| Mockup | In the app | Reason |
|---|---|---|
| "Maps" tab | "Calibrate" tab | D2 |
| Settings gear on Trips and Recording | Trips header overflow (Import trip, Debug); no gear on Recording | Settings is a tab. Opening Settings mid-recording could change north or install an update, which ends the recording. |
| "IMU Connected" | "Sensors OK", "Paused", or a named fault (6.4) | The IMU is built in; "connected" would be false. |
| "Pocket v5 - PDR" | "Pocket · Hand · PDR" (mode · carry · processing); camera modes "Camera + steps" | There is no "v5"; the pipeline version applies at processing time. |
| Live path canvas while recording | Pocket: stats card with a large clock. Camera modes: the camera preview. | D7 |
| Recording "Distance 22.4 m", "Speed", "Vertical", "VIO 98 %" | "Distance ≈" (hardware steps × saved stride, labelled an estimate, showing the stride) and "Cadence". No live speed, vertical or VIO. | The raw barometer is not the climb-filtered height (unvalidated on real walks); live VIO needs ARCore state plumbing. |
| Meta line "date · duration · distance" | "date and time · mode" | Duration and distance are already in the stats row; several trips a day share a date; a renamed trip loses the mode word. |
| Highlighted border on the first card | Border only on a trip that is recording or busy | There is no "selected trip". |
| Different path hues per card | The same PROGRESS ramp on every card | D5 |
| "1 m grid" always | "n m grid" with the real spacing (1, 2, 5, 10, 50 m) | The spacing depends on the path's extent. |
| Filters All / Pocket / Illuminated / Processed | All / Pocket / Flashlight / Illuminated / Processed / Not processed | Flashlight trips must be filterable. |
| Viewer top bar: share + overflow | Same. Survey mode's ruler moves from the top bar to the canvas buttons (it stays in the top bar inside Survey mode, to leave it). | Back plus three actions leaves too little room for the title. This departs from `docs/plans/2026-09-29-survey-mode-design.md`, which put the ruler in the top bar; it stays one tap away. |
| Footprints and magnet icons | `Icons.AutoMirrored.Filled.DirectionsWalk` and a vector `ic_magnet` | Neither glyph exists in material-icons-extended 1.7.6. |
| Mockup numbers (e.g. Vertical 5.8 m with a -0.7…+5.0 range) | Computed values | The mockup's numbers are illustrative and inconsistent. |

## 4. Design system (work item A1)

### 4.1 Colour tokens

`ui/theme/Theme.kt` sets all 36 `darkColorScheme` roles (material3 1.3.1) explicitly, so no
baseline purple leaks into dialogs, sheets, menus, snackbars, the navigation bar or
`contentColorFor`. `primary` and `error` are also foreground colours (M3 draws TextButton and
OutlinedButton labels, focused field labels and links in primary; the app draws about 30 error
lines in error), so each must reach 4.5:1 on background, surfaceContainer, surfaceContainerHigh,
secondaryContainer and the canvas background. Strong blue fills with white text use the extended
`brandFill` instead.

| Role | Value | Notes |
|---|---|---|
| background / onBackground | `#07111F` / `#EAF2FF` | |
| surface / onSurface | `#0B1628` / `#EAF2FF` | |
| surfaceVariant / onSurfaceVariant | `#16263F` / `#8FA3BF` | grey-blue secondary text |
| surfaceContainerLowest / Low / (default) / High / Highest | `#060E1A` / `#0D1A2E` / `#111F35` / `#152741` / `#1A2E4C` | cards: surfaceContainer; sheets: Low; dialogs: High |
| surfaceBright / surfaceDim / surfaceTint | `#1E3352` / `#07111F` / `#64B5F6` | |
| primary / onPrimary | `#64B5F6` / `#00233A` | 7.5:1 on cards, 6.8:1 on dialogs |
| primaryContainer / onPrimaryContainer | `#0F3A66` / `#D6E9FF` | |
| inversePrimary | `#1565C0` | |
| secondary / onSecondary | `#4FC3F7` / `#00233A` | cyan accent for icons and selected nav items |
| secondaryContainer / onSecondaryContainer | `#143357` / `#CFE6FF` | stays navy-blue: UpdateBanner, sensor chips, the locked compass face, the survey banner use it |
| tertiary / onTertiary | `#FFB74D` / `#3A2600` | warnings |
| tertiaryContainer / onTertiaryContainer | `#4A3310` / `#FFE0B2` | |
| error / onError | `#FF8A80` / `#690005` | 7.2:1 on cards |
| errorContainer / onErrorContainer | `#4A1518` / `#FFDAD6` | |
| outline / outlineVariant | `#6F86A6` / `#1E3A5F` | outline draws interactive boundaries (unchecked Switch, text fields, outlined buttons, unselected chips) and must reach 3:1; outlineVariant is decorative only (card border, dividers, chart zero line). Never override component borders to outlineVariant. |
| inverseSurface / inverseOnSurface | `#DDE7F5` / `#0B1628` | snackbars |
| scrim | `#000000` | |

Extended tokens (`ui/theme/ImuColors.kt`, `MaterialTheme.imuColors` through a
`CompositionLocal`): `backgroundTop #0C1D34`, `backgroundBottom #050B16` (page gradient),
`cardFill` = surfaceContainer at 88 % alpha, `cardBorder #1E3A5F`, `cardBorderStrong #2F6FB5`,
`brandFill #1976D2` / `onBrandFill #FFFFFF` (4.6:1), `success #34D399`, `successContainer
#0B3326`, `warning #FBBF24`, `stopRed #D32F2F` (white on it 5.0:1), `canvasBackground #0A1424`.

### 4.2 Typography and shapes

- `Typography`: M3 defaults with `headlineMedium` bold and `titleLarge`/`titleMedium` semibold.
  M3 `Typography` has fixed slots, so the tagline is an extension:
  `val Typography.tagline: TextStyle get() = labelLarge.copy(letterSpacing = 3.sp)`.
- `Shapes`: extraSmall 8 dp, small 12 dp, medium 18 dp, large 24 dp, extraLarge 28 dp. Chips and
  pills use `CircleShape` in their wrappers.

### 4.3 System integration and resources

- `MainActivity`: `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
  navigationBarStyle = SystemBarStyle.dark(TRANSPARENT))`, so a phone in light mode still gets
  light system icons.
- `res/values/colors.xml`: `window_background #07111F`; `res/values/themes.xml`: add
  `android:windowBackground` to the existing `Theme.ImuMapper`, keeping its three items. No
  `values-v31` file: a qualified style replaces the base style instead of merging, and on API 31+
  the splash screen already takes a solid windowBackground, so cold start is navy.
- Launcher icon (D11): redraw `ic_launcher_foreground.xml` inside the 66 dp safe zone; add a
  `<monochrome>` layer to both adaptive-icon files.
- `res/drawable/ic_magnet.xml`: 24 dp single-colour horseshoe magnet, tinted by `Icon`, not
  auto-mirrored (used by the recording Mag tile).
- B to E add no resources; screen text stays as Kotlin literals, as today.

### 4.4 Shared components (`ui/common/`)

| Component | Contract |
|---|---|
| `AppBackground(content)` | Box with the vertical page gradient |
| `AppScaffold(topBar, bottomBar, snackbarHost, floatingActionButton, content)` | `AppBackground` + `Scaffold(containerColor = Transparent)`; every screen uses it |
| `ScreenHeader(title, subtitle?, tagline = false, actions)` | Large bold title for top-level screens, status-bar inset included |
| `AppTopBar(title, subtitle?, onBack?, actions)` | Transparent `TopAppBar` for pushed screens; `titleMedium` when a subtitle is present; AutoMirrored back arrow "Back"; `subtitle` is a composable slot so callers can lay out parts |
| `GlassCard(modifier, highlighted = false, tone = Normal/Accent/Error, onClick?, onLongClick?, onClickLabel?, onLongClickLabel?, content)` | cardFill + 1 dp border (cardBorderStrong when highlighted), medium shape |
| `SectionHeader(icon?, title, action?)` | "Trip Summary", "Recent Trips" |
| `StatTile(icon, label, value, detail?, contentDescription?)` | Icon (18 dp) inline with the label on the first line; value below (TextDirection.Ltr, one line, never ellipsized); optional detail below (may wrap); `semantics(mergeDescendants = true)`, icon decorative; a "—" value is spoken "not available" |
| `StatGrid(tiles, maxColumns)` | Measures labels, values and detail numbers in their real styles (`rememberTextMeasurer`, follows nonlinear font scaling) and picks the largest column count from maxColumns down to 1 that fits; the choice is a pure `statColumns(availableDp, gapDp, neededDp, maxColumns)` with a test |
| `StatusPill(text, tone, onClick?, onClickLabel?)` | CircleShape pill, text ≥ 11 sp, ≥ 48 dp target when clickable (Role.Button). Text on fill: Info onBrandFill on brandFill (4.6:1); Success success on successContainer (7.2:1); Warning onTertiaryContainer on tertiaryContainer (9.3:1); Error onErrorContainer on errorContainer (11.5:1); Neutral onSurfaceVariant on surfaceContainerHighest (5.3:1). Never bright text on its own container. |
| `BrandFilterChip(selected, onClick, label, leadingIcon?)` | FilterChip, pill shape; selected brandFill / onBrandFill; unselected border outline |
| `BrandButton(onClick, enabled, modifier, content)` | Filled pill button in brandFill / onBrandFill with M3 disabled alphas; every filled `Button` in the app switches to it except the red Stop pill and error-filled buttons |
| `RoundIconButton(onClick, contentDescription, icon or text, size = 40.dp)` | Translucent dark circle with a 1 dp border inside a ≥ 48 dp target |
| `GridScaleChip(text)` | Non-interactive pill (a Box, not a Surface, so taps reach the canvas under it) |
| `SegmentedTabs(tabs, selected, onSelect)` | Rounded container; selected tab brandFill; `selectableGroup()` with `selectable(role = Role.Tab)`, ≥ 48 dp tall |
| `rememberBatteryState()` (`Battery.kt`) | Registers `ACTION_BATTERY_CHANGED` while composed; level % and charging, or null |
| `BatteryFormat` (`BatteryFormat.kt`, pure) | Level text, a level bucket enum (the composable maps it to Battery0Bar..BatteryFull / BatteryChargingFull), low at ≤ 15 % |
| `UnitFormat` (`UnitFormat.kt`, pure) | `formatDistance(m?)`: "—" for null, negative or non-finite; rounds to 0.1 m; below 1000 m "%.1f m", else "%.2f km" (Locale.US). `formatDuration(s?)`: today's viewer m:ss / h:mm:ss, "—" for null or negative. `formatHeight(m)`: signed "%+.1f m" with no "-0.0". `formatClock(ns)`: h:mm:ss, negatives clamped to 0. |

## 5. Navigation shell (work items A3 and B)

Routes (`ui/nav/Routes.kt`): add `NEW_TRIP = "new-trip"`. Top-level: `TRIPS`, `NEW_TRIP`,
`CALIBRATION`, `SETTINGS`. Pushed (no bar): `RECORD`, `VIEWER`, `TUNING`, `DEBUG`.

- `AppBottomBar(currentRoute, recordingActive, updateAvailable, onSelect)` in `ui/nav`:
  NavigationBar (container surfaceContainerLow at 92 % alpha; selected icon and label secondary;
  indicator primaryContainer) with Trips (`Icons.AutoMirrored.Filled.ViewList`), Record
  (`RadioButtonChecked`, badge while recording or stopping), Calibrate (`Straighten`), Settings
  (`Settings`, badge while UpdateManager.state is Available, Downloading, ReadyToInstall or
  NeedsInstallPermission). Badged items add `stateDescription` ("Recording in progress",
  "Update available"); the dot is decorative.
- Tab navigation: `navigate(route) { popUpTo(startDestination) { saveState = true };
  launchSingleTop = true; restoreState = true }`. NavHost transitions are 150 ms fades.
- Double-tap guard: every push of `record`, `viewer`, `debug` and `tuning` uses
  `launchSingleTop = true`, and every onClick that pushes a route is wrapped in
  `dropUnlessResumed { }` (lifecycle-runtime-compose 2.8.7, already a dependency). Two RECORD
  entries would both adopt the recording and both process it when it stops.
- Pure `recordRouteFor(state: RecordingState): String?` in `ui/nav`: `Routes.record(mode)` for
  `Recording`, null for `Idle` and `Stopping`. "Return to recording" and the Record tab use it, so
  they always open the **running** recording's mode.
- Calibrate tab: `CalibrationScreen(onBack = null, ...)` hides its bottom bar while a flow's
  sensors run (leaving the tab cancels the flow).
- Settings tab: `SettingsScreen(onBack = null, ...)`; a new Tools card lists Assisted tuning and
  Debug.

Screen signatures after work item A3 (fixed before the parallel work starts; no later work item
changes them, and C never edits `AppNavGraph.kt`):

```kotlin
TripListScreen(onOpenTrip: (Long) -> Unit, onOpenRecording: (TripMode) -> Unit, onNewTrip: () -> Unit,
               onOpenDebug: () -> Unit, banner: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {})
NewTripScreen(onContinue: (TripMode) -> Unit, onReturnToRecording: (TripMode) -> Unit, bottomBar: @Composable () -> Unit = {})
CalibrationScreen(onBack: (() -> Unit)?, onOpenTuning: () -> Unit, bottomBar: @Composable () -> Unit = {})
SettingsScreen(onBack: (() -> Unit)?, onOpenTuning: () -> Unit = {}, onOpenDebug: () -> Unit = {}, bottomBar: @Composable () -> Unit = {})
RecordScreen(mode, onFinished, onCancelled)            // unchanged
ViewerScreen(tripId, onBack, onOpenDebug)              // unchanged
```

## 6. Screens

### 6.1 Trips (work item C)

Width budget at 384 dp: a 352 dp card; beside the 72 dp thumbnail the text column is about
244 dp.

Layout: `ScreenHeader("IMU Mapper", tagline "INDOOR PATH TRACKING")` stays fixed, with an overflow
(`MoreVert`): "Import trip…" (disabled while importing) and "Debug". The import progress bar may
sit fixed under it. Everything else is one LazyColumn with stable keys:

1. Update banner (restyled `UpdateBanner`).
2. Sticky header: search field (rounded, search icon, clear button, filters the name) and filter
   chips (single select, horizontal scroll): All, Pocket, Flashlight, Illuminated, Processed
   (`latestRunId != null`, whatever the status), Not processed (`latestRunId == null` and not
   RECORDING). A FAILED trip with an earlier run keeps its Failed pill but counts as Processed.
   Sticky, because a focused field in a plain lazy item is disposed when it scrolls away.
3. Section row: "Recent Trips" when sorting by newest date, otherwise "Trips"; sort menu: Date
   (newest), Date (oldest), Name (Collator), Distance, Duration (the shown values, nulls last).
4. Trip cards:
   - Thumbnail tile 72 dp: the path (PROGRESS colours, green start dot, red end dot, north up,
     never mirrored) or, without a run, the mode icon. Decorative for TalkBack.
   - Title row: name (titleSmall semibold, weight 1, one line, ellipsis) and an overflow button
     "More actions for <name>" opening the existing `TripActionsSheet`.
   - Meta row: date and time (weight 1 fill false, ellipsis), " · ", mode (never ellipsized):
     separate Texts, bidi-safe.
   - Status line: the pill (Recording Warning with a decorative pulsing dot, Recorded Neutral,
     Processed Info, Failed Error clickable "Show error" → `TripErrorDialog`), or a spinner while
     busy. Under it, when `notes` is the ended-unexpectedly note, a small warning line.
   - Stats row across the full card width below the thumbnail, three equal columns: Duration
     (`Timer`), Distance (`Route`), Steps (`DirectionsWalk`), values LTR, one line.
   - `combinedClickable(onClickLabel = "Open trip", onLongClickLabel = "More actions")`; border
     highlighted while recording or busy.
   - Tap: pure `recordingModeFor(item, state): TripMode?` returns the running mode only when the
     item is RECORDING and its id is the running trip's; then `onOpenRecording(mode)`, otherwise
     the viewer.
5. Empty states: no trips → onboarding card with "Record a trip" (Record tab) and "Import a trip"
   and three steps (Calibrate tab, Record tab, the 3D path); no match → "No trips match" with
   "Clear filters", below the search and chips. No empty state before the first database emission.

Card numbers come from one run: with `latestRunId` set and `statsJson` decoding, Duration,
Distance and Steps all come from that `PathStats`; if it does not decode, Duration and Distance
fall back to the trip columns (which `markProcessed` wrote from that run) and Steps is a dash;
without a run, Distance and Steps are dashes (a ZIP imported without results keeps the manifest's
distance, which describes no run here) and Duration shows `trip.durationS` (active time for a
phone recording). Formatting through `formatDistance` and `formatDuration`.

Data (no entity change):

- `TripDao.observeTripRows()`: `SELECT trips.*, path_results.statsJson AS statsJson,
  path_results.label AS runLabel FROM trips LEFT JOIN path_results ON path_results.tripId =
  trips.id AND path_results.runId = trips.latestRunId ORDER BY startedAtEpochMs DESC` into a plain
  `TripRow(@Embedded trip: TripEntity, statsJson: String?, runLabel: String?)` declared in
  `Daos.kt` (never an `@Entity`). KSP checks the SQL at build time; the emulator pass checks the
  join (a processed card shows steps, an unprocessed one a dash).
- `TripRepository.observeTripRows()`; `RoomTripRepository` delegates; `FakeTripRepository`
  combines its flows. `observeTrips()` stays for the recorder sweep and Debug.
- Pure `TripListItem.of(row, json)` in `ui/triplist` (statsJson decoded with `runCatching`; the
  fixtures hold `"{}"` and partial JSON) and pure `TripListQuery` (`text`, `TripFilter`,
  `TripSort`, `apply`).
- `TripListViewModel`: query state, rows combined and filtered on `Dispatchers.Default`. The
  new-trip leftovers go: `carryPosition`, `defaultTripMode`, `saveCarryPosition` and the
  `calibration` and `defaultTripMode` constructor arguments (A3 removes the dialog and the FAB).
- Thumbnails (`data/TripThumbnails.kt`, lazy in `AppContainer`, passed to the VM):
  `suspend fun get(tripId, runId): PathThumbnail?`. Memory map, then the sidecar, then generation
  from `results/run-<n>.json`, decoding points only (`@Serializable PointsOnly(val points:
  List<PathPoint>)`, `ignoreUnknownKeys`, streamed), then the sidecar is written. One generation
  at a time (Mutex) on IO; `OutOfMemoryError` and `IOException` caught; failures remembered for
  the session. Every path is built from `TripFiles.root` without the `mkdirs` helpers
  (`tripDir`, `resultsDir`, `resultFile`, `AtomicFiles.writeText`); the sidecar is written with a
  temp file and a rename that never creates folders, then the row is checked with
  `getTrip(tripId)` and, if it is gone, the sidecar is deleted again. The sidecar envelope
  (private to the file) has `formatVersion` and `sourceLength`; a mismatch regenerates it. No
  android.* API, including `Log`, so the class is JVM-tested. Cards request thumbnails per visible
  item. Sidecars are never exported or imported (`TripArchive` only takes `results/*.json`) and go
  with the trip folder.

### 6.2 New trip page (Record tab, work item B)

`ui/newtrip/NewTripScreen.kt` + `NewTripViewModel.kt` (constructor takes `StateFlow<RecordingState>`
and the default-mode flow, never `RecordingController.get`):

- `ScreenHeader("New Trip", "Record motion with your IMU")`.
- While `Recording`: only a highlighted card "Recording in progress · <mode> · <formatClock>"
  with "Return to recording" (→ `onReturnToRecording(state.mode)`). While `Stopping` (no mode):
  "Saving the last trip…", nothing else. Mode cards and Continue are hidden in both states.
- Otherwise: "Capture mode", three selectable GlassCards (icon, `TripFormat.modeLabel`,
  `modeExplanation`), preselected from `UpdatePreferences.defaultTripMode` until the user picks
  (the stored value's first emission is not overridden by a seed); a device card with the battery
  (`rememberBatteryState`) and a low-battery warning; a large `BrandButton` "Continue" with a
  trailing `Icons.AutoMirrored.Filled.ArrowForward` calling `onContinue(mode)`.

### 6.3 Viewer (work item D)

Top bar (`AppTopBar`): back; title = trip name; subtitle = a Row of separate Texts: date (weight 1
fill false, ellipsized first), run label (ellipsis), "raw" (never ellipsized). `viewerSubtitle`
and `ViewerTitleTest` stay; a small pure function splits the parts, with a test. Actions outside
Survey mode: Share (whole-trip ZIP; hidden when no exporter is wired, disabled while RECORDING or
busy) and an overflow: Runs…, Top view, Side view, Fit to path, Survey mode, Debug. Inside Survey
mode the bar is unchanged (north chip, Undo, ruler to leave, survey menu).

`SegmentedTabs`: Path, 3D, Graph, Details (`rememberSaveable`), hidden in Survey mode and while
there is no scene. The non-blocking `ui.error` line sits under the tabs. Outside Survey mode the
SnackbarHost is the `AppScaffold` one, so Share results show on every tab; in Survey mode the
canvas-local host above the survey panel stays as today. The LaunchedEffects that show messages
stay at screen level.

Canvas (Path: weight about 42 % of the content, 20 dp rounded clip and border; 3D and Survey: the
remaining height). Overlays use absolute alignment (D10):

- Top-right column: fit to path (`NearMe`, "Fit to path") and Survey mode (`SquareFoot`,
  "Survey mode").
- Bottom-right column: "3D"/"2D" text button (label from the VM camera: pure
  `OrbitCamera.isTopDown`; top-down shows "3D" → `applyPreset(THREE_D)`, otherwise "2D" →
  `applyPreset(TOP)`; "Switch to 3D view" / "Switch to top view"), Layers (the View options:
  colour by Progress / Time / Altitude / Source, floor grid, point cloud, markers, raw path and
  its hint; "View options"), Fullscreen / FullscreenExit (Path ↔ 3D tab; "Full height" /
  "Exit full height").
- Bottom-left `GridScaleChip` from a pure `gridChipText(scene, options)`: null without a scene or
  with the grid off, otherwise "<n> m grid" from `PathScene.gridSpacing(max(scene.bounds.size.x,
  scene.bounds.size.y))`. The world-space grid label leaves the scene so it is not shown twice.
- Path tab: the MarkerCard and the error line sit directly below the canvas, pinned above the
  scrolling cards, so the canvas neither resizes nor gets covered. 3D tab: they stay in a bottom
  overlay column with an end padding of about 64 dp so the buttons stay tappable; the grid chip
  hides while a card shows.
- Survey mode: the fit button and the grid chip stay (the chip in the bottom column between the
  snackbar and the survey panel, outside the height measured for `setBottomInset`); the survey
  panel overlays the canvas as today.

Scene look (A2): canvas background `#0A1424` (still dark: survey order numbers are drawn in it);
blue-tinted grid; a soft glow under the main path's lines, drawn by `PathRenderer` from a
path-line range on `SceneModel` (no extra line primitives; overlay runs get no glow); "Start" and
"End" labels next to those markers through `labelOrigin`, merged into "Start / End" when within
24 dp, and a label that would cover the other marker or its label moves to the other side of its own
marker (drawn from `scene.markers`, so they vanish with the markers and in Survey mode). The north
arrow and the axis triad stay.

Path tab below the canvas (scrolls; the canvas does not):

- "Trip Summary" card, `StatGrid(maxColumns = 3)`: Distance (`Route`, `formatDistance`),
  Duration (`Timer`, `formatDuration`; detail "incl. pauses" when the run's diagnostics record
  pauses), Steps (`DirectionsWalk`), Vertical (`Height`; value = range; detail "min … max",
  spoken "from <min> to <max>"), Closure (`TrackChanges`; value "0.52 m", detail "1.2 % of
  distance"; "—" without a loop mark), VIO (`SignalCellularAlt`; detail "of points").
- "Elevation" card: `ElevationChart` (height against distance, PROGRESS colours, zero line, y
  labels as Texts), wrapped in `LocalLayoutDirection provides Ltr`. Hidden for fewer than 2
  points or zero total distance.

Graph tab: the chart large, plus `StatGrid(maxColumns = 4)` tiles Min, Max, Net change (last z −
first z) with `formatHeight`, and Climb (≥ 0.5 m) and Descent (≥ 0.5 m) from
`PathProfile.climbs`. All from the shown result, so the raw view gets its own numbers.

Details tab: run list (select, overlay, labels), raw-path switch with hint, trip facts (mode,
carry, started, ended, raw log size, north reference). For a trip that ended unexpectedly (notes
equal the shared `RecordingController.ENDED_UNEXPECTEDLY_NOTE` constant, which
`finalizeOrphanedTrip` then uses) "Ended: unknown" with the warning, and "Last data ≈ start +
stats.durationS" when a run exists; `endedAtEpochMs` of such a trip is when the app noticed, not
when recording stopped. Buttons: Survey mode, Export ZIP (same as Share), Debug.

ViewModel:

- Optional last constructor parameter `shareTrip: (suspend (tripId: Long) -> ShareRequest)? = null`
  (TripExporter is final and needs a Context). `ShareRequest` is a pure
  `fun interface { fun launch(context: Context) }`, so the tests never build an Intent. ViewerScreen
  passes a lambda that exports and returns `ShareRequest { ctx -> ctx.startActivity(intent) }` around
  the export's `shareIntent`. `share()` ignores taps while busy, sets
  `busy`, stores `pendingShare` or the one-shot `message`, clears `busy`; `consumeShare()`.
- `options` default `ColorMode.PROGRESS`.
- `setViewport` also refits when the camera is still the last fit (the canvas height changes
  with the tab).
- A preset requested before any canvas was measured (restored onto Graph or Details, then Survey
  or Top/Side view) is kept as `pendingPreset` and applied by `fitIfPossible` once the viewport
  and the result are known.

Pure helpers (A2, `render/`): `PathProgress` (below), `PathProfile.elevation(points, maxBuckets =
256)` (distance from `PathProgress.cumulative`, min and max per bucket so peaks survive),
`PathProfile.axis(minZ, maxZ)` (symmetric nice ticks, ±1 m minimum band),
`PathProfile.climbs(points, deadBandM = 0.5)` (hysteresis over full-resolution points: a turning
point is confirmed only when z moves the dead band the other way; never summed per point, which
would count walking bob as climb).

`render/PathProgress.kt` defines PROGRESS once: `cumulative(points): DoubleArray` (3D, metres,
starting at 0, over every point) and `fractions(cumulative)` (divided by the total; below 1e-9
the index fraction; one point gives 0), with overloads for `List<Vec3>` and `List<PathPoint>`. It
is always computed on the full, undecimated list. `PathScene` colours a segment by its start
point's fraction (like TIME), separately for the overlay; `PathThumbnail` stores each kept
vertex's full-resolution fraction; `PathProfile` and the calibration preview use it too.
`SceneColors.PROGRESS_STOPS` holds the six stops.

### 6.4 Recording (work item E)

`RecordScreen` stays a pushed route with the same phases and back rules. On adoption,
`RecordViewModel` copies `mode` and `carryPosition` from `RecordingState.Recording` into
`RecordUiState`; from RECORDING on the screen reads only `ui.mode` and `ui.carry`, never the
route's mode (so a record route opened with the wrong mode still shows the running trip right).
`RecordUiState` gains `strideLengthM`, set in the existing `observeConfig` collector.

SETUP ("Ready to record"): `AppTopBar("Ready to record", "<mode>")` with back; a "Before you
start" GlassCard with the mode's start instructions and the north and pose guidance; "Phone
carried in" `BrandFilterChip`s in a FlowRow (fixes today's clipped "Helmet"; saved through the
existing `setCarry`); permission and error text; a large `BrandButton` "Start recording" with
progress. COMPASS: the existing `CompassDialog`, restyled through the theme.

RECORDING (header "Recording" or "Paused", subtitle "<mode> · <carry>"; no back arrow, Back
still opens the stop dialog). Layout:

- **Bottom block, pinned in every mode:** marks as a 3 + 2 grid of `FilledTonalButton`s
  (Junction, Chamber, Note / Back at start, Re-orient; `heightIn(min = 56.dp)`, labels wrap to
  two lines, never a scrolling row; Re-orient one tap, Back at start keeps its confirm, Note its
  text dialog); the "Volume keys also mark a waypoint" hint; the controls row: Pause/Resume and
  Flag as 64 dp circles ("Pause recording" / "Resume recording", "Mark waypoint") and the red
  "Stop Recording" pill, 64 dp tall (may shorten to "Stop"), keeping its confirm dialog.
- **Camera modes, above:** `ArSection` with `weight(1f)`, always composed (never in a lazy list
  or toggle), square corners (a Compose clip does not round a SurfaceView); below it a
  `weight(1f)` `verticalScroll` region with the status card, tiles and stats card.
- **Pocket, above:** one `weight(1f)` `verticalScroll` region with the status card, tiles and
  stats card; the stats card leads with a large h:mm:ss clock and the caption "The path is
  computed when you stop."
- **Status card:** status dot (decorative) and text, subtitle "Pocket · Hand · PDR" ("Camera +
  steps" for camera modes), battery icon and %. Status precedence, first match wins:
  (1) write error → red "Log write failed: <message>"; (2) accelerometer or gyroscope not
  delivering → red; (3) an available stall-monitored sensor stalled, or silent after 2 s of
  active time → amber, naming it ("Barometer not delivering"; two names; "<n> sensors not
  delivering"); (4) paused → amber "Paused" with "Sensors keep logging; this stretch is left out
  of the path and the timer."; (5) green "Sensors OK". Tapping expands the full per-sensor chip
  list (Role.Button, "Show all sensors" / "Hide sensor list", expanded state).
- **Tiles** (`StatGrid(maxColumns = 4)`, 2 × 2 when needed): IMU (OK / Stalled / None + accel
  rate), Steps (ON / None), Heading (OK from game rotation; "Fallback" when only the rotation
  vector delivers; Stalled; None), Mag (`ic_magnet`: None without a magnetometer; Stalled when
  stalled, never a stale accuracy; "Waiting" while accuracy is null; else High / Medium / Low /
  Unreliable / No contact with bars). Full words, spoken as "Magnetometer: unreliable, wave the
  phone in a figure 8".
- **Stats card** (`StatGrid(maxColumns = 3)`): Time (`formatClock`, active time; detail "excl.
  pauses" once paused), Steps, Marks; Distance ≈ (hardware steps × saved stride, detail
  "estimate · 0.70 m/step"), Cadence (steps per active minute). Without a step detector, Steps,
  Distance ≈ and Cadence show "—" with "counted after Stop", never 0.

STOPPING: the "Saving and processing the trip…" card as today; nothing on screen navigates
(processing runs in the ViewModel's scope).

Pure `ui/record/RecordStatus.kt`: status and precedence, processing label, tile states, the
estimate and cadence, from `SensorStats`, `RecordingState.Recording` and the stride.
`capture/CaptureSupport.kt`'s `formatElapsed` (pinned by `FormatTest` and used by the
notification) is not changed.

### 6.5 Settings, Calibration, Tuning, Debug (work item B)

- All use `AppScaffold`, `GlassCard` instead of `Card`, `SectionHeader`s with icons,
  `BrandButton` for filled buttons, `BrandFilterChip` for choice chips.
- Settings: tab, no back arrow; `ScreenHeader("Settings", "IMU Mapper <version>")`; sections
  Recording, Processing, Updates, Tools (Assisted tuning, Debug), About. The Updates pill comes
  from a pure `ui/settings/UpdatePill.kt` (state, unavailableReason) → label and tone or null:
  unavailable reason → "Debug build" Neutral; Idle → none (it also follows a quiet auto-check or
  "Later", so it never means up to date); Checking → "Checking…" Info; UpToDate → "Up to date"
  Success; Available / Downloading / ReadyToInstall / NeedsInstallPermission → "Update available"
  Info; Error without a release → "Check failed" Error; Error with a release → "Update failed"
  Error. The stepper keeps its semantics; its help text is unchanged (`ConfirmStepsHelpTest`).
- Calibration: tab, no back arrow; hides the bottom bar while a flow's sensors run. The path
  preview uses `SceneColors.START/END` instead of the dark `#2E7D32`/`#C62828`, draws segments
  with the PROGRESS ramp (`PathProgress.fractions`), and shows a "1 m grid" chip (its grid is
  1 m).
- Tuning and Debug: pushed with `AppTopBar`; cards restyled; logic untouched.
- `UpdateBanner`: GlassCard tone Accent with a `SystemUpdate` icon.

## 7. Work items and file ownership

| WI | Scope | Files |
|---|---|---|
| A1 | Theme, tokens, components, formatters, system bars, resources | `ui/theme/*`, `ui/common/*`, `MainActivity.kt`, `res/**`, tests in `test/.../ui/common/` |
| A2 | Render: `PathProgress`, PROGRESS mode and stops, grid tint, canvas background, glow range, Start/End labels, `PathProfile`, `PathThumbnail`, `OrbitCamera.isTopDown`, grid label removal | `render/*`, `test/.../render/*` |
| A3 | Cross-owner seam: routes, signatures, graph wiring, dialog removal | `ui/nav/*`, `ui/triplist/TripListScreen.kt`, `TripListDialogs.kt`, `TripListViewModel.kt`, `ui/newtrip/NewTripScreen.kt` (stub), `ui/calibration/CalibrationScreen.kt` and `ui/settings/SettingsScreen.kt` (signatures and bottomBar slot only) |
| B | Bottom bar, New trip page, Settings, Calibration, Tuning, Debug, UpdateBanner | `ui/nav/*`, `ui/newtrip/*`, `ui/settings/*`, `ui/calibration/*`, `ui/tuning/*`, `ui/debug/*`, their tests |
| C | Trips | `ui/triplist/*`, `data/db/Daos.kt`, `data/TripRepository.kt`, `data/TripThumbnails.kt` (new), `AppContainer.kt`, `test/.../data/Fakes.kt`, new tests |
| D | Viewer | `ui/viewer/*`, `test/.../ui/viewer/*`, and the one-line constant use in `capture/RecordingController.kt` (`ENDED_UNEXPECTEDLY_NOTE`) |
| E | Recording | `ui/record/*`, new tests |
| F | Docs | `README.md`, `CLAUDE.md`, `.claude/rules/app.md`, this file |

A1, A2 and A3 touch disjoint files and land first. B to E then run in parallel; each must pass
`:app:assembleDebug :app:testDebugUnitTest` on its own branch. `TripFormat`'s existing functions
keep their names, signatures and output during B to E (B and D import them); C may add functions.

F's changes to `.claude/rules/app.md`: the "Pure render and calibration code" bullet becomes "Pure
render and UI logic" and lists the new pure files; "A new screen" adds the bottom-bar item and
`bottomBar` slot for top-level screens; the "Runs are numbered" bullet notes the thumbnail cache
keyed by run id and that `TripArchive` does not export sidecars.

## 8. Tests

JVM unit tests (no Robolectric; logic in pure classes):

- A1: `UnitFormatTest` (0 → "0.0 m", 999.94 → "999.9 m", 999.95 → "1.00 km", 1234.5 → "1.23 km",
  null/NaN/-1 → "—"; durations; signed heights without "-0.0"; clock with negatives clamped),
  `BatteryFormatTest`, `StatColumnsTest`.
- A2: `PathProgressTest` (0 to 1, monotonic, matches `PathStats.distanceM` for the same points,
  coincident points give index fractions, empty and single point); `PathSceneTest` additions
  (PROGRESS first segment is the first stop; on the 20-point test circle the last segment is
  `gradient(PROGRESS_STOPS, 18.0/19)`; a standing pause keeps the colour under PROGRESS but not
  TIME; coincident points build without throwing; the default stays TIME; the grid label is gone;
  the path-line range covers exactly the main path, with and without an overlay and with the grid
  off; existing counts hold); `PathProfileTest` (distance monotonic from 0 to the total, min and
  max preserved, bucket limit, empty, single and coincident points, no NaN, axis ticks with the
  ±1 m band; climbs: a flat 10 Hz series with a ±3 cm 2 Hz bob gives 0 and 0, a 3 m staircase up
  and down with the bob gives about 3 and 3, a 0.4 m rise gives 0); `PathThumbnailTest` (finite and
  inside [0,1]², aspect kept, north goes up, first and last kept, ≤ max vertices, empty and single
  point, coincident points centred, a kept vertex's progress equals `PathProgress` at that point);
  `PathRendererTest` (label merge rule); `OrbitCameraTest` (`isTopDown` true after TOP and after
  orbiting to the clamp, false for THREE_D and SIDE).
- B: `RecordRouteTest` (a FLASHLIGHT recording while the default is POCKET routes to
  FLASHLIGHT; Idle and Stopping give null), `UpdatePillTest`, `NewTripViewModelTest` (recording
  state hides the choice; default mode followed until picked).
- C: `TripListQueryTest` (search, each filter including "FAILED with a run is Processed" and
  "FAILED without a run is Not processed", each sort with nulls last, combined),
  `TripListItemTest` (null, `"{}"`, partial and full statsJson; one-run rule),
  `RecordingModeForTest`, `TripThumbnailsTest` with temp directories (writes the sidecar; a
  second instance reads it with the run file deleted; missing folder or run gives null and creates
  no directory; corrupt or old sidecar regenerates; failures are remembered; a generation that
  finishes after the folder is deleted writes nothing).
- D: `ViewerViewModelTest` additions (default PROGRESS; refit after a viewport change while the
  camera is the last fit, none after the user moved it; share with a gated lambda: busy while
  suspended, a second tap ignored, `pendingShare` is the returned instance, consume clears it, a
  throwing lambda sets the message), `ViewerSurveyTest` additions (survey or TOP before the first
  viewport ends in the top view), `GridChipTextTest`, the subtitle split test; `ViewerTitleTest`
  unchanged.
- E: `RecordStatusTest` (each precedence step and their combinations, 2 s silence rule, sensor
  naming, Mag stalled with a stale accuracy, Mag waiting, tiles, estimate with the stride shown,
  cadence at zero time and while paused, no step detector gives dashes), `RecordViewModel`
  adoption test (a POCKET route adopting a FLASHLIGHT recording reports FLASHLIGHT and the
  recording's carry).
- Everything existing passes: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (JBR 21) and
  the pipeline suite from `pipeline/` (unchanged code, run as a guard).

## 9. Features that must stay reachable, and where they live now

| Feature | Before | After |
|---|---|---|
| Start a trip | FAB → dialog (mode, carry) → setup → Start | Record tab (mode) → Continue → Ready to record (carry) → Start recording |
| Return to a running recording | New trip with the same mode | Record tab badge → Return to recording; RECORDING card |
| Import trip | Trips top bar | Trips overflow; empty-state button |
| Calibration | Trips top bar | Calibrate tab |
| Assisted tuning | Calibration | Calibration and Settings → Tools |
| Debug (global) | Trips top bar | Trips overflow and Settings → Tools |
| Debug (trip) | Viewer top bar | Viewer overflow and Details tab |
| Settings | Trips top bar | Settings tab |
| Update banner | Trips | Trips (restyled) and the Settings tab badge |
| Rename / Export / Re-process / Delete | Long press | Card overflow and long press |
| Failed error + Re-process | Failed chip | Failed pill |
| Run selection and overlay | Runs menu | Overflow → Runs…, Details tab |
| Presets 3D / Top / Side / Fit | Preset menu | 3D/2D button, overflow (Top, Side, Fit), fit button, double tap |
| Colour mode, grid, cloud, markers, raw path | View menu | Layers button; raw also in Details |
| Survey mode | Ruler in the top bar | Ruler on the canvas, overflow and Details; inside Survey mode the ruler in the top bar |
| Whole-trip ZIP export | Trip list | Trip list and viewer Share / Details |
| Marker card, photo dialog, status overlay | Viewer | Viewer (restyled) |
| Annotations, Pause/Resume, Stop confirm, volume keys | Record | Record (restyled, same actions) |
| Full sensor list with rates | Record chips | Status card, expanded |
| Camera preview, tracking chip, torch, ARCore install/errors | Record | Record, camera slot |

## 10. Verification

1. Build and tests: `:app:assembleDebug :app:testDebugUnitTest` and the pipeline tests.
2. Emulator (scratch AVD, API 34, 1080 × 2400, night mode) with `tools/samples/*.imul` imported:
   screenshots of Trips (list, search with no match), New trip, Ready to record, Recording
   (Pocket), Viewer (Path, 3D, Graph, Details, Survey), Calibrate, Settings, Debug; compared with
   the mockup and the "before" shots.
3. The phone's width and large text: `adb shell wm density 450` (about 384 dp) at font scale 1.0
   and 1.3; the longest content (Illuminated name, Failed pill, 1:02:09, 999.9 m, five-digit
   steps, the update banner, the viewer bar with raw on and a Debug-labelled run).
4. RTL: Hebrew locale; header, cards, tiles, canvas overlays, chart, numbers.
5. Contrast of interactive boundaries (unchecked switches, search field, unselected chips).
6. Accessibility: `uiautomator dump` (or TalkBack) for tab roles and selection, badge states,
   tiles read as one item, the status card's expanded state.
7. Adversarial review of the whole diff (correctness, lifecycle and navigation, data honesty,
   RTL and accessibility, rules), then fixes.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Leaving the record route while processing cancels the run | The route stays pushed with no bar; nothing on the STOPPING screen navigates |
| A second recording entry with another mode | New trip hides its choice while recording; routes use the running mode; the record screen reads the adopted mode; double-tap guard |
| ARCore restarts if `ArSection` leaves composition | Always composed in the camera slot |
| Thumbnail memory and orphan folders | Points-only streamed decode, one at a time, OOM caught, sidecar reuse, no mkdirs, row check after the write |
| Stale camera after the canvas height changes | Refit rule and pending preset, with tests |
| Partial colour scheme or low contrast | All roles set; contrast rules in 4.1 and 4.4 |
| Light-mode users lose light mode | Stated in the PR |
| Tab switch cancels a calibration walk | Bar hidden while its sensors run |
| Experiment runs become the latest run | Cards and the viewer both show the latest run; the run label is not used as a PDR/VIO badge |
| RTL reordering | LTR numbers, absolute overlays, LTR charts |

## 12. Follow-ups (not in this change)

- A live path preview while recording, labelled as a preview.
- Live barometer height, speed and camera-tracking share on the recording screen.
- A light theme or a theme setting.

## 13. What the plan review changed

The first draft was reviewed by five agents, and each finding was challenged by a second agent;
45 of 48 findings stood. The main changes: the carry is chosen only on the setup step (no second
"New Trip" screen and no save-then-navigate race); Start is hidden while a recording runs and
every recording route uses the running mode; a pre-fork seam (A3) fixes the screen signatures so
the parallel work items compile on their own; `PathProgress` defines the ramp once; contrast-safe
primary and error colours with a separate `brandFill`; a real `outline`; measured stat grids and
card width budgets for 384 dp at font scale 1.3; the recording layout pins the marks and controls;
a shared `UnitFormat`; the Processed filter follows the run, not the status; card numbers come from
one run; honest status precedence and step-detector handling on the recording screen; the Graph
tab's climb and descent use a dead band; thumbnails never recreate deleted trip folders; the
share action is testable without Android classes; an accessibility contract for the custom
components.

## 14. As built

Implemented in work items A1 to F, each built and tested on its own branch, then reviewed by a
second agent before merging, then reviewed again as a whole. Differences from the text above, kept
on purpose:

- Text direction: every theme text style takes its direction from its own text and aligns to the
  layout's start, as Android's views do (`Theme.kt`); `layoutTextAlign()` gives screen-aligned
  columns. Without this, English lines were reordered on Hebrew phones.
- New trip page: the recording card is a title plus a status line (mode · clock · Paused), and the
  device card names the phone's maker and model next to the battery.
- Recording: in Pocket mode the stats card's large clock replaces the Time tile. The IMU and Heading
  tiles read "Waiting" during the first 2 s of active time, and the 2 s grace also covers the
  accelerometer and gyroscope, so a start never flashes a red fault. The saved carry no longer
  overwrites a running recording's carry or a chip the user has just picked.
- Viewer: in Survey mode the subtitle leaves out the date; Top view, Side view and Fit to path from
  the overflow switch to the Path tab first; Closure without a closed loop reads "—" with "no loop
  closed"; the Vertical detail shows signed numbers without a unit; the error line sits where each
  tab has room (under the tabs on Graph and Details, under the canvas on Path, over it on 3D).
- Trips: the stats row uses the shared `StatGrid` (icon and label, value below); the sort labels
  say their direction ("Distance (longest)").
- Calibration preview: the grid spacing follows the ground shown (1 m for the square test, wider on
  long tuning walks), and the chip names it.
- Components: `StatGrid` also balances rows (4 tiles become 2 × 2, 5 become 3 + 2); battery text is
  "92 %", like the app's other percentages; the Start and End labels merge within 24 dp, and past
  that a label that would cover the other marker or its label moves to the other side of its marker
  (and merges after all when that side is blocked too).
