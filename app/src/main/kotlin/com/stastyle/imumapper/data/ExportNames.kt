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
