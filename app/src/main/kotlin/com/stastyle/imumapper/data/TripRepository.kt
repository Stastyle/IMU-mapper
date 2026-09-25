package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.PathResultDao
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripDao
import com.stastyle.imumapper.data.db.TripEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

interface TripRepository {
    fun observeTrips(): Flow<List<TripEntity>>
    fun observeTrip(tripId: Long): Flow<TripEntity?>
    suspend fun getTrip(tripId: Long): TripEntity?
    /** Inserts and returns the new id. Creates the trip directory. */
    suspend fun createTrip(trip: TripEntity): Long
    /**
     * Writes every column of [trip]. Prefer the targeted writes below when only one aspect changes,
     * because this one silently overwrites whatever another writer stored since the row was read.
     */
    suspend fun updateTrip(trip: TripEntity)
    /** Changes only the name; a status change landing at the same time is kept. */
    suspend fun renameTrip(tripId: Long, name: String)
    /** Records a successful processing run: status PROCESSED, latest run and stats, error cleared. */
    suspend fun markProcessed(tripId: Long, runId: Int, distanceM: Double, durationS: Double)
    /** Records a failed processing run: status FAILED and the message; earlier results stay listed. */
    suspend fun markFailed(tripId: Long, error: String)
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TripRepository {

    override fun observeTrips(): Flow<List<TripEntity>> = trips.observeAll()

    override fun observeTrip(tripId: Long): Flow<TripEntity?> = trips.observe(tripId)

    override suspend fun getTrip(tripId: Long): TripEntity? = trips.get(tripId)

    override suspend fun createTrip(trip: TripEntity): Long {
        val id = trips.insert(trip)
        withContext(ioDispatcher) { files.tripDir(id) }
        return id
    }

    override suspend fun updateTrip(trip: TripEntity) = trips.update(trip)

    override suspend fun renameTrip(tripId: Long, name: String) = trips.rename(tripId, name)

    override suspend fun markProcessed(tripId: Long, runId: Int, distanceM: Double, durationS: Double) =
        trips.markProcessed(tripId, runId, distanceM, durationS)

    override suspend fun markFailed(tripId: Long, error: String) = trips.markFailed(tripId, error)

    override suspend fun deleteTrip(tripId: Long) {
        results.deleteForTrip(tripId)
        trips.delete(tripId)
        // Callers launch from the main thread; unlinking a raw log and hundreds of photos is slow.
        withContext(ioDispatcher) { files.deleteTrip(tripId) }
    }

    override fun observeResults(tripId: Long): Flow<List<PathResultEntity>> = results.observeForTrip(tripId)

    override suspend fun listResults(tripId: Long): List<PathResultEntity> = results.listForTrip(tripId)

    override suspend fun getResult(tripId: Long, runId: Int): PathResultEntity? = results.get(tripId, runId)

    override suspend fun nextRunId(tripId: Long): Int = (results.maxRunId(tripId) ?: 0) + 1

    override suspend fun addResult(result: PathResultEntity) = results.insert(result)
}
