package com.stastyle.imumapper.pipeline.survey

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurveyDocTest {

    private fun sample() = SurveyDoc(
        stations = listOf(
            Station(1, StationKind.START, "Start", 1_000_000_000L),
            Station(2, StationKind.MARK, "מערה, north", 6_000_000_000L),
            Station(3, StationKind.CORNER, "C1", 11_000_000_000L),
            Station(4, StationKind.USER, "S1", 13_500_000_000L),
            Station(5, StationKind.END, "End", 16_000_000_000L),
        ),
        references = listOf(
            CompassReference(1, 1_000_000_000L, 11_000_000_000L, 44.5, backBearing = false),
            CompassReference(2, 11_000_000_000L, 16_000_000_000L, 272.0, true, ReferenceLine.FITTED),
        ),
        manualRotationDeg = -3.5,
        manualRotationRunId = 7,
        detail = Detail.FINE,
    )

    @Test
    fun roundTripKeepsEveryField() {
        val doc = sample()
        assertEquals(doc, SurveyDoc.fromJson(doc.toJson()))
    }

    @Test
    fun defaultsAreWrittenOut() {
        val json = SurveyDoc().toJson()
        assertTrue("\"formatVersion\": 1" in json, json)
        assertTrue("\"detail\": \"NORMAL\"" in json, json)
        assertEquals(1, SurveyDoc.FORMAT_VERSION)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = """
            {"formatVersion": 2, "planImage": {"scale": 100},
             "stations": [{"id": 3, "kind": "MARK", "name": "Fork", "tNs": 5, "colour": "red"}]}
        """.trimIndent()
        val doc = SurveyDoc.fromJson(text)
        assertEquals(2, doc.formatVersion)
        assertEquals(listOf(Station(3, StationKind.MARK, "Fork", 5L)), doc.stations)
    }

    @Test
    fun emptyObjectDecodesToDefaults() {
        val doc = SurveyDoc.fromJson("{}")
        assertEquals(SurveyDoc(), doc)
        assertEquals(SurveyDoc.FORMAT_VERSION, doc.formatVersion)
        assertEquals(Detail.NORMAL, doc.detail)
        assertEquals(null, doc.manualRotationRunId)
    }

    @Test
    fun unknownEnumValueFallsBackToTheFieldDefault() {
        // A file written by a newer build with a Detail, kind or line this build does not know.
        val text = """
            {"detail": "ULTRA",
             "stations": [{"id": 1, "kind": "WALL", "name": "w", "tNs": 7}],
             "references": [{"id": 1, "fromNs": 1, "toNs": 2, "bearingDeg": 10.0, "line": "CURVE"}]}
        """.trimIndent()
        val doc = SurveyDoc.fromJson(text)
        assertEquals(Detail.NORMAL, doc.detail)
        assertEquals(StationKind.USER, doc.stations.single().kind)
        assertEquals(ReferenceLine.CHORD, doc.references.single().line)
        assertEquals(10.0, doc.references.single().bearingDeg)
    }

    @Test
    fun aNanBearingCannotBeWritten() {
        val doc = SurveyDoc(references = listOf(CompassReference(id = 1, bearingDeg = Double.NaN)))
        assertFailsWith<SerializationException> { doc.toJson() }
        val infinite = SurveyDoc(manualRotationDeg = Double.POSITIVE_INFINITY)
        assertFailsWith<SerializationException> { infinite.toJson() }
    }

    @Test
    fun aNormalDocHoldsNoNan() {
        val json = sample().toJson()
        assertFalse("NaN" in json, json)
        assertFalse("Infinity" in json, json)
    }
}
