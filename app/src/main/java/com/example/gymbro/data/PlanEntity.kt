package com.example.gymbro.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.gymbro.plan.Plan
import com.example.gymbro.plan.PlanDay
import com.example.gymbro.plan.PlanGoal
import com.example.gymbro.plan.PlanLevel
import java.time.Instant

// A workout plan of a user: one row of the "plans" table of the local database, the copy of the
// plan saved on the server, so that it can be seen, followed and changed by hand also offline.
// - changed: changed on this phone (e.g. offline), not sent to the server yet
// - deleted: deleted on this phone, not deleted on the server yet
// They are sent as soon as the server is reachable (see PlanRepository). The plans are created
// online (by the coach, or copied from another user): id is always their id on the server.
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: Long,
    val userId: Long,
    val name: String,
    val goal: PlanGoal?,
    val level: PlanLevel?,
    val daysPerWeek: Int?,
    val minutesPerSession: Int?,
    val preferences: String,
    val notes: String,
    val days: List<PlanDay>,
    val createdAt: Long,        // milliseconds since 1970
    val updatedAt: Long,
    val isPublic: Boolean,
    val fromUsername: String?,
    val changed: Boolean = false,
    val deleted: Boolean = false
) {
    fun toPlan() = Plan(
        id = id, name = name, goal = goal, level = level, daysPerWeek = daysPerWeek,
        minutesPerSession = minutesPerSession, preferences = preferences, notes = notes, days = days,
        createdAt = Instant.ofEpochMilli(createdAt), updatedAt = Instant.ofEpochMilli(updatedAt),
        isPublic = isPublic, fromUsername = fromUsername
    )

    companion object {
        fun of(userId: Long, plan: Plan, changed: Boolean = false) = PlanEntity(
            id = plan.id, userId = userId, name = plan.name, goal = plan.goal, level = plan.level,
            daysPerWeek = plan.daysPerWeek, minutesPerSession = plan.minutesPerSession,
            preferences = plan.preferences, notes = plan.notes, days = plan.days,
            createdAt = plan.createdAt.toEpochMilli(), updatedAt = plan.updatedAt.toEpochMilli(),
            isPublic = plan.isPublic, fromUsername = plan.fromUsername, changed = changed
        )
    }
}
