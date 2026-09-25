package com.stastyle.imumapper.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): TripEntity?

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

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
