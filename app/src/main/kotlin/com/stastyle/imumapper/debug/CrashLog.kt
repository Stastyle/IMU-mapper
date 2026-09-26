package com.stastyle.imumapper.debug

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.stastyle.imumapper.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last uncaught exception as a text file so it can be read from the Debug screen after
 * the app has been restarted: there is no crash reporting, and the phone is usually not on a
 * computer with adb when a trip is being looked at. The report carries the heap figures at the
 * moment of the crash, which is what tells an out-of-memory failure apart from a plain bug.
 *
 * The handler writes the file and then hands the exception to the handler that was installed
 * before it, so the system still shows its own crash dialog and restarts the app as usual.
 */
class CrashLog(private val dir: File) {

    val file: File get() = File(dir, FILE_NAME)

    /** The stored report, or null when there is none. */
    fun read(): String? = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() {
        file.delete()
    }

    fun write(report: String) {
        dir.mkdirs()
        file.writeText(report)
    }

    /** Installs the handler; [environment] is evaluated at crash time so the heap figures are current. */
    fun install(environment: () -> String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                write(report(thread.name, e, environment()))
            } catch (ignored: Throwable) {
                // Out of memory or a full disk: nothing sensible left to do but crash as before.
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        const val FILE_NAME = "last-crash.txt"

        fun from(context: Context): CrashLog = CrashLog(File(context.applicationContext.filesDir, "crash"))

        /** Formats one report; pure so it is unit tested. */
        fun report(threadName: String, e: Throwable, environment: String, nowMs: Long = System.currentTimeMillis()): String {
            val trace = StringWriter().also { w -> PrintWriter(w).use { e.printStackTrace(it) } }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(nowMs))
            return buildString {
                append("IMU Mapper crash report\n")
                append("time: ").append(stamp).append('\n')
                append("exception: ").append(e.toString()).append('\n')
                append(environment.trimEnd()).append('\n')
                append("thread: ").append(threadName).append('\n')
                append('\n')
                append(trace.toString().trimEnd()).append('\n')
            }
        }

        /** App version, device and the heap situation right now. */
        fun environment(context: Context): String {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
            val maxMb = rt.maxMemory() / MB
            val am = context.applicationContext.getSystemService(ActivityManager::class.java)
            val classes = if (am == null) "" else " (memoryClass ${am.memoryClass} MB, largeMemoryClass ${am.largeMemoryClass} MB)"
            return "app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                "heap: $usedMb MB used of $maxMb MB max$classes"
        }

        private const val MB = 1_000_000L
    }
}
