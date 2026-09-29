# Survey mode: debrief a recorded walk

Status: agreed with the user on 2026-09-29. Phase 1 is specified in full here; phases 2 and 3 are
outlined at the end.

## Goal

After a recording, at home, the user wants to pull numbers out of the path: the distance, azimuth
and slope between points they choose, and between the turns of the path even where they marked
nothing while walking. They also want to correct north when the phone's reading was off, using
bearings they took with a hand compass. The results leave the phone as a CSV table and, in phase 2,
as a plan image.

## Decisions the user made

| Question | Answer | Consequence |
|---|---|---|
| Where is the debrief done? | At home, after the trip | A dense panel is fine and two-handed gestures are allowed. No dim or red theme. |
| Where do known bearings come from? | A hand compass, magnetic | Everything is magnetic. No declination, no true or grid north. |
| Are the marks made while walking the stations? | Not always: often nothing is marked | Automatic turn (corner) detection is part of phase 1, not a later add-on. |
| What kind of walks? | All kinds: out-and-back, loops, one-way | Phase 3 adds "same place" ties that correct drift and stride. |
| What output? | A CSV table and a plan image | No Survex or Therion export, no passage widths (LRUD), no walls. |

Angles are in degrees. Mils are not offered unless the user asks.

## The concept

The viewer gets a Survey mode. It opens as a north-up plan. The marks made while walking
(waypoints, junctions, chambers, notes) and the start and end are named stations. Every turn of
the path gets an automatic station too, so a trip with no marks still has something to measure
between. A tap on a stretch of path between two stations measures that stretch. Taps on stations
build a chain and measure between them. A leg is always the straight line between two stations,
and a curved stretch just needs more stations.

North is corrected with facts the user knows: "this stretch reads 045° on my compass", or a manual
rotation. The whole map turns about the start. Nothing is re-processed, no run is written and the
database is not touched: the survey is a small per-trip file layered over the run being viewed.

## Phase 1 in detail

### Entering and leaving

- A ruler icon in the viewer top bar, next to the run, preset and view menus, toggles Survey mode.
- Entering applies the Top preset (north up). One-finger drag pans instead of orbiting, pinch
  zooms, double-tap fits. Leaving restores orbit and keeps the camera where it is.
- In Survey mode the stats panel is replaced by the survey panel, and the overlay run is hidden.
  The raw-path toggle still works: stations are placed by time, so they follow it.
- The mode is labelled "Survey (beta)" until phase 2.

### Stations

A station is a moment of the walk: it is stored by its time (`tNs`) and placed on whichever path is
shown. Kinds:

| Kind | Where it comes from | Default name |
|---|---|---|
| START, END | First and last point of the path | "Start", "End" |
| MARK | WAYPOINT, JUNCTION, CHAMBER and NOTE annotations | The note when it has one, else "Junction 2" etc. |
| CORNER | Automatic turn detection | "C1", "C2"… in time order when generated |
| USER | Added by the user | "S1", "S2"… |

- The first time Survey mode opens on a trip, it seeds START, END, MARK and CORNER stations and
  saves them. REORIENT and LOOP_CLOSED annotations are not stations.
- The traverse is the list of stations in time order. Consecutive stations form the legs.
- Stations can be renamed and deleted. A renamed CORNER becomes a USER station, so a Detail change
  keeps it and its name. Deleting a CORNER keeps it deleted until the Detail setting changes.

### Automatic corners

- Run Ramer-Douglas-Peucker on the plan (east, north) of the shown path, with the tolerance set by a
  Detail control: Coarse 1.0 m, Normal 0.5 m (default), Fine 0.25 m.
- Keep only vertices where the direction turns by at least 20°. Remove the weakest vertex and
  re-check until every remaining vertex passes.
- Drop a corner within 1.5 m of path distance of another station. No leg shorter than 1.5 m.
- Changing Detail regenerates the CORNER stations. Corners the user moved or renamed have become USER
  stations, so they are kept. The panel shows how many corners the setting gives.
- A vertex is always an existing path point, so a corner is a place where the user actually stood.

### Gestures in Survey mode

Checked in this order:

1. **Tap on a station** (within the existing 24 dp marker radius): append it to the chain
   selection. Tapping the last selected station again removes it.
2. **Tap on the path** (within 32 dp): select the stretch between the stations on either side of
   the tapped moment, and move the cursor there. This replaces a chain selection.
3. **Tap elsewhere**: nothing. Clearing is an explicit button, so a near miss never loses a
   selection.
4. **Long-press on the path**: add a USER station there, with an Undo snackbar.
5. **Long-press on a station**: a sheet with Rename and Delete.

Stations lie on the path, so station hits must win over path hits.

### The survey panel

```
┌──────────────────────────────────────────┐
│ ←  Trip name · Survey (beta)   N +4.0° ⋮ │  ⋮: Export CSV · Detail
├──────────────────────────────────────────┤
│              (plan, north up)            │
├──────────────────────────────────────────┤
│ Junction 2 › C3 › Chamber        [Clear] │  selection
│  34.2 m        047° M        ▲ +7°       │  headline: length, azimuth, slope
│ horiz 33.9 · Δh +4.1 · 12 % · path 38.5⌒ │  details
│ fitted 046° M · straight 0.89            │  stretch selection only
├──────────────────────────────────────────┤
│ ◀ ────────●──────────────────────── ▶    │  scrubber: distance along the path
│ 12:40:18 · 41.2 m         [+ Station]    │
├──────────────────────────────────────────┤
│   [ Legs ]      [ Set azimuth ]          │
└──────────────────────────────────────────┘
```

- **Chain selection:** each hop, then totals: the sum of the hop lengths, the sum of the path
  lengths, and the straight line from the first station to the last.
- **Scrubber:** a slider over distance along the path (standing still takes no room). It moves
  the cursor drawn on the map. ◀ ▶ step one path point. It is also how to pick a point where the
  path passes the same place twice (out and back). **+ Station** adds a USER station at the cursor.
  With one CORNER or USER station selected, **Move here** moves it to the cursor (a moved CORNER
  becomes USER). The label's clock counts from the raw log's START event, when Start was pressed,
  not from the path's first point, so it matches a note or photo taken underground on a VIO run too.
  Without a readable raw log it shows the time since the path's start instead.
- **Legs:** a table of the traverse with From, To, Length, Azimuth, Slope, Δh and Path, a ⌒ mark on
  curved legs and a totals row. Tapping a row selects that leg.
- **Copy:** a long-press on the readout copies one line, for example
  `Junction 2 → Chamber: 34.2 m, horiz 33.9 m, 047° M, +7° (Δh +4.1 m), path 38.5 m`.

### What is measured

For endpoints A and B, placed at their times on the shown, north-corrected path, d = B − A:

| Field | Definition | Shown as |
|---|---|---|
| Length | \|d\| | 0.1 m |
| Horizontal | H = √(dE² + dN²) | 0.1 m |
| Height change | dz | 0.1 m, signed |
| Azimuth | atan2(dE, dN) in [0, 360), clockwise from north | 1°, suffixed M or R; "—" when H < 0.3 m |
| Slope | atan2(dz, H), up is positive | 1°, ▲ or ▼; greyed when H < 5 m |
| Grade | 100·dz/H | 1 %; "—" when H < 0.3 m |
| Path length | 3D length along the path between the two times | 0.1 m |
| Curved (⌒) | The path strays from the chord by more than max(0.3 m, 2 % of the length) | mark |
| Fitted azimuth | Total-least-squares line through the stretch's plan points | stretch selection only |
| Straightness | Length / path length | 2 decimals, stretch selection only |

- **M or R suffix.** The suffix is **M** when the run's `northReference` diagnostic equals
  `OrientationEstimator.MAGNETIC`, or when a compass reference is applied. Otherwise it is **R**,
  including runs whose diagnostics lack the key: an unknown north is never shown as magnetic. On an R
  trip without references, a banner says north is arbitrary and suggests Set azimuth. The reason text
  comes from `CalibrationMath.northProblem`.
- **Placing a time on a PDR path.** A PDR path has one point per step. Between two steps with a long
  gap (standing, or a pause), the walker stayed at the earlier step. So between points i and i+1 the
  position holds at pᵢ, then moves to pᵢ₊₁ over the last min(gap, 1.5 × the median step period) of
  the gap. On a VIO path, placement is linear.
- **Fitted azimuth.** Resample the plan points of the stretch every 0.25 m of path, then fit
  φ = ½·atan2(2·C_EN, C_EE − C_NN) and orient it from A to B. Height is left out, so barometer noise
  cannot tilt it. Why a leg's headline azimuth is the chord and not the fit: dead-reckoning error
  accumulates along the walk rather than scattering around a line, and chords chain, so the sum of
  the legs equals the net displacement.

### North correction

The survey stores facts; the rotation is solved from them for whichever run is shown.

- **Manual rotation**, in degrees, with −5, −0.5, +0.5, +5 steppers and a typed value. It is
  tagged with the run id it was set on. On another run the app asks whether to apply it there too,
  because a re-process can change the heading offset.
- **Compass references.** Each is a pair of times (from, to), the bearing read on the hand compass
  (magnetic), a back-bearing flag (the bearing was taken from B to A), and whether it describes the
  line from point to point (default) or the direction of the passage (the fitted line). A
  back-bearing adds 180° before use.
- **Solve.** Mₖ is the measured azimuth of reference k on the uncorrected shown path, Rₖ its
  compass bearing, hₖ its horizontal length.

  ```
  θ = atan2( Σ hₖ·sin(Rₖ − Mₖ), Σ hₖ·cos(Rₖ − Mₖ) )   if any reference exists
  θ = manual rotation                                   otherwise
  residualₖ = wrap(Rₖ − Mₖ − θ)
  ```

  One reference is matched exactly. With two or more, a residual over 3° shows "References
  disagree: drift during the walk, or a misread bearing". The manual steppers are disabled while
  references exist.
- **Apply.** Rotate about the first path point O, clockwise on the map for positive θ, so every
  azimuth grows by θ:

  ```
  x' = Ox + (x − Ox)·cosθ + (y − Oy)·sinθ
  y' = Oy − (x − Ox)·sinθ + (y − Oy)·cosθ
  z' = z,  heading' = wrap(heading + θ)        check: θ = 90° takes north (0, 1) to east (1, 0)
  ```

  It applies to `points`, `rawPoints`, annotations, keyframes (position and heading) and
  `pointCloud`. Stats are unchanged by a rotation. It is a pure `PathResult → PathResult` view,
  applied after the raw view and before the scene is built, and cached per run, raw flag and θ.
- **Why no new run is needed.** A rotation about any pivot commutes with
  `LoopClosure.apply` (it subtracts a distance-weighted fraction of the closure error vector, and
  distances are unchanged by rotation) and with `Smoothing.movingAverage` (an average with weights
  summing to 1). So rotating the output equals rotating inside the pipeline. Tests assert both.
- **The Set azimuth dialog** opens from a selected pair or stretch. It shows the current reading
  (chord and fitted), a field for the compass bearing, the back-bearing box, and "The map turns
  +4.0° about the start". It warns when the stretch is shorter than 10 m horizontally, when it is
  crooked (straightness under 0.9, or chord and fit differ by more than 3°), when the change is over
  15°, and when it is over 45° ("back-bearing?", or "check the reading and the box" when the box is
  ticked). The two change warnings are left out while north is arbitrary (a relative-north run with no
  reference and no manual rotation in use), since the first reading there may turn the map by any
  angle, and a warning would push the user to tick the back-bearing box wrongly.
- **The north chip** in the top bar shows the rotation in use ("N +4.0° M"). It opens the North
  sheet, whose header adds how many references set it ("N +4.0° M · 2 refs"): the manual steppers,
  each reference with its residual and a delete button, and Reset north. The count stays off the
  chip so the trip name and "Survey (beta)" keep room on a narrow phone.
- **Undo.** Every survey edit (stations, references, manual rotation, Detail) goes on an in-memory
  undo stack of about 50 snapshots, behind the top-bar Undo and the snackbar.

### Storage

- `files/trips/<id>/survey.json`, next to `raw.imul`. No Room change, so no version bump, no
  Migration and no risk of wiping trips on update.
- It holds facts only: stations (id, kind, name, `tNs`), references, the manual rotation and its
  run id, and Detail. Every field has a default, it is read with `ignoreUnknownKeys`, and it holds no
  NaN and no derived value. A `formatVersion` field starts at 1.
- Written to a temp file and renamed after each edit (`AtomicFiles`). The temp file is synced to the
  disk before the rename, so a power cut just after an edit cannot leave a renamed file of zeros.
- A malformed file puts Survey mode into read-only with an error, and is never overwritten. The
  error banner's Start over renames it to `survey.json.bad-<n>`, keeping it, and seeds a new survey.
- A file with a newer `formatVersion` (from a later app, say through a ZIP import) opens read-only
  with its own stations and an error asking for an update, and is never overwritten, because saving
  it would drop the fields this build does not know. It offers no Start over.
- It is user state, not a run, so "runs are never overwritten" still holds. Deleting a trip removes
  its directory and so the file.
- The ZIP export includes it, and the import restores it. `MANIFEST_VERSION` stays 1; an older build
  ignores the file.

### CSV export

- From the Survey overflow menu, through the share sheet, with the same cache and FileProvider path
  as the trip ZIP export.
- One table, one header row, UTF-8 with a byte-order mark so Excel shows Hebrew names, numbers
  formatted with `Locale.US`.
- One row per leg of the traverse: `from, to, from_s, to_s, length_m, horizontal_m,
  height_change_m, azimuth_deg, north, slope_deg, grade_pct, path_m, curved, to_east_m,
  to_north_m, to_up_m`. Times are seconds since the Start station, the path's first point, and
  coordinates are relative to it, in the corrected frame. On a VIO run that point is the first
  tracking pose, which can come seconds after Start was pressed. Empty cell where a value is "—".
- A station name that starts with `=`, `+`, `-` or `@` is written with a leading apostrophe, so Excel
  shows the name (with the apostrophe) instead of `#NAME?` or a formula.
- The share text names the trip, the run and the correction, for example "Run 3, north +4.0°
  from 2 compass readings".

### Where the code goes

- **`pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/`**, a pure package like
  `tuning/`: code the processor never runs, so no `PIPELINE_VERSION` bump and no `replay.py` port.
  It holds the survey document model, time placement along a path, measurements, the line fit,
  corner detection, the north solve and frame, the traverse and the CSV text.
- **`app/`:**
  - `data/`: the survey file store, plus the `TripFiles`, `TripArchive` and `TripImporter` changes.
  - `render/`: a small survey layer (stations, selected chords, the highlighted stretch, the
    cursor, and a decimated copy of the path with times for hit-testing), projected with the same
    camera and drawn over the path scene. Survey edits rebuild only this layer.
  - `ui/viewer/`: a pure survey controller (selection, cursor, edits, undo) owned by
    `ViewerViewModel`, and the panel, sheets and dialogs. Canvas gestures gain tap coordinates,
    long-press and an orbit lock.
- No new dependency.

### Tests

- **pipeline:**
  - Azimuths: N, E, S and W read 0, 90, 180 and 270.
  - A vertical leg reads "—" and ±90°.
  - A rotation of +10° raises every azimuth by 10°.
  - One reference is matched exactly, and two give their residuals. Back-bearing works.
  - The rotation commutes with loop closure and with smoothing.
  - The line fit on a swaying straight walk.
  - Corners of a rectangle walk are found at each Detail level.
  - Time placement holds across a standing gap and a pause.
  - The survey document survives a JSON round trip, ignores unknown keys, and has no NaN.
  - CSV text is exact.
- **app:**
  - The store in a temp directory: missing, malformed and atomic write.
  - `TripArchive` round trip with and without the survey file.
  - The survey layer and the path hit test.
  - The controller: select, stretch, add, move, delete, undo.

## Later phases

2. **Plan image and profile.**
   - Share a PNG of the plan with the legs labelled ("34.2 m · 047° · ▲7°"), station names, a scale
     bar, a north arrow marked M, and a line stating the correction.
   - A profile view (distance along the path across, height up) for slopes.
   - Slope breakpoints in corner detection, so a flat-then-steep stretch splits.
3. **Same place ties and drift.**
   - "Same place" facts between two moments at one spot: suggested automatically on out-and-back
     walks (the same corners in reverse order with mirrored turns), or picked by hand. LOOP_CLOSED
     counts as one.
   - Solve a heading drift rate and a stride scale from the ties, combined with the compass
     references by least squares. Show each fact's residual so a bad one stands out.

## Not planned

Survex or Therion export, passage widths and walls, true or grid north and declination, mils, a
dim theme, a Station button in the recorder.

## Release notes

Every merge to `main` ships to phones. Phase 1 is one PR titled for the user, for example "Measure
distance, azimuth and slope between points of a recorded trip, and correct its north (beta)".
