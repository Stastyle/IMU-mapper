package com.stastyle.imumapper.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A trip with the stats and label of its latest run, as the trip list shows it. A query result, not a table: it must
 * never become an `@Entity`, because a new table changes the schema and needs a Migration. [statsJson] and [runLabel]
 * are null when the trip has no run yet, or when its latest run's row is missing.
 */
data class TripRow(
    @Embedded val trip: TripEntity,
    /** `PathStats` of the latest run as JSON, as [PathResultEntity.statsJson] stores it. */
    val statsJson: String?,
    /** [PathResultEntity.label] of the latest run. */
    val runLabel: String?,
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<TripEntity>>

    /**
     * Every trip, newest first, joined to its latest run. The join matches on [TripEntity.latestRunId], so the card's
     * numbers come from the same run the viewer opens, and a trip without a run keeps its row with nulls.
     */
    @Query(
        "SELECT trips.*, path_results.statsJson AS statsJson, path_results.label AS runLabel FROM trips " +
            "LEFT JOIN path_results ON path_results.tripId = trips.id AND path_results.runId = trips.latestRunId " +
            "ORDER BY startedAtEpochMs DESC",
    )
    fun observeTripRows(): Flow<List<TripRow>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): TripEntity?

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    /** Column-scoped writes: each touches only its own fields, so concurrent editors cannot revert each other. */
    @Query("UPDATE trips SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query(
        "UPDATE trips SET status = 'PROCESSED', latestRunId = :runId, distanceM = :distanceM, " +
            "durationS = :durationS, lastError = NULL WHERE id = :id",
    )
    suspend fun markProcessed(id: Long, runId: Int, distanceM: Double, durationS: Double)

    @Query("UPDATE trips SET status = 'FAILED', lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PathResultDao {
    @Query("SELECT * FROM path_results WHERE tripId = :tripId ORDER BY runId ASC")
    fun observeForTrip(tripId: Long): Flow<List<PathResultEntity>>

    @Query("SELECT * FROM path_results WHERE tripId = :tripId ORDER BY runId ASC")
    suspend fun listForTrip(tripId: Long): List<PathResultEntity>

    @Query("SELECT * FROM path_results WHERE tripId = :tripId AND runId = :runId")
    suspend fun get(tripId: Long, runId: Int): PathResultEntity?

    @Query("SELECT MAX(runId) FROM path_results WHERE tripId = :tripId")
    suspend fun maxRunId(tripId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: PathResultEntity)

    @Delete
    suspend fun delete(result: PathResultEntity)

    @Query("DELETE FROM path_results WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: Long)
}

@Dao
interface CalibrationDao {
    @Query("SELECT * FROM calibration WHERE id = 1")
    fun observe(): Flow<CalibrationEntity?>

    @Query("SELECT * FROM calibration WHERE id = 1")
    suspend fun get(): CalibrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalibrationEntity)
}
