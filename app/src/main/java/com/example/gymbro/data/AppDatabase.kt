package com.example.gymbro.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Local database of the app (a SQLite file on the phone). The instance is in GymBroApp.database.
// The app is "offline first": the pages show the data saved here (finished sessions, workout plans,
// posts and profile of the logged user, chats with the coach), and the repositories
// (package repository) keep them in sync with the server.
// version: to be increased every time the structure of the tables changes.
@Database(
    entities = [SessionEntity::class, ChatMessage::class, PlanEntity::class, PostEntity::class, ProfileEntity::class],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun planDao(): PlanDao
    abstract fun postDao(): PostDao
    abstract fun profileDao(): ProfileDao

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

        // Version 3 -> 4: new table of the chat with the AI coach. The saved sessions are kept.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, `text` TEXT NOT NULL, `model` TEXT, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        // Version 4 -> 5: chats about the workout plans. The saved messages stay in the general chat.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN planId INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN planUpdated INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Version 5 -> 6: the finished sessions are saved on the server; this table keeps only
        // those waiting to be sent (the saved ones are sent too). New columns: the id that avoids
        // sending a session twice, and the workout plan followed.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN clientId TEXT")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN planId INTEGER")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN planName TEXT")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN planDay INTEGER")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN planDayName TEXT")
            }
        }

        // Version 6 -> 7: offline first. The sessions table keeps also the copy of the sessions of
        // the server (serverId) and the sessions deleted offline (deleted); the sessions waiting to
        // be sent are kept. New tables: workout plans, posts and profile of the user.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE training_sessions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                // the sessions saved by the oldest versions had no clientId
                db.execSQL(
                    "UPDATE training_sessions SET clientId = 'local-' || userId || '-' || id || '-' || startTime " +
                        "WHERE clientId IS NULL"
                )
                // the same tables that Room creates in a new database
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plans` (`id` INTEGER NOT NULL, `userId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `goal` TEXT, `level` TEXT, `daysPerWeek` INTEGER, " +
                        "`minutesPerSession` INTEGER, `preferences` TEXT NOT NULL, `notes` TEXT NOT NULL, " +
                        "`days` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`isPublic` INTEGER NOT NULL, `fromUsername` TEXT, `changed` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `posts` (`id` INTEGER NOT NULL, `userId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `metric` TEXT, `better` TEXT, `amount` REAL, " +
                        "`notes` TEXT, `durationSeconds` INTEGER, `distanceKm` REAL, `kcal` REAL, " +
                        "`maxSpeedKmh` REAL, `goalKm` REAL, `route` TEXT, `exercises` TEXT, " +
                        "`sessionStart` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (`userId` INTEGER NOT NULL, " +
                        "`followersCount` INTEGER NOT NULL, `followingCount` INTEGER NOT NULL, `picture` BLOB, " +
                        "PRIMARY KEY(`userId`))"
                )
            }
        }

        // Version 7 -> 8: steps of the runs (step sensor) and swim sessions (pool length, laps swum,
        // goal in laps), in the sessions and in the posts. The saved data are kept.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("training_sessions", "posts")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN steps INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN poolLengthM REAL")
                    db.execSQL("ALTER TABLE $table ADD COLUMN laps INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN goalLaps INTEGER")
                }
            }
        }
    }
}
