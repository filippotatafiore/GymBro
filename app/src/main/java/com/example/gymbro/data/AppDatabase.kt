package com.example.gymbro.data

import androidx.room.Database
import androidx.room.RoomDatabase

// Local database of the app (a SQLite file on the phone). The instance is in GymBroApp.database.
@Database(entities = [TrainingSession::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trainingSessionDao(): TrainingSessionDao
}
