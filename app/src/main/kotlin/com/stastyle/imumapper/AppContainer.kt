package com.stastyle.imumapper

import android.content.Context
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.data.RoomCalibrationRepository
import com.stastyle.imumapper.data.RoomTripRepository
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.AppDatabase
import com.stastyle.imumapper.pipeline.DefaultProcessor
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.process.DefaultTripProcessor
import com.stastyle.imumapper.process.TripProcessor
import kotlinx.serialization.json.Json

/**
 * Manual dependency container. Screens reach it through [ImuMapperApp.container] or the
 * [com.stastyle.imumapper.ui.common.appContainer] composable helper. Add new singletons here.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val tripFiles: TripFiles by lazy { TripFiles(appContext) }

    val tripRepository: TripRepository by lazy {
        RoomTripRepository(database.tripDao(), database.pathResultDao(), tripFiles)
    }

    val calibrationRepository: CalibrationRepository by lazy {
        RoomCalibrationRepository(database.calibrationDao(), json)
    }

    val processor: Processor by lazy { DefaultProcessor() }

    val tripProcessor: TripProcessor by lazy {
        DefaultTripProcessor(tripRepository, calibrationRepository, tripFiles, processor)
    }
}
