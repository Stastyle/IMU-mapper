package com.stastyle.imumapper.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How an export's ZIP lands in cache/export: whole or not at all, even when the trip list's Export and the
 * viewer's Share write the same trip's file at once.
 */
class TripExporterTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-export").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun target() = File(files.exportDir(), "Cave-20260930-1200-7.zip")

    private fun parts(): List<File> = files.exportDir().listFiles().orEmpty().filter { it.name.endsWith(".part") }

    @Test
    fun replacesTheOldZipWholeAndLeavesNoTempFile() {
        val target = target().apply { writeText("old export") }

        TripExporter.replaceFile(target) { it.write("new export".toByteArray()) }

        assertEquals("new export", target.readText())
        assertEquals(listOf(target.name), files.exportDir().list().orEmpty().toList())
    }

    @Test
    fun aFailedWriteKeepsTheOldZipAndRemovesItsTempFile() {
        val target = target().apply { writeText("old export") }

        assertFailsWith<IOException> {
            TripExporter.replaceFile(target) { out ->
                out.write("half of".toByteArray())
                throw IOException("No space left on device")
            }
        }

        assertEquals("old export", target.readText())
        assertTrue(parts().isEmpty())
    }

    @Test
    fun anExportThatStartsWhileAnotherWritesTheSameZipUsesItsOwnTempFile() {
        // The outer write is the trip list's Export, the inner one the viewer's Share of the same trip,
        // started and finished while the first is halfway through. They used to share "<zip>.part", so
        // the second truncated the first's bytes and the first then renamed a mix of both.
        val target = target()
        val first = ByteArray(64 * 1024) { 'a'.code.toByte() }
        val second = ByteArray(48 * 1024) { 'b'.code.toByte() }

        TripExporter.replaceFile(target) { outer ->
            outer.write(first, 0, first.size / 2)
            TripExporter.replaceFile(target) { inner ->
                assertEquals(2, parts().size)
                inner.write(second)
            }
            assertContentEquals(second, target.readBytes())
            outer.write(first, first.size / 2, first.size - first.size / 2)
        }

        // Whichever rename lands last wins, and what it publishes is one whole archive.
        assertContentEquals(first, target.readBytes())
        assertTrue(parts().isEmpty())
    }

    @Test
    fun theTempFileSitsBesideTheZipWhereStaleOnesArePruned() {
        val target = target()
        var part: File? = null
        TripExporter.replaceFile(target) { out ->
            part = parts().single()
            out.write("zip".toByteArray())
        }
        val written = assertNotNull(part)
        assertEquals(files.exportDir().canonicalFile, written.parentFile?.canonicalFile)
        assertTrue(written.name.startsWith("Cave-20260930-1200-7-"), written.name)
        assertFalse(written.exists())

        // A crash mid-export leaves such a file behind; it goes once it is older than the exports kept.
        val stale = File(files.exportDir(), written.name).apply {
            writeText("half a zip")
            setLastModified(1_000L)
        }
        files.pruneExports(maxAgeMs = 24L * 60L * 60L * 1000L)
        assertFalse(stale.exists())
        assertTrue(target.exists())
    }
}
