package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.CalibrationDao
import com.stastyle.imumapper.data.db.CalibrationEntity
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** The user's current calibration, applied to every new recording and re-processing. */
interface CalibrationRepository {
    fun observeConfig(): Flow<PipelineConfig>
    suspend fun getConfig(): PipelineConfig
    suspend fun saveConfig(config: PipelineConfig, notes: String = "")
    fun observeCarryPosition(): Flow<CarryPosition>
    suspend fun getCarryPosition(): CarryPosition
    suspend fun saveCarryPosition(position: CarryPosition)
}

class RoomCalibrationRepository(
    private val dao: CalibrationDao,
    private val json: Json,
) : CalibrationRepository {

    override fun observeConfig(): Flow<PipelineConfig> = dao.observe().map { it?.let(::decode) ?: PipelineConfig() }

    override suspend fun getConfig(): PipelineConfig = dao.get()?.let(::decode) ?: PipelineConfig()

    override suspend fun saveConfig(config: PipelineConfig, notes: String) {
        val existing = dao.get()
        dao.upsert(
            CalibrationEntity(
                configJson = json.encodeToString(PipelineConfig.serializer(), config),
                updatedAtEpochMs = System.currentTimeMillis(),
                carryPosition = existing?.carryPosition ?: CarryPosition.HAND,
                notes = notes.ifBlank { existing?.notes ?: "" },
            ),
        )
    }

    override fun observeCarryPosition(): Flow<CarryPosition> = dao.observe().map { it?.carryPosition ?: CarryPosition.HAND }

    override suspend fun getCarryPosition(): CarryPosition = dao.get()?.carryPosition ?: CarryPosition.HAND

    override suspend fun saveCarryPosition(position: CarryPosition) {
        val existing = dao.get()
        dao.upsert(
            CalibrationEntity(
                configJson = existing?.configJson ?: json.encodeToString(PipelineConfig.serializer(), PipelineConfig()),
                updatedAtEpochMs = System.currentTimeMillis(),
                carryPosition = position,
                notes = existing?.notes ?: "",
            ),
        )
    }

    private fun decode(entity: CalibrationEntity): PipelineConfig =
        runCatching { json.decodeFromString(PipelineConfig.serializer(), entity.configJson) }
            .getOrDefault(PipelineConfig())
}
