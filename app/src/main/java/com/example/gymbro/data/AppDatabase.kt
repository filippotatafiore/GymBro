package com.example.gymbro.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Local database of the app (a SQLite file on the phone). The instance is in GymBroApp.database.
// version: to be increased every time the structure of the tables changes.
@Database(entities = [TrainingSession::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trainingSessionDao(): TrainingSessionDao

    companion object {
        // Version 2 -> 3: statistics and route of the run sessions. The saved sessions are kept
        // (the new columns are empty for them).
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN kcal REAL")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN maxSpeedKmh REAL")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN goalKm REAL")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN route TEXT")
            }
        }
    }
}
