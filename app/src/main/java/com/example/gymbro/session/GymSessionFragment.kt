package com.example.gymbro.session

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R

// Gym session in progress: list of the exercises (the same ones saved with the session when it stops)
class GymSessionFragment : SessionFragment(R.layout.fragment_gym_session) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val exerciseList = view.findViewById<RecyclerView>(R.id.exerciseList)
        exerciseList.layoutManager = LinearLayoutManager(requireContext())
        exerciseList.adapter = ExerciseAdapter(WorkoutPlan.exercises)
    }
}
