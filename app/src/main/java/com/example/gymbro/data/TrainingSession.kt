package com.example.gymbro.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.gymbro.session.SessionType

// A finished training session: one row of the "training_sessions" table of the local database
@Entity(tableName = "training_sessions")
data class TrainingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SessionType,
    val startTime: Long,        // milliseconds since 1970
    val durationMillis: Long    // training time, pauses excluded
)
