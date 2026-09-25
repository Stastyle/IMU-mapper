package com.stastyle.imumapper.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, PathResultEntity::class, CalibrationEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun pathResultDao(): PathResultDao
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "imu-mapper.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
