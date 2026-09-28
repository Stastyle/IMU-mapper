---
paths:
  - "pipeline/**"
---

# Pipeline rules

## Raw log format (`log/LogFormat.kt`)

- **Enums stored as ordinal bytes are append-only:** `AnnotationKind`, `EventKind` and
  `TrackingState`. Never reorder them.
- **An unknown ordinal decodes as the enum's last entry,** and that entry has behaviour. A reader
  without a new entry sees a new `AnnotationKind` as `REORIENT` (a heading split) and a new
  `TrackingState` as `TRACKING` (the log counts as VIO). Check that this is acceptable before
  appending, or use a new record type instead.
- **Evolve the format by adding record types or appending payload fields.** Bumping
  `LogFormat.VERSION` makes every older build and `replay.py` reject new logs.
- **An appended field must be optional on read.** Check `len` before decoding it and use a default
  when it is absent. Otherwise the read underflows, `forEachRecord` counts the record as corrupt,
  and every record of that type in existing logs is silently dropped. Do the same in `replay.py`,
  and add a `LogCodecTest` case with an old-length payload.
- **A new record type needs changes in several places:**
  - `LogFormat`: the constant and the KDoc table.
  - A `LogRecord` subtype in `core/Samples.kt`.
  - `LogWriter`, and `LogReader.decode` plus `KNOWN_TYPES`.
  - `RawLog`: the field and the `Builder`.
  - `replay.py`: `T_*`, `TYPE_NAMES`, `_decode`, and the attribute in `parse_imul`/`RawLog`.
  - The stream counts in `DebugViewModel.summarise`.
  - A round-trip case in `LogCodecTest`.
- **Enums written by name** go into:
  - `LogMeta` JSON (`TripMode`, `CarryPosition`, `HeadingAxisMode`).
  - `PathResult` JSON (`PositionSource`, `AnnotationKind`).
  - Room columns (`TripMode`, `CarryPosition`).

  A new value makes older builds lose `LogMeta`. **Never rename a constant**, or the current build
  loses the `LogMeta` of existing logs and fails to decode stored runs and rows.

## Memory

- A long trip holds millions of samples. Some `RawLog` streams are column-backed lists that build
  an object on each `get`: accel, gyro, mag, the three uncalibrated streams, baro, rotation and
  steps. Iterate or index them, and never copy one into another collection. Poses, point clouds,
  keyframes, annotations and events are plain lists.
- Processing reads with `LogReader.UNCALIBRATED_TYPES` skipped.
- `RawLog` lists are in file order, not time order. Records come from several threads, so sort
  them yourself when you need time order.

## Results and post-processing

- **Post-processing must not add, drop or reorder points:** `RawPath` aligns raw and final points by
  index.
- **A step that changes nothing must return the same list instance,** because `rawPoints` is stored
  only when `points !== rawPoints`.
- **`PathResult` JSON allows no NaN or Infinity.** `toJson()` throws on them, so the run cannot be
  stored.
- **Diagnostics must be deterministic:** use insertion-ordered maps, with values formatted through
  `Diag.num` (Locale.US).
- **`Quat` is w-first.** Android and ARCore quaternions are xyzw, so go through
  `fromAndroidRotationVector` or `fromXyzw`.

## Change checklists

- **Bump `PIPELINE_VERSION`:**
  - Mirror it in `PathBuilder.PIPELINE_VERSION` in `tools/replay.py`.
  - Update the literal in `PdrProcessorTest`'s `pipelineVersion` assertion.
  - Never go below 2: the viewer's raw-path view needs version 2 or later.
- **New or changed `PipelineConfig` field:**
  - `tuning/ConfigSchema.kt` (fields and `valueText`) and the `when` in `tuning/ProposalParser.kt`.
  - `app/.../ui/debug/ConfigFields.kt` (enum, `ConfigDraft.from`, `parse`, `differs`) and
    `ConfigFieldsTest`. Keep the ranges equal to `ConfigSchema`.
  - `CONFIG_DEFAULTS` in `tools/replay.py`.
  - `app/src/main/assets/tuning/master_prompt.md`, if the chat model should reason about it.
  - Never rename a field without `@JsonNames`.
- **Change in `pdr/` or `post/`:**
  - Port it to the same-named class in `tools/replay.py`. The Kotlin code is the reference.
  - Compare the two with `replay.py --compare` against a Kotlin result. Without a phone, add a
    temporary test that writes
    `DefaultProcessor().process(log, log.meta!!.config).toJson()` for a `tools/samples/*.imul`
    log to a file, then delete the test.
  - Update "How the pipeline works" in `master_prompt.md` and the parity numbers in
    `tools/README.md`.
  - `VioProcessor` repeats the loop-closure and smoothing step and uses `PdrSolver` for gap fill,
    so run the VIO tests too.
- **Renamed diagnostics key:**
  - Update the tests, `CalibrationMath.HEADING_AXIS_DIAG`, `master_prompt.md`, `replay.py` and
    `tools/samples/README.md`.
  - VIO trips repeat the `PdrSolver` keys with a `pdr.` prefix.
  - `loopClosure` is written by both `PdrProcessor` and `VioProcessor`, so rename it in both.
  - `northReference` and its values `magnetic` and `relative: ` (a prefix followed by the reason)
    are read by the app: `CalibrationMath` through the `OrientationEstimator` constants
    (`NORTH_REFERENCE`, `MAGNETIC`, `RELATIVE`), and the heading calibration, which refuses a walk
    whose north is not magnetic. Use the constants, never a literal, so a rename reaches the app.
    `VioProcessor` copies the key through the same constant, and `replay.py` has its own copies
    (`NORTH_*`).
  - `yawCorrectionFinalDeg` is read by the heading calibration through
    `OrientationEstimator.YAW_CORRECTION_FINAL` to reject a walk on which the compass moved.
  - `northOffsetDeg` and `northReferenceAtS` are written in `OrientationEstimator.correctYaw` and
    in `correct_yaw` in `replay.py`, in the same order, and `master_prompt.md` explains them.
    `ConfigSchema` descriptions also name `northReference` and `northOffsetDeg`.
- **New value in a core enum:**
  - The compiler lists the app's exhaustive `when`s, for example `PathScene`,
    `CalibrationFormat`, `CaptureSupport`, `RecordScreen`, `SettingsScreen` and
    `RecordViewModel.annotate`.
  - Also grep for `EnumName.` to find the `==`/`!=` checks the compiler cannot catch. Examples:
    `mode != TripMode.POCKET`, the mode checks in `ArSessionManager` and `ArSection`, the
    `headingAxis ==` branches in `PdrSolver`, and the `TrackingState` checks in `RawLog.hasVio` and
    `vio/TrackingRuns`.
  - For the ordinal enums, append to `ANNOTATION_KINDS`, `EVENT_KINDS` or `TRACKING_STATES` in
    `replay.py`.
- **`SyntheticWalk` or `SampleLogTest` scenarios, or how `LogWriter` encodes a record the samples
  contain:** regenerate `tools/samples/*.imul` from `pipeline/` with
  `IMU_MAPPER_SAMPLES_DIR=../tools/samples ../gradlew test --tests '*SampleLogTest*' --rerun`.
  Gradle does not track the env var, so without `--rerun` the test can be skipped. Then update
  `tools/samples/README.md`.

## Tests

- Tests use deterministic synthetic walks (`SyntheticWalk`, `SyntheticVio`) with tolerance checks,
  not golden files.
- Run one class with `(cd pipeline && ../gradlew test --tests '*ClassName')`.
