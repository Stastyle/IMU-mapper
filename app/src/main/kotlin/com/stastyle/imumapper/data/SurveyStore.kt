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
