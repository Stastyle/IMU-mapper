package com.stastyle.imumapper.pipeline.survey

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a station came from: it sets the default name, and only CORNER stations regenerate on a Detail change. */
@Serializable
enum class StationKind { START, END, MARK, CORNER, USER }

/** A named moment of the walk. It is stored by time and placed on whichever path is shown. */
@Serializable
data class Station(
    val id: Int = 0,
    val kind: StationKind = StationKind.USER,
    val name: String = "",
    /** elapsedRealtime nanoseconds, the clock of every PathPoint.tNs. */
    val tNs: Long = 0L,
)

/** Which line of the stretch a compass bearing describes. */
@Serializable
enum class ReferenceLine {
    /** The straight line from the first moment to the second (default). */
    CHORD,

    /** The direction of the passage: the total-least-squares line through the stretch. */
    FITTED,
}

/** A hand-compass bearing (magnetic) between two moments of the walk, stored as read. */
@Serializable
data class CompassReference(
    val id: Int = 0,
    val fromNs: Long = 0L,
    val toNs: Long = 0L,
    /** Degrees in [0, 360) as read on the compass, before any back-bearing flip. */
    val bearingDeg: Double = 0.0,
    /** Taken from B back to A: 180 degrees are added before use. */
    val backBearing: Boolean = false,
    val line: ReferenceLine = ReferenceLine.CHORD,
)

/** RDP tolerance for automatic corners. */
@Serializable
enum class Detail(val toleranceM: Double) { COARSE(1.0), NORMAL(0.5), FINE(0.25) }

/**
 * The per-trip survey file (`files/trips/<id>/survey.json`). Facts only: no derived value and no NaN,
 * every field defaulted so older and newer files keep decoding.
 */
@Serializable
data class SurveyDoc(
    val formatVersion: Int = FORMAT_VERSION,
    val stations: List<Station> = emptyList(),
    val references: List<CompassReference> = emptyList(),
    /**
     * Degrees, positive turns the map clockwise; used only while the doc has no compass reference (a
     * reference too short to measure still disables it).
     */
    val manualRotationDeg: Double = 0.0,
    /** The run the manual rotation was set on; null when it was never set. */
    val manualRotationRunId: Int? = null,
    val detail: Detail = Detail.NORMAL,
) {
    /** Throws a SerializationException on a NaN or infinite value, so a bad number never reaches the file. */
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION: Int = 1

        /** coerceInputValues turns an enum value from a newer build into the field's default instead of failing. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
            prettyPrint = true
        }

        fun fromJson(text: String): SurveyDoc = json.decodeFromString(serializer(), text)
    }
}
