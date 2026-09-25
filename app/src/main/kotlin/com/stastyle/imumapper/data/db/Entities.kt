package com.stastyle.imumapper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode

enum class TripStatus {
    /** A recorder is writing to this trip. */
    RECORDING,
    /** The raw log is complete; no result yet. */
    RECORDED,
    /** At least one processing run exists. */
    PROCESSED,
    /** The last processing run failed; see [TripEntity.lastError]. */
    FAILED,
}

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mode: TripMode,
    val carryPosition: CarryPosition,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val status: TripStatus = TripStatus.RECORDING,
    val rawLogSizeBytes: Long = 0,
    /** runId of the most recent processing run, null before the first. */
    val latestRunId: Int? = null,
    val distanceM: Double? = null,
    val durationS: Double? = null,
    val notes: String = "",
    val lastError: String? = null,
)

/** One processing run of a trip. The path itself lives in the JSON file named by [fileName]. */
@Entity(tableName = "path_results", primaryKeys = ["tripId", "runId"])
data class PathResultEntity(
    val tripId: Long,
    /** 1, 2, 3… per trip, in processing order. */
    val runId: Int,
    val pipelineVersion: Int,
    val createdAtEpochMs: Long,
    /** File name inside the trip's results directory, e.g. run-3.json. */
    val fileName: String,
    /** PathStats as JSON, denormalised for list screens. */
    val statsJson: String,
    /** PipelineConfig used for this run, as JSON. */
    val configJson: String,
    /** Short user-visible label, e.g. "PDR", "VIO", "re-run with stride 0.75". */
    val label: String = "",
)

/** Single-row table (id = 1) holding the user's current calibration as a PipelineConfig JSON. */
@Entity(tableName = "calibration")
data class CalibrationEntity(
    @PrimaryKey val id: Int = 1,
    val configJson: String,
    val updatedAtEpochMs: Long,
    val carryPosition: CarryPosition = CarryPosition.HAND,
    val notes: String = "",
)
