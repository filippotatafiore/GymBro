package com.example.gymbro.session

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R

// Gym session in progress: list of the exercises
class GymSessionFragment : SessionFragment(R.layout.fragment_gym_session) {

    // Placeholder exercises: they will be loaded from the database later on
    private val exercises = listOf(
        Exercise("Bench Press", "Chest", 4, 8),
        Exercise("Incline Dumbbell Press", "Chest", 3, 10),
        Exercise("Cable Crossover", "Chest", 3, 12),
        Exercise("Dips", "Chest - Triceps", 3, 12),
        Exercise("Triceps Pushdown", "Triceps", 3, 12),
        Exercise("Skull Crushers", "Triceps", 3, 10),
        Exercise("Overhead Triceps Extension", "Triceps", 3, 12),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val exerciseList = view.findViewById<RecyclerView>(R.id.exerciseList)
        exerciseList.layoutManager = LinearLayoutManager(requireContext())
        exerciseList.adapter = ExerciseAdapter(exercises)
    }
}
