package com.example.gymbro.session

// Exercises of the gym session: shown during the session and saved with it when it is stopped.
// PLACEHOLDER: a fixed list, to be replaced by workout plans chosen/edited by the user.
object WorkoutPlan {
    val exercises = listOf(
        Exercise("Bench Press", "Chest", 4, 8, 60.0),
        Exercise("Incline Dumbbell Press", "Chest", 3, 10, 22.0),
        Exercise("Cable Crossover", "Chest", 3, 12, 15.0),
        Exercise("Dips", "Chest - Triceps", 3, 12),
        Exercise("Triceps Pushdown", "Triceps", 3, 12, 25.0),
        Exercise("Skull Crushers", "Triceps", 3, 10, 20.0),
        Exercise("Overhead Triceps Extension", "Triceps", 3, 12, 18.0),
    )
}
