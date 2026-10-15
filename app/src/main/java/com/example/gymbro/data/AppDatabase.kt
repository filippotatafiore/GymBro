package com.example.gymbro.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Local database of the app (a SQLite file on the phone). The instance is in GymBroApp.database.
// version: to be increased every time the structure of the tables changes.
@Database(entities = [PendingSession::class, ChatMessage::class], version = 6, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingSessionDao(): PendingSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

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
    }
}
