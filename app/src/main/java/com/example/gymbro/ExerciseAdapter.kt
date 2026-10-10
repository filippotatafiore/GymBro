package com.example.gymbro

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Fills the exercise list of the Gym page: one row (res/layout/item_exercise.xml) for each exercise
class ExerciseAdapter(private val exercises: List<Exercise>) :
    RecyclerView.Adapter<ExerciseAdapter.ExerciseViewHolder>() {

    // References to the views of a single row
    class ExerciseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.exerciseName)
        val muscleGroup: TextView = view.findViewById(R.id.exerciseMuscleGroup)
        val setsReps: TextView = view.findViewById(R.id.exerciseSetsReps)
    }

    // Creates a new (empty) row
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExerciseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exercise, parent, false)
        return ExerciseViewHolder(view)
    }

    // Writes the data of the exercise at the given position into a row
    override fun onBindViewHolder(holder: ExerciseViewHolder, position: Int) {
        val exercise = exercises[position]
        holder.name.text = exercise.name
        holder.muscleGroup.text = exercise.muscleGroup
        holder.setsReps.text = holder.itemView.context.getString(R.string.sets_reps, exercise.sets, exercise.reps)
    }

    override fun getItemCount() = exercises.size
}
