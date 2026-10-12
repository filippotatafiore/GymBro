package com.example.gymbro.profile

import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric

// An exercise/activity that can be published manually, with the default way of evaluating it
// (the user can change it when publishing)
data class ManualExercise(val name: String, val metric: Metric, val better: Better)

// Predefined list of the exercises/activities that can be published manually
object ManualExercises {
    val all = listOf(
        ManualExercise("Squat", Metric.WEIGHT, Better.HIGHER),
        ManualExercise("Deadlift", Metric.WEIGHT, Better.HIGHER),
        ManualExercise("Bench Press", Metric.WEIGHT, Better.HIGHER),
        ManualExercise("Overhead Press", Metric.WEIGHT, Better.HIGHER),
        ManualExercise("Pull-up", Metric.WEIGHT, Better.HIGHER),
        ManualExercise("Dip", Metric.WEIGHT, Better.HIGHER),
        ManualExercise("Muscle-up", Metric.WEIGHT, Better.HIGHER),
        ManualExercise("Front Lever", Metric.TIME, Better.HIGHER),
        ManualExercise("Back Lever", Metric.TIME, Better.HIGHER),
        ManualExercise("Planche", Metric.TIME, Better.HIGHER),
        ManualExercise("Handstand", Metric.TIME, Better.HIGHER),
        ManualExercise("L-sit", Metric.TIME, Better.HIGHER),
        ManualExercise("Plank", Metric.TIME, Better.HIGHER),
        ManualExercise("Dead Hang", Metric.TIME, Better.HIGHER),
        ManualExercise("100 m Sprint", Metric.TIME, Better.LOWER),
        ManualExercise("1 km Row", Metric.TIME, Better.LOWER),
    )
}
