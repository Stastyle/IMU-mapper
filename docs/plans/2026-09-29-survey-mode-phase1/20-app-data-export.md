## Section B: App data and export (store, ZIP, CSV share)

Tasks 12 to 14 add Survey mode's file plumbing to `com.stastyle.imumapper.data`: the per-trip
`survey.json` store, the ZIP export and import of that file, and the CSV file plus its share intent.
Nothing here touches Room, the pipeline or the UI.

**Before you start this section:**

- Section A tasks 1 and 11 must be committed. The tests below import `SurveyDoc`, `Station`,
  `StationKind`, `CompassReference`, `ReferenceLine`, `Detail` and `SurveyCsv` from
  `com.stastyle.imumapper.pipeline.survey`. Check with:

  ```bash
  ls pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDoc.kt \
     pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyCsv.kt
  ```

  The app picks the pipeline up through the composite build, so you do not publish anything.
- Every Gradle command below needs the `JAVA_HOME` / `ANDROID_HOME` prefix from the plan header.
- Line numbers on Replace steps are the file as the previous step left it, as in Section D, so a
  second replacement in the same file is already shifted by the first. The **Files:** lists give the
  lines of the file before the task. Find each snippet by its text, which matches exactly once.
- Only failing tests print. A passing run ends with `BUILD SUCCESSFUL`. With Kotlin 2.1 (K2) a missing
  symbol is reported as `e: file:///X:/IMU-mapper-survey/...Test.kt:<line>:<col> Unresolved reference 'Name'.`
  followed by `> Task :app:compileDebugUnitTestKotlin FAILED` and `BUILD FAILED`.
- App tests use kotlin.test on JUnit 4 (`@BeforeTest` / `@AfterTest`, temp dirs from
  `Files.createTempDirectory`), as `TripArchiveTest` does. `assertIs` is part of kotlin.test.
- **Windows note:** `File.renameTo` on Windows fails when the target already exists. On this machine,
  every save that overwrites a file therefore goes through `AtomicFiles`'s fallback (a direct write,
  then the temp file is deleted). On Android and on the Linux CI runner the rename itself succeeds.
  The tests pass on both paths, and you do not need to change anything for Windows.

**Interface notes (no signature changed):**

- `SurveyStore.load` puts only the **first non-blank line** of the exception message into
  `SurveyLoad.Malformed.message`. kotlinx.serialization appends `JSON input: ...` on later lines,
  and the controller embeds the message in a one-line error. If the message is null or blank, the
  exception's class name is used instead, as the architecture says.
- `ExportNames.safeStem` keeps the existing order (replace, trim `_`, then cut to 40), so ZIP names
  are byte-for-byte unchanged. A cut that lands on a `_` can therefore leave one at the end.

---

### Task 12: Survey store with atomic writes

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/data/AtomicFiles.kt`
- Create: `app/src/main/kotlin/com/stastyle/imumapper/data/SurveyStore.kt`
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/data/TripFiles.kt:6-20` (layout KDoc), `:37`
  (new function after `photosDir`), `:60-64` (companion)
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/process/TripProcessor.kt:3` (import), `:103`
  (call site), `:150-160` (delete `writeAtomically`)
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/AppContainer.kt:6-7` (import), `:55` (new val)
- Test: `app/src/test/kotlin/com/stastyle/imumapper/data/SurveyStoreTest.kt` (create)
- Regression: `app/src/test/kotlin/com/stastyle/imumapper/process/DefaultTripProcessorTest.kt`
  (unchanged, must still pass)

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/data/SurveyStoreTest.kt`:

```kotlin
package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import kotlinx.serialization.SerializationException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** survey.json in a temp directory: what load reports, and that save never leaves half a file behind. */
class SurveyStoreTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles
    private lateinit var store: SurveyStore

    private val tripId = 7L

    private val doc = SurveyDoc(
        stations = listOf(
            Station(id = 1, kind = StationKind.START, name = "Start", tNs = 1_000_000_000L),
            Station(id = 2, kind = StationKind.MARK, name = "Junction 1", tNs = 6_000_000_000L),
            Station(id = 3, kind = StationKind.END, name = "End", tNs = 16_000_000_000L),
        ),
        references = listOf(
            CompassReference(
                id = 1,
                fromNs = 1_000_000_000L,
                toNs = 11_000_000_000L,
                bearingDeg = 47.5,
                backBearing = true,
                line = ReferenceLine.FITTED,
            ),
        ),
        manualRotationDeg = -2.5,
        manualRotationRunId = 3,
        detail = Detail.FINE,
    )

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-survey").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
        store = SurveyStore(files)
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun surveyFileSitsNextToTheRawLog() {
        assertEquals(File(files.tripDir(tripId), "survey.json"), files.surveyFile(tripId))
        assertEquals(files.rawLog(tripId).parentFile, files.surveyFile(tripId).parentFile)
    }

    @Test
    fun missingFileIsMissing() {
        assertEquals(SurveyLoad.Missing, store.load(tripId))
        assertFalse(files.surveyFile(tripId).exists(), "loading never creates the file")
    }

    @Test
    fun saveThenLoadRoundTrips() {
        store.save(tripId, doc)
        assertEquals(SurveyLoad.Loaded(doc), store.load(tripId))
        assertEquals(doc.toJson(), files.surveyFile(tripId).readText())
    }

    @Test
    fun saveLeavesNoTempFileBehind() {
        store.save(tripId, doc)
        assertEquals(listOf(TripFiles.SURVEY_NAME), files.tripDir(tripId).list()!!.toList())
    }

    @Test
    fun secondSaveReplacesTheFirst() {
        store.save(tripId, doc)
        val edited = doc.copy(stations = doc.stations.dropLast(1), detail = Detail.COARSE)
        store.save(tripId, edited)
        assertEquals(SurveyLoad.Loaded(edited), store.load(tripId))
        assertEquals(listOf(TripFiles.SURVEY_NAME), files.tripDir(tripId).list()!!.toList())
    }

    @Test
    fun docThatCannotBeEncodedLeavesTheSavedFileAlone() {
        store.save(tripId, doc)
        assertFailsWith<SerializationException> { store.save(tripId, doc.copy(manualRotationDeg = Double.NaN)) }
        assertEquals(SurveyLoad.Loaded(doc), store.load(tripId))
        assertEquals(listOf(TripFiles.SURVEY_NAME), files.tripDir(tripId).list()!!.toList())
    }

    @Test
    fun invalidJsonIsMalformedAndLeftAsItIs() {
        val bad = "{ \"stations\": [ oops"
        files.surveyFile(tripId).writeText(bad)
        val load = assertIs<SurveyLoad.Malformed>(store.load(tripId))
        assertTrue(load.message.isNotBlank())
        assertFalse('\n' in load.message, "one line for the error text: ${load.message}")
        assertEquals(bad, files.surveyFile(tripId).readText())
    }

    @Test
    fun jsonOfTheWrongShapeIsMalformed() {
        files.surveyFile(tripId).writeText("[1, 2]")
        assertIs<SurveyLoad.Malformed>(store.load(tripId))
        assertEquals("[1, 2]", files.surveyFile(tripId).readText())
    }

    @Test
    fun directoryInTheFilesPlaceIsMalformed() {
        val dir = files.surveyFile(tripId).also { it.mkdirs() }
        val load = assertIs<SurveyLoad.Malformed>(store.load(tripId))
        assertTrue(load.message.isNotBlank())
        assertTrue(dir.isDirectory, "the directory is left as it is")
    }
}
```

**Step 2: Run the test and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.SurveyStoreTest'`

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with errors including
`Unresolved reference 'SurveyStore'.`, `Unresolved reference 'SurveyLoad'.`,
`Unresolved reference 'surveyFile'.` and `Unresolved reference 'SURVEY_NAME'.`

**Step 3: Create `AtomicFiles`**

This is `TripProcessor.writeAtomically`, moved into its own file and unchanged except that the charset
is now written out. The survey CSV's byte-order mark depends on the text being written as UTF-8.

Create `app/src/main/kotlin/com/stastyle/imumapper/data/AtomicFiles.kt`:

```kotlin
package com.stastyle.imumapper.data

import java.io.File

/**
 * Writes through a temp file and a rename, so a crash or a reader sees the old file or the new one
 * rather than half of one: run results, survey.json and the survey CSV.
 */
object AtomicFiles {
    /**
     * Writes [text] (UTF-8) to "<target>.tmp", then renames it over [target]. When the rename fails it
     * falls back to writing [target] directly and removes the temp file.
     */
    fun writeText(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Rename can fail across some file systems, and on Windows (where unit tests may run)
            // whenever the target exists; a plain write is the fallback.
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private const val TMP_SUFFIX = ".tmp"
}
```

**Step 4: Give `TripFiles` the survey file**

In `app/src/main/kotlin/com/stastyle/imumapper/data/TripFiles.kt`:

Replace (layout KDoc, lines 12-13):

```kotlin
 * files/trips/<tripId>/photos/<name>.jpg    keyframes
 * cache/export/                             ZIPs for the share sheet
```

with:

```kotlin
 * files/trips/<tripId>/photos/<name>.jpg    keyframes
 * files/trips/<tripId>/survey.json          Survey mode facts (SurveyStore)
 * cache/export/                             ZIPs for the share sheet
```

Replace (line 38):

```kotlin
    fun photosDir(tripId: Long): File = File(tripDir(tripId), PHOTOS_DIR_NAME).also { it.mkdirs() }
```

with:

```kotlin
    fun photosDir(tripId: Long): File = File(tripDir(tripId), PHOTOS_DIR_NAME).also { it.mkdirs() }

    /** Survey mode's facts for the trip; absent until Survey mode first opens on it. */
    fun surveyFile(tripId: Long): File = File(tripDir(tripId), SURVEY_NAME)
```

Replace (companion, line 67):

```kotlin
        const val PHOTOS_DIR_NAME = "photos"
```

with:

```kotlin
        const val PHOTOS_DIR_NAME = "photos"
        const val SURVEY_NAME = "survey.json"
```

**Step 5: Create the store**

Create `app/src/main/kotlin/com/stastyle/imumapper/data/SurveyStore.kt`:

```kotlin
package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.survey.SurveyDoc

/** What reading a trip's survey.json found. */
sealed interface SurveyLoad {
    /** No file yet: Survey mode has never been opened on this trip, so it seeds. */
    data object Missing : SurveyLoad

    data class Loaded(val doc: SurveyDoc) : SurveyLoad

    /** Unreadable or undecodable: Survey mode goes read-only and never overwrites it. */
    data class Malformed(val message: String) : SurveyLoad
}

/**
 * Blocking file access for `files/trips/<id>/survey.json`; call it off the main thread. The survey is
 * user state layered over the runs, so it lives next to the raw log instead of in Room.
 */
class SurveyStore(private val files: TripFiles) {

    /**
     * Missing when the file does not exist; Malformed on any read or decode exception, carrying the first
     * line of its message (kotlinx.serialization appends the JSON input on later lines) or its class name.
     */
    fun load(tripId: Long): SurveyLoad {
        val file = files.surveyFile(tripId)
        if (!file.exists()) return SurveyLoad.Missing
        return try {
            SurveyLoad.Loaded(SurveyDoc.fromJson(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            SurveyLoad.Malformed(describe(e))
        }
    }

    /**
     * Atomic, so a crash mid-save leaves the previous survey. The doc is encoded before anything is
     * written, so a doc that cannot be encoded (a NaN) throws and leaves the file untouched.
     */
    fun save(tripId: Long, doc: SurveyDoc) {
        AtomicFiles.writeText(files.surveyFile(tripId), doc.toJson())
    }

    private fun describe(e: Exception): String =
        e.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: e.javaClass.simpleName
}
```

**Step 6: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.SurveyStoreTest'`

Expected: `BUILD SUCCESSFUL`. There is no per-test output because only failures print. The report
is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 7: Move `TripProcessor` onto `AtomicFiles`**

In `app/src/main/kotlin/com/stastyle/imumapper/process/TripProcessor.kt`:

Replace (line 3):

```kotlin
import com.stastyle.imumapper.data.CalibrationRepository
```

with:

```kotlin
import com.stastyle.imumapper.data.AtomicFiles
import com.stastyle.imumapper.data.CalibrationRepository
```

Replace (line 104):

```kotlin
            withContext(ioDispatcher) { writeAtomically(resultFile, result.toJson()) }
```

with:

```kotlin
            // Temp file and rename, so a half-written result never carries a run name.
            withContext(ioDispatcher) { AtomicFiles.writeText(resultFile, result.toJson()) }
```

Replace (lines 152-164, the whole private function and the blank line after it):

```kotlin
    /** Write to a sibling temp file then rename so a half-written result never carries a run name. */
    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Rename can fail across some file systems; a plain copy is the fallback.
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun defaultLabel(processor: Processor, log: RawLog): String = when (processor) {
```

with:

```kotlin
    private fun defaultLabel(processor: Processor, log: RawLog): String = when (processor) {
```

Keep `import java.io.File`, because `readLog`, `outOfMemoryMessage` and `readResult` still use it.
Then check that nothing else still uses the old function:
`grep -n "writeAtomically" app/src/main/kotlin/com/stastyle/imumapper/process/TripProcessor.kt` prints nothing.

**Step 8: Expose the store from `AppContainer`**

In `app/src/main/kotlin/com/stastyle/imumapper/AppContainer.kt`:

Replace (lines 6-7):

```kotlin
import com.stastyle.imumapper.data.RoomTripRepository
import com.stastyle.imumapper.data.TripExporter
```

with:

```kotlin
import com.stastyle.imumapper.data.RoomTripRepository
import com.stastyle.imumapper.data.SurveyStore
import com.stastyle.imumapper.data.TripExporter
```

Replace (line 56):

```kotlin
    val tripImporter: TripImporter by lazy { TripImporter(appContext, tripRepository, tripFiles) }
```

with:

```kotlin
    val tripImporter: TripImporter by lazy { TripImporter(appContext, tripRepository, tripFiles) }

    val surveyStore: SurveyStore by lazy { SurveyStore(tripFiles) }
```

**Step 9: Run the store test and the processor regression test**

Run:
`./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.SurveyStoreTest' --tests 'com.stastyle.imumapper.process.DefaultTripProcessorTest'`

Expected: `BUILD SUCCESSFUL`. The run compiles the main sources, so `AppContainer` is checked too.
`DefaultTripProcessorTest.processesAndStoresFirstRun` still finds no `.tmp` in `results/`.

**Step 10: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/data/AtomicFiles.kt \
  app/src/main/kotlin/com/stastyle/imumapper/data/SurveyStore.kt \
  app/src/main/kotlin/com/stastyle/imumapper/data/TripFiles.kt \
  app/src/main/kotlin/com/stastyle/imumapper/process/TripProcessor.kt \
  app/src/main/kotlin/com/stastyle/imumapper/AppContainer.kt \
  app/src/test/kotlin/com/stastyle/imumapper/data/SurveyStoreTest.kt
git status --short
git commit -F - <<'EOF'
Keep Survey mode's facts in a survey.json next to each trip's raw log

The survey is user state layered over the runs, so it lives in the trip directory instead of Room: no
entity change and no migration. It is saved through the temp-file-and-rename that run results already
used, now shared as AtomicFiles, so a crash mid-save leaves the previous file. A file that cannot be
read or decoded is reported as Malformed, so Survey mode can go read-only instead of seeding over it.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

`git status --short` before the commit must show only the six staged paths (`M ` / `A `).

---

### Task 13: ZIP export and import carry survey.json

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/data/TripArchive.kt:122-129` (`ExtractedTrip`),
  `:136-140` (layout KDoc), `:149-152` (`write` KDoc), `:170-175` (`write`), `:200-224` (`extractZip`)
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/data/TripImporter.kt:121-125` (`moveIntoTrip`)
- Test: `app/src/test/kotlin/com/stastyle/imumapper/data/TripArchiveTest.kt` (imports, one new test
  before line 125). The existing tests stay as they are and still pin the entry list of a trip with no
  survey.
- Test: `app/src/test/kotlin/com/stastyle/imumapper/data/TripImporterTest.kt` (imports, two
  `assertNull` lines at `:78` and `:93`, two new tests before `zipOf` at line 142)

**Step 1: Write the failing export test**

In `app/src/test/kotlin/com/stastyle/imumapper/data/TripArchiveTest.kt`:

Replace (line 7):

```kotlin
import com.stastyle.imumapper.pipeline.core.TripMode
```

with:

```kotlin
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

Replace (lines 128-129):

```kotlin
    @Test
    fun zipWithoutRawLogStillCarriesManifest() {
```

with:

```kotlin
    @Test
    fun zipCarriesSurveyBetweenResultsAndPhotos() {
        populateTrip()
        val survey = SurveyDoc(
            stations = listOf(Station(id = 1, kind = StationKind.START, name = "Start", tNs = 0L)),
        ).toJson()
        files.surveyFile(trip.id).writeText(survey)
        // Left behind by an interrupted save; only the exact name is the survey.
        File(files.tripDir(trip.id), "survey.json.tmp").writeText("partial")

        ZipFile(buildZip()).use { z ->
            assertEquals(
                listOf(
                    "trip.json", "raw.imul", "results/run-1.json", "results/run-2.json", "survey.json",
                    "photos/kf-0001.jpg", "photos/kf-0002.jpg",
                ),
                z.entries().asSequence().map { it.name }.toList(),
            )
            assertEquals(survey, z.getInputStream(z.getEntry("survey.json")).readBytes().toString(Charsets.UTF_8))
            val manifest = TripArchive.decodeManifest(
                z.getInputStream(z.getEntry("trip.json")).readBytes().toString(Charsets.UTF_8),
            )
            assertEquals(1, manifest.formatVersion, "older builds keep reading the archive and skip survey.json")
        }
    }

    @Test
    fun zipWithoutRawLogStillCarriesManifest() {
```

**Step 2: Run the test and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.TripArchiveTest'`

Expected: `BUILD FAILED` with

```
TripArchiveTest > zipCarriesSurveyBetweenResultsAndPhotos FAILED
    java.lang.AssertionError at TripArchiveTest.kt:<line>
```

The report shows that the actual entry list has no `survey.json` between `results/run-2.json` and
`photos/kf-0001.jpg`. The other five tests pass.

**Step 3: Write survey.json into the archive**

In `app/src/main/kotlin/com/stastyle/imumapper/data/TripArchive.kt`:

Replace (layout KDoc, lines 136-141):

````kotlin
 * trip.json              TripManifest
 * raw.imul               the raw log
 * results/run-<n>.json   PathResult per run
 * photos/<name>          keyframes
 * ```
 */
````

with:

````kotlin
 * trip.json              TripManifest
 * raw.imul               the raw log
 * results/run-<n>.json   PathResult per run
 * survey.json            Survey mode facts (SurveyStore), when the trip has them
 * photos/<name>          keyframes
 * ```
 *
 * [MANIFEST_VERSION] stays 1 with survey.json in the layout: older builds skip entries they do not know.
 */
````

Replace (`write` KDoc, lines 153-154):

```kotlin
     * Writes the archive for [trip] to [out]. Only files that exist are added, so a trip without
     * results or photos still exports. [tripDir] is `files/trips/<id>`.
```

with:

```kotlin
     * Writes the archive for [trip] to [out]. Only files that exist are added, so a trip without
     * results, photos or a survey still exports. [tripDir] is `files/trips/<id>`.
```

Replace (in `write`, lines 173-178):

```kotlin
            val resultsDir = File(tripDir, TripFiles.RESULTS_DIR_NAME)
            for (f in sortedFiles(resultsDir)) {
                if (f.extension == "json") addFile(zip, "${TripFiles.RESULTS_DIR_NAME}/${f.name}", f)
            }

            val photosDir = File(tripDir, TripFiles.PHOTOS_DIR_NAME)
```

with:

```kotlin
            val resultsDir = File(tripDir, TripFiles.RESULTS_DIR_NAME)
            for (f in sortedFiles(resultsDir)) {
                if (f.extension == "json") addFile(zip, "${TripFiles.RESULTS_DIR_NAME}/${f.name}", f)
            }

            // Only the exact name: a survey.json.tmp left by an interrupted save is not the survey.
            val survey = File(tripDir, TripFiles.SURVEY_NAME)
            if (survey.isFile) addFile(zip, TripFiles.SURVEY_NAME, survey)

            val photosDir = File(tripDir, TripFiles.PHOTOS_DIR_NAME)
```

**Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.TripArchiveTest'`

Expected: `BUILD SUCCESSFUL`. All six tests pass, and `zipHoldsManifestLogResultsAndPhotos`
(no survey) keeps its entry list.

**Step 5: Write the failing import tests**

In `app/src/test/kotlin/com/stastyle/imumapper/data/TripImporterTest.kt`:

Replace (line 8):

```kotlin
import com.stastyle.imumapper.pipeline.log.LogReader
```

with:

```kotlin
import com.stastyle.imumapper.pipeline.log.LogReader
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

In `exportedZipRoundTrips`, replace (lines 80-81):

```kotlin
        assertEquals(listOf("p.jpg"), extracted.photos.map { it.name })
        assertTrue(File(staging, "photos/p.jpg").readBytes().contentEquals(byteArrayOf(9)))
```

with:

```kotlin
        assertEquals(listOf("p.jpg"), extracted.photos.map { it.name })
        assertTrue(File(staging, "photos/p.jpg").readBytes().contentEquals(byteArrayOf(9)))
        assertNull(extracted.survey, "a trip without a survey brings none")
```

In `bareLogIsCopiedAsRawLog`, replace (lines 96-97):

```kotlin
        assertNull(extracted.manifest)
        assertTrue(extracted.results.isEmpty())
```

with:

```kotlin
        assertNull(extracted.manifest)
        assertTrue(extracted.results.isEmpty())
        assertNull(extracted.survey)
```

Replace (line 147, the start of the helper):

```kotlin
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
```

with:

```kotlin
    @Test
    fun surveyIsExtractedWithItsBytes() {
        val trip = TripEntity(
            id = 3, name = "Corridor", mode = TripMode.POCKET, carryPosition = CarryPosition.CHEST,
            startedAtEpochMs = 123L,
        )
        writeSampleLog(files.rawLog(3))
        // A Hebrew station name ("cave"), so the bytes include multi-byte UTF-8.
        val survey = SurveyDoc(
            stations = listOf(Station(id = 1, kind = StationKind.USER, name = "\u05DE\u05E2\u05E8\u05D4", tNs = 5L)),
        ).toJson().toByteArray(Charsets.UTF_8)
        files.surveyFile(3).writeBytes(survey)
        val out = ByteArrayOutputStream()
        TripArchive.write(trip, emptyList(), files.tripDir(3), out)

        val staging = File(files.importDir(), "s")
        val extracted = TripArchive.extractZip(out.toByteArray().inputStream(), staging)

        val file = assertNotNull(extracted.survey)
        assertEquals(File(staging, "survey.json"), file)
        assertTrue(file.readBytes().contentEquals(survey))
    }

    @Test
    fun surveyIsTakenOnlyFromTheArchiveRoot() {
        val bytes = zipOf(
            "raw.imul" to "IMUL",
            "other/survey.json" to "{}",
            "survey.json.tmp" to "{}",
        )
        val extracted = TripArchive.extractZip(bytes.inputStream(), File(files.importDir(), "s"))
        assertNull(extracted.survey)
        assertEquals("IMUL", extracted.rawLog?.readText())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
```

**Step 6: Run the tests and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.TripImporterTest'`

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin` with
`Unresolved reference 'survey'.` for each use of `extracted.survey`.

**Step 7: Return the survey from extraction**

In `app/src/main/kotlin/com/stastyle/imumapper/data/TripArchive.kt`:

Replace (lines 122-129):

```kotlin
/** Files unpacked from an archive into a staging directory, before any database row exists. */
data class ExtractedTrip(
    val manifest: TripManifest?,
    val rawLog: File?,
    /** `results/run-<n>.json` files, in archive order. */
    val results: List<File>,
    val photos: List<File>,
)
```

with:

```kotlin
/** Files unpacked from an archive into a staging directory, before any database row exists. */
data class ExtractedTrip(
    val manifest: TripManifest?,
    val rawLog: File?,
    /** `results/run-<n>.json` files, in archive order. */
    val results: List<File>,
    val photos: List<File>,
    /** survey.json when the archive carried one (exports from builds with Survey mode). */
    val survey: File? = null,
)
```

In `extractZip`, replace (lines 209-210):

```kotlin
        val results = ArrayList<File>()
        val photos = ArrayList<File>()
```

with:

```kotlin
        val results = ArrayList<File>()
        val photos = ArrayList<File>()
        var survey: File? = null
```

Replace (line 224):

```kotlin
                    name == TripFiles.RAW_LOG_NAME -> rawLog = copyEntry(zip, File(stagingDir, TripFiles.RAW_LOG_NAME))
```

with:

```kotlin
                    name == TripFiles.RAW_LOG_NAME -> rawLog = copyEntry(zip, File(stagingDir, TripFiles.RAW_LOG_NAME))
                    name == TripFiles.SURVEY_NAME -> survey = copyEntry(zip, File(stagingDir, TripFiles.SURVEY_NAME))
```

Replace (line 235):

```kotlin
        return ExtractedTrip(manifest, rawLog, results, photos)
```

with:

```kotlin
        return ExtractedTrip(manifest, rawLog, results, photos, survey)
```

`extractRawLog` stays as it is, because `survey` defaults to null.

**Step 8: Move the survey into the imported trip**

In `app/src/main/kotlin/com/stastyle/imumapper/data/TripImporter.kt`, replace (lines 124-125):

```kotlin
        for (f in extracted.photos) move(f, File(files.photosDir(tripId), f.name))
    }
```

with:

```kotlin
        for (f in extracted.photos) move(f, File(files.photosDir(tripId), f.name))
        extracted.survey?.let { move(it, files.surveyFile(tripId)) }
    }
```

`TripImporter` needs a `Context` and a `Uri`, so it has no unit test. This line is checked by compiling,
and by the manual check below once Task 29 is in.

**Step 9: Run the data tests and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.*'`

Expected: `BUILD SUCCESSFUL`. This covers `TripArchiveTest`, `TripImporterTest`, `SurveyStoreTest`
and `HeadingOffsetResetTest`, and the run compiles `TripImporter`.

**Step 10: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/data/TripArchive.kt \
  app/src/main/kotlin/com/stastyle/imumapper/data/TripImporter.kt \
  app/src/test/kotlin/com/stastyle/imumapper/data/TripArchiveTest.kt \
  app/src/test/kotlin/com/stastyle/imumapper/data/TripImporterTest.kt
git status --short
git commit -F - <<'EOF'
Carry a trip's survey in its ZIP export and restore it on import

survey.json sits at the archive root after the results and before the photos. The manifest version
stays 1 because older builds skip entries they do not know. Only the exact name is exported, so a
survey.json.tmp left by an interrupted save stays behind.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

**Manual check (after Task 29, on a phone):**
1. Open Survey mode on a trip and rename a station.
2. Export the trip from the trip list, then import the ZIP.
3. Open Survey mode on the imported copy. It shows the same stations and the renamed name, and it
   does not seed new ones.

---

### Task 14: CSV file naming, writing and share intent

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/data/ExportNames.kt`
- Create: `app/src/main/kotlin/com/stastyle/imumapper/data/SurveyCsvFile.kt`
- Create: `app/src/main/kotlin/com/stastyle/imumapper/data/SurveyShare.kt` (Android, compile-checked)
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/data/TripExporter.kt:89-101` (`exportFileName`)
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/data/TripFiles.kt`. Only KDoc changes, which
  say that `cache/export/` now holds CSVs too: the layout line (line 14 after Task 12) and the
  `pruneExports` KDoc (lines 53-56 after Task 12). Find both by their text.
- Test: `app/src/test/kotlin/com/stastyle/imumapper/data/SurveyCsvFileTest.kt` (create)

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/data/SurveyCsvFileTest.kt`:

```kotlin
package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The survey CSV as other apps receive it: its name and its bytes. */
class SurveyCsvFileTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-csv").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun fileNameCarriesTripRunAndRawFlag() {
        assertEquals("Cave_loop-7-run3-survey.csv", SurveyCsvFile.fileName("Cave loop", 7L, 3, raw = false))
        assertEquals("Cave_loop-7-run3-raw-survey.csv", SurveyCsvFile.fileName("Cave loop", 7L, 3, raw = true))
    }

    @Test
    fun hebrewLettersAreKept() {
        // "Big cave" in Hebrew: the letters stay, only the space becomes '_'.
        val name = "\u05DE\u05E2\u05E8\u05D4 \u05D2\u05D3\u05D5\u05DC\u05D4"
        assertEquals(
            "\u05DE\u05E2\u05E8\u05D4_\u05D2\u05D3\u05D5\u05DC\u05D4-12-run1-survey.csv",
            SurveyCsvFile.fileName(name, 12L, 1, raw = false),
        )
    }

    @Test
    fun nameWithoutLettersOrDigitsBecomesTrip() {
        assertEquals("trip-5-run2-survey.csv", SurveyCsvFile.fileName("?! / * ..", 5L, 2, raw = false))
        assertEquals("trip-5-run2-survey.csv", SurveyCsvFile.fileName("", 5L, 2, raw = false))
    }

    @Test
    fun safeStemReplacesSymbolsAndTrimsTheEnds() {
        assertEquals("Cave_loop__2", ExportNames.safeStem("  Cave loop #2 "))
        // Path separators never reach the file system as directories.
        assertEquals("a_b_c", ExportNames.safeStem("a/b\\c"))
    }

    @Test
    fun safeStemKeepsAtMostFortyCharacters() {
        assertEquals("x".repeat(40), ExportNames.safeStem("x".repeat(55)))
        // The ends are trimmed first, so leading symbols do not use up the forty.
        assertEquals("x".repeat(40), ExportNames.safeStem("--" + "x".repeat(45)))
    }

    @Test
    fun zipExportNameKeepsItsShape() {
        // The stamp uses the default time zone, so only its shape is checked.
        val name = TripExporter.exportFileName("Cave loop", 1_700_000_000_000L, 7L)
        assertTrue(Regex("""Cave_loop-\d{8}-\d{4}-7\.zip""").matches(name), name)
    }

    @Test
    fun writtenFileStartsWithTheByteOrderMarkAndDecodesBack() {
        val dir = File(tmp, "export")
        val row = "Start,\u05DE\u05E2\u05E8\u05D4,0.0,5.0,5.00,5.00,0.00,0.0,M,0.0,0.0,5.00,no,0.00,5.00,0.00"
        val text = SurveyCsv.BOM + SurveyCsv.HEADER + SurveyCsv.EOL + row + SurveyCsv.EOL

        val file = SurveyCsvFile.write(dir, "Cave_loop-7-run3-survey.csv", text)

        assertEquals(File(dir, "Cave_loop-7-run3-survey.csv"), file)
        val bytes = file.readBytes()
        assertTrue(bytes.copyOf(3).contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        assertEquals(text, bytes.toString(Charsets.UTF_8))
        assertEquals(listOf(file.name), dir.list()!!.toList(), "no temp file is left in the export directory")
    }

    @Test
    fun writingTheSameNameAgainReplacesTheFile() {
        val dir = File(tmp, "export")
        SurveyCsvFile.write(dir, "a.csv", "first")
        val file = SurveyCsvFile.write(dir, "a.csv", "second")
        assertEquals("second", file.readText())
        assertEquals(listOf("a.csv"), dir.list()!!.toList())
    }
}
```

How the expected values follow from the rule "letters and digits kept, everything else `_`, trim `_`,
cut to 40, `trip` when empty":

- `"  Cave loop #2 "` maps to `"__Cave_loop__2_"`, which trims to `"Cave_loop__2"`.
- `"?! / * .."` is all `_`, trims to empty, and becomes `"trip"`.
- `"--" + 45 x` trims to 45 x, which is cut to 40.
- `zipExportNameKeepsItsShape` guards the refactor in Step 6, so it passes before and after it.

**Step 2: Run the test and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.SurveyCsvFileTest'`

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin` with
`Unresolved reference 'SurveyCsvFile'.` and `Unresolved reference 'ExportNames'.`

**Step 3: Create `ExportNames`**

Create `app/src/main/kotlin/com/stastyle/imumapper/data/ExportNames.kt`:

```kotlin
package com.stastyle.imumapper.data

/** File names other apps see: readable, and safe on every file system the share sheet may hand them to. */
object ExportNames {
    /**
     * Letters and digits of any script are kept (a Hebrew trip name stays readable) and everything else
     * becomes '_'; then '_' is trimmed from both ends, the result is cut to 40 chars, and "trip" stands in
     * when nothing is left. The trip ZIP and the survey CSV share it, so one trip's exports look alike.
     */
    fun safeStem(name: String): String =
        name.map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trim('_')
            .take(MAX_LENGTH)
            .ifEmpty { "trip" }

    private const val MAX_LENGTH = 40
}
```

**Step 4: Create `SurveyCsvFile`**

Create `app/src/main/kotlin/com/stastyle/imumapper/data/SurveyCsvFile.kt`:

```kotlin
package com.stastyle.imumapper.data

import java.io.File

/** The survey CSV as a file in cache/export, named so a receiver can tell trips, runs and raw paths apart. */
object SurveyCsvFile {
    const val MIME_CSV: String = "text/csv"

    /**
     * "<safeStem>-<tripId>-run<runId>[-raw]-survey.csv". The same trip, run and path always give the
     * same name, so exporting again replaces the file instead of piling up copies in the cache.
     */
    fun fileName(tripName: String, tripId: Long, runId: Int, raw: Boolean): String {
        val rawPart = if (raw) "-raw" else ""
        return "${ExportNames.safeStem(tripName)}-$tripId-run$runId$rawPart-survey.csv"
    }

    /**
     * Writes [text] into [dir] atomically and returns the file. The byte-order mark is part of [text]
     * (SurveyCsv.BOM), and UTF-8 turns it into EF BB BF, which Excel needs to show Hebrew names.
     */
    fun write(dir: File, fileName: String, text: String): File =
        File(dir, fileName).also { AtomicFiles.writeText(it, text) }
}
```

**Step 5: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.SurveyCsvFileTest'`

Expected: `BUILD SUCCESSFUL`.

**Step 6: Switch the ZIP name to `ExportNames` and update the export-directory KDoc**

In `app/src/main/kotlin/com/stastyle/imumapper/data/TripExporter.kt`, replace (lines 89-101):

```kotlin
        /**
         * `<name>-<yyyyMMdd-HHmm>-<id>.zip` with the name reduced to file-system-safe ASCII, so the
         * receiver sees something meaningful and two trips never collide.
         */
        fun exportFileName(tripName: String, startedAtEpochMs: Long, tripId: Long): String {
            val safe = tripName.map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .trim('_')
                .take(40)
                .ifEmpty { "trip" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(startedAtEpochMs))
            return "$safe-$stamp-$tripId.${TripArchive.ZIP_EXTENSION}"
        }
```

with:

```kotlin
        /**
         * `<name>-<yyyyMMdd-HHmm>-<id>.zip` with the name made file-system safe by [ExportNames.safeStem],
         * so the receiver sees something meaningful and two trips never collide.
         */
        fun exportFileName(tripName: String, startedAtEpochMs: Long, tripId: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(startedAtEpochMs))
            return "${ExportNames.safeStem(tripName)}-$stamp-$tripId.${TripArchive.ZIP_EXTENSION}"
        }
```

The old KDoc said "ASCII". `isLetterOrDigit` has always kept Hebrew letters, so that word was wrong.
Leave the imports alone, because `SimpleDateFormat`, `Date` and `Locale` are still used.

In `app/src/main/kotlin/com/stastyle/imumapper/data/TripFiles.kt`, replace:

```kotlin
 * cache/export/                             ZIPs for the share sheet
```

with:

```kotlin
 * cache/export/                             ZIPs and survey CSVs for the share sheet
```

and replace:

```kotlin
     * Removes export ZIPs older than [maxAgeMs]. Exports live in the cache so the system may also
     * reclaim them; this just keeps the share sheet from accumulating one file per tap.
```

with:

```kotlin
     * Removes exported files (ZIPs, survey CSVs) older than [maxAgeMs]. Exports live in the cache so the
     * system may also reclaim them; this just keeps the share sheet from accumulating one file per tap.
```

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.SurveyCsvFileTest'`

Expected: `BUILD SUCCESSFUL`. `zipExportNameKeepsItsShape` still passes.

**Step 7: Create the share intent**

Create `app/src/main/kotlin/com/stastyle/imumapper/data/SurveyShare.kt`:

```kotlin
package com.stastyle.imumapper.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a survey CSV to other apps the way [TripExporter] hands over a trip ZIP. It is kept apart from
 * [SurveyCsvFile] so that everything unit-tested stays free of Android.
 */
object SurveyShare {
    /**
     * ACTION_SEND chooser for a CSV in cache/export, through the `<packageName>.fileprovider` authority
     * ([TripExporter.authority]); the `cache` path in res/xml/file_paths.xml already covers cache/export/.
     */
    fun intent(context: Context, file: File, subject: String, text: String): Intent {
        val uri = FileProvider.getUriForFile(context, TripExporter.authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = SurveyCsvFile.MIME_CSV
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            // Some receivers only honour the grant when the URI is also in the clip data.
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, CHOOSER_TITLE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private const val CHOOSER_TITLE = "Export survey CSV"
}
```

**Step 8: Compile the app and re-run the data tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.*'`

Expected: `BUILD SUCCESSFUL`. `assembleDebug` compiles `SurveyShare`, and every test in the data package
passes.

**Step 9: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/data/ExportNames.kt \
  app/src/main/kotlin/com/stastyle/imumapper/data/SurveyCsvFile.kt \
  app/src/main/kotlin/com/stastyle/imumapper/data/SurveyShare.kt \
  app/src/main/kotlin/com/stastyle/imumapper/data/TripExporter.kt \
  app/src/main/kotlin/com/stastyle/imumapper/data/TripFiles.kt \
  app/src/test/kotlin/com/stastyle/imumapper/data/SurveyCsvFileTest.kt
git status --short
git commit -F - <<'EOF'
Write the survey CSV to the export cache and hand it to the share sheet

The CSV follows the trip ZIP's route: cache/export and the same FileProvider path. Its name reuses
the ZIP export's sanitising, now ExportNames.safeStem, so a Hebrew trip name stays readable. The name
also carries the trip id, the run and the raw flag, so exports of different runs do not overwrite
each other. The UTF-8 byte-order mark is part of the text, and Excel needs it to show Hebrew station
names.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

**Manual check (after Tasks 25 and 29 wire `exportSurveyCsv` and the screen, on a phone):**
1. Open Survey mode and choose ⋮ → Export CSV. The chooser is titled "Export survey CSV".
2. Share to an email or Drive app. The attachment is named `<trip>-<id>-run<n>-survey.csv`, or
   `...-raw-survey.csv` when the raw path is shown. The subject is "IMU Mapper survey: <trip name>",
   and the body is the share text.
3. Open the file in Excel or Google Sheets. It has one header row and one row per leg, and Hebrew
   station names read correctly.
4. Repeat on the debug build (package `com.stastyle.imumapper.debug`). Sharing works, because the
   authority is built from `context.packageName`.
