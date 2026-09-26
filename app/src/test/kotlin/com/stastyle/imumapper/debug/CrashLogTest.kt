package com.stastyle.imumapper.debug

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashLogTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-crash").toFile()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun reportCarriesExceptionEnvironmentThreadAndTrace() {
        val e = OutOfMemoryError("Java heap space")
        val report = CrashLog.report("main", e, "app: 1.2.3 (10203)\nheap: 250 MB used of 256 MB max\n", nowMs = 0L)
        val lines = report.lines()

        assertEquals("IMU Mapper crash report", lines[0])
        assertTrue(lines[1].startsWith("time: 1970-01-01"), lines[1])
        assertEquals("exception: java.lang.OutOfMemoryError: Java heap space", lines[2])
        assertEquals("app: 1.2.3 (10203)", lines[3])
        assertEquals("heap: 250 MB used of 256 MB max", lines[4])
        assertEquals("thread: main", lines[5])
        assertEquals("", lines[6])
        assertEquals("java.lang.OutOfMemoryError: Java heap space", lines[7])
        assertTrue(lines[8].trimStart().startsWith("at "), lines[8])
        assertTrue(report.contains("CrashLogTest"), "the trace names this test")
    }

    @Test
    fun fileRoundTripAndClear() {
        val log = CrashLog(File(tmp, "crash"))
        assertNull(log.read())
        log.write("boom")
        assertEquals("boom", log.read())
        assertEquals("last-crash.txt", log.file.name)
        log.clear()
        assertNull(log.read())
        // Clearing twice is harmless.
        log.clear()
    }

    @Test
    fun installedHandlerWritesTheReportThenDelegates() {
        val log = CrashLog(File(tmp, "crash"))
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var delegated: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated = e }
        try {
            log.install { "app: test" }
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            val failure = IllegalStateException("something broke")
            handler.uncaughtException(Thread.currentThread(), failure)

            val report = log.read()!!
            assertTrue(report.contains("exception: java.lang.IllegalStateException: something broke"), report)
            assertTrue(report.contains("app: test"), report)
            assertEquals(failure, delegated)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
