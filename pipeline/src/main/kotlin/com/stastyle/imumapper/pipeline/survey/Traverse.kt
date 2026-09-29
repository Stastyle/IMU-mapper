package com.stastyle.imumapper.pipeline.survey

/** Consecutive stations of the traverse and the chord between them. */
data class TraverseLeg(val from: Station, val to: Station, val measure: LegMeasure)

/** The Legs table's totals row. */
data class LegTotals(val lengthM: Double, val horizontalM: Double, val heightChangeM: Double, val pathM: Double)

/** A chain selection: each hop, the straight line from the first to the last, and the sums. */
data class ChainMeasure(
    val hops: List<LegMeasure>,
    val straight: LegMeasure,
    val hopLengthSumM: Double,
    val hopPathSumM: Double,
)

/** The traverse (stations in time order) and chains of tapped stations, measured as chords. */
object Traverse {

    /** Legs between consecutive stations in SurveyStations.ordered order; empty for fewer than two. */
    fun legs(stations: List<Station>, timeline: PathTimeline): List<TraverseLeg> =
        SurveyStations.ordered(stations).zipWithNext { from, to ->
            TraverseLeg(from, to, Measure.leg(timeline, from.tNs, to.tNs))
        }

    /** Sums of the legs (heightChange sums to the net climb). */
    fun totals(legs: List<TraverseLeg>): LegTotals = LegTotals(
        lengthM = legs.sumOf { it.measure.lengthM },
        horizontalM = legs.sumOf { it.measure.horizontalM },
        heightChangeM = legs.sumOf { it.measure.heightChangeM },
        pathM = legs.sumOf { it.measure.pathM },
    )

    /** [timesNs] in tap order, at least two. */
    fun chain(timesNs: List<Long>, timeline: PathTimeline): ChainMeasure {
        require(timesNs.size >= 2) { "a chain needs at least two stations" }
        val hops = timesNs.zipWithNext { from, to -> Measure.leg(timeline, from, to) }
        return ChainMeasure(
            hops = hops,
            straight = Measure.leg(timeline, timesNs.first(), timesNs.last()),
            hopLengthSumM = hops.sumOf { it.lengthM },
            hopPathSumM = hops.sumOf { it.pathM },
        )
    }
}
