package com.stastyle.imumapper.pipeline.survey

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    fun namesWrittenByEarlierBuildsStillDecode() {
        // survey.json stores these enums by name, and coerceInputValues turns an unknown name into the
        // field default, so a renamed constant would silently turn stored CORNERs into USER stations.
        // The names are compared as strings, so an IDE rename cannot update this test with the enum.
        val text = """
            {"detail": "FINE",
             "stations": [{"id": 1, "kind": "START"}, {"id": 2, "kind": "END"}, {"id": 3, "kind": "MARK"},
                          {"id": 4, "kind": "CORNER"}, {"id": 5, "kind": "USER"}],
             "references": [{"id": 1, "line": "CHORD"}, {"id": 2, "line": "FITTED"}]}
        """.trimIndent()
        val doc = SurveyDoc.fromJson(text)
        assertEquals(listOf("START", "END", "MARK", "CORNER", "USER"), doc.stations.map { it.kind.name })
        assertEquals(listOf("CHORD", "FITTED"), doc.references.map { it.line.name })
        for (name in listOf("COARSE", "NORMAL", "FINE")) {
            assertEquals(name, SurveyDoc.fromJson("""{"detail": "$name"}""").detail.name)
        }
    }

    @Test
    fun keysWrittenByEarlierBuildsStillDecode() {
        // ignoreUnknownKeys drops the value of a renamed field without an error, so every stored key is
        // pinned here as a string, each with a non-default value. A new field changes the written key
        // set and fails the second half until its key is added here too.
        val text = """
            {"formatVersion": 1,
             "stations": [{"id": 4, "kind": "CORNER", "name": "C1", "tNs": 11}],
             "references": [{"id": 2, "fromNs": 3, "toNs": 9, "bearingDeg": 272.5, "backBearing": true,
                             "line": "FITTED"}],
             "manualRotationDeg": -3.5, "manualRotationRunId": 7, "detail": "FINE"}
        """.trimIndent()
        val expected = SurveyDoc(
            formatVersion = 1,
            stations = listOf(Station(id = 4, kind = StationKind.CORNER, name = "C1", tNs = 11L)),
            references = listOf(
                CompassReference(
                    id = 2, fromNs = 3L, toNs = 9L, bearingDeg = 272.5, backBearing = true,
                    line = ReferenceLine.FITTED,
                ),
            ),
            manualRotationDeg = -3.5,
            manualRotationRunId = 7,
            detail = Detail.FINE,
        )
        assertEquals(expected, SurveyDoc.fromJson(text))

        fun keys(json: String): List<Set<String>> {
            val root = Json.parseToJsonElement(json).jsonObject
            val station = root.getValue("stations").jsonArray.single().jsonObject
            val reference = root.getValue("references").jsonArray.single().jsonObject
            return listOf(root.keys, station.keys, reference.keys)
        }
        assertEquals(keys(text), keys(expected.toJson()))
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
