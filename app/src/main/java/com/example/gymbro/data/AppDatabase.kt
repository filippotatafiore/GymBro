package com.example.gymbro.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// Local database of the app (a SQLite file on the phone). The instance is in GymBroApp.database.
// version: to be increased every time the structure of the tables changes.
@Database(entities = [TrainingSession::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trainingSessionDao(): TrainingSessionDao
}
