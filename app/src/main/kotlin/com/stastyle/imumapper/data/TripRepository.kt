package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.PathResultDao
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripDao
import com.stastyle.imumapper.data.db.TripEntity
import kotlinx.coroutines.flow.Flow

interface TripRepository {
    fun observeTrips(): Flow<List<TripEntity>>
    fun observeTrip(tripId: Long): Flow<TripEntity?>
    suspend fun getTrip(tripId: Long): TripEntity?
    /** Inserts and returns the new id. Creates the trip directory. */
    suspend fun createTrip(trip: TripEntity): Long
    suspend fun updateTrip(trip: TripEntity)
    /** Removes the row, its results and every file of the trip. */
    suspend fun deleteTrip(tripId: Long)

    fun observeResults(tripId: Long): Flow<List<PathResultEntity>>
    suspend fun listResults(tripId: Long): List<PathResultEntity>
    suspend fun getResult(tripId: Long, runId: Int): PathResultEntity?
    /** Next free runId for the trip (1 for the first run). */
    suspend fun nextRunId(tripId: Long): Int
    suspend fun addResult(result: PathResultEntity)
}

class RoomTripRepository(
    private val trips: TripDao,
    private val results: PathResultDao,
    private val files: TripFiles,
) : TripRepository {

    override fun observeTrips(): Flow<List<TripEntity>> = trips.observeAll()

    override fun observeTrip(tripId: Long): Flow<TripEntity?> = trips.observe(tripId)

    override suspend fun getTrip(tripId: Long): TripEntity? = trips.get(tripId)

    override suspend fun createTrip(trip: TripEntity): Long {
        val id = trips.insert(trip)
        files.tripDir(id)
        return id
    }

    override suspend fun updateTrip(trip: TripEntity) = trips.update(trip)

    override suspend fun deleteTrip(tripId: Long) {
        results.deleteForTrip(tripId)
        trips.delete(tripId)
        files.deleteTrip(tripId)
    }

    override fun observeResults(tripId: Long): Flow<List<PathResultEntity>> = results.observeForTrip(tripId)

    override suspend fun listResults(tripId: Long): List<PathResultEntity> = results.listForTrip(tripId)

    override suspend fun getResult(tripId: Long, runId: Int): PathResultEntity? = results.get(tripId, runId)

    override suspend fun nextRunId(tripId: Long): Int = (results.maxRunId(tripId) ?: 0) + 1

    override suspend fun addResult(result: PathResultEntity) = results.insert(result)
}
