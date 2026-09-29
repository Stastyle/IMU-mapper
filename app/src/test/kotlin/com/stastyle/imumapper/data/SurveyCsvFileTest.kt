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
        val name = "מערה גדולה"
        assertEquals(
            "מערה_גדולה-12-run1-survey.csv",
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
        val row = "Start,מערה,0.0,5.0,5.00,5.00,0.00,0.0,M,0.0,0.0,5.00,no,0.00,5.00,0.00"
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
