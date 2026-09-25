package com.stastyle.imumapper.process

import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor

/**
 * Runs the pipeline over a recorded trip and stores the result as a new run.
 * Implemented by the app-data work item.
 */
interface TripProcessor {
    /**
     * Processes [tripId] with [config] (or the saved calibration when null), writes
     * `results/run-<n>.json`, inserts the [PathResultEntity] and updates the trip row.
     */
    suspend fun process(tripId: Long, config: PipelineConfig? = null, label: String = ""): PathResultEntity
}

class DefaultTripProcessor(
    private val trips: TripRepository,
    private val calibration: CalibrationRepository,
    private val files: TripFiles,
    private val processor: Processor,
) : TripProcessor {
    override suspend fun process(tripId: Long, config: PipelineConfig?, label: String): PathResultEntity {
        TODO("Trip processing not implemented yet")
    }
}
