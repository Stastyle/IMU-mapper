package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import java.io.File
import java.io.IOException

/** What reading a trip's survey.json found. */
sealed interface SurveyLoad {
    /** No file yet: Survey mode has never been opened on this trip, so it seeds. */
    data object Missing : SurveyLoad

    data class Loaded(val doc: SurveyDoc) : SurveyLoad

    /**
     * Saved by a newer build (formatVersion above SurveyDoc.FORMAT_VERSION). Unknown keys are ignored on
     * decode, so saving [doc] back would drop the newer fields: Survey mode shows it read-only and never
     * overwrites it. The file is fine, so there is no Start over; updating the app edits it.
     */
    data class Newer(val doc: SurveyDoc) : SurveyLoad

    /**
     * Unreadable or undecodable: Survey mode goes read-only and never overwrites it. Start over
     * (SurveyStore.setAside) keeps it under another name and seeds a new survey.
     */
    data class Malformed(val message: String) : SurveyLoad
}

/**
 * Blocking file access for `files/trips/<id>/survey.json`; call it off the main thread. The survey is
 * user state layered over the runs, so it lives next to the raw log instead of in Room.
 */
class SurveyStore(private val files: TripFiles) {

    /**
     * Missing when the file does not exist; Newer when its formatVersion is above this build's; Malformed
     * on any read or decode exception, carrying the first line of its message (kotlinx.serialization
     * appends the JSON input on later lines) or its class name.
     */
    fun load(tripId: Long): SurveyLoad {
        val file = files.surveyFile(tripId)
        if (!file.exists()) return SurveyLoad.Missing
        val doc = try {
            SurveyDoc.fromJson(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            return SurveyLoad.Malformed(describe(e))
        }
        return if (doc.formatVersion > SurveyDoc.FORMAT_VERSION) SurveyLoad.Newer(doc) else SurveyLoad.Loaded(doc)
    }

    /**
     * Atomic, so a crash mid-save leaves the previous survey. The doc is encoded before anything is
     * written, so a doc that cannot be encoded (a NaN) throws and leaves the file untouched.
     */
    fun save(tripId: Long, doc: SurveyDoc) {
        AtomicFiles.writeText(files.surveyFile(tripId), doc.toJson())
    }

    /**
     * Start over on an unreadable survey: renames survey.json to "survey.json.bad-<n>", the lowest n not
     * taken, so the file is kept and never overwritten, and the next [load] is Missing. Returns the kept
     * file, or null when there was no survey.json. Throws IOException when the rename fails.
     */
    fun setAside(tripId: Long): File? {
        val file = files.surveyFile(tripId)
        if (!file.exists()) return null
        val kept = generateSequence(1) { it + 1 }
            .map { File(file.parentFile, "${file.name}$SET_ASIDE_INFIX$it") }
            .first { !it.exists() }
        if (!file.renameTo(kept)) throw IOException("Could not rename ${file.name} to ${kept.name}")
        return kept
    }

    private fun describe(e: Exception): String =
        e.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: e.javaClass.simpleName

    private companion object {
        const val SET_ASIDE_INFIX = ".bad-"
    }
}
