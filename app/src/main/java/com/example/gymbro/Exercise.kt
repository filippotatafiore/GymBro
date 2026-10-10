package com.example.gymbro

// A single exercise of a gym session
data class Exercise(
    val name: String,
    val muscleGroup: String,
    val sets: Int,
    val reps: Int
)
