package com.example.gymbro.session

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatWeight

// Fills the exercise list of the gym session: one row (res/layout/item_exercise.xml) for each exercise
class ExerciseAdapter(private val exercises: List<Exercise>) :
    RecyclerView.Adapter<ExerciseAdapter.ExerciseViewHolder>() {

    class ExerciseViewHolder(view: View) : RecyclerView.ViewHolder(view)

    // Creates a new (empty) row
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExerciseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exercise, parent, false)
        return ExerciseViewHolder(view)
    }

    // Writes the data of the exercise at the given position into a row
    override fun onBindViewHolder(holder: ExerciseViewHolder, position: Int) {
        bindExerciseRow(holder.itemView, exercises[position])
    }

    override fun getItemCount() = exercises.size
}

// Writes the data of an exercise into a row (res/layout/item_exercise.xml).
// Also used by the details of a published gym session.
fun bindExerciseRow(row: View, exercise: Exercise) {
    row.findViewById<TextView>(R.id.exerciseName).text = exercise.name
    row.findViewById<TextView>(R.id.exerciseMuscleGroup).text = exercise.muscleGroup
    row.findViewById<TextView>(R.id.exerciseSetsReps).text =
        row.context.getString(R.string.sets_reps, exercise.sets, exercise.reps)

    val weight = row.findViewById<TextView>(R.id.exerciseWeight)
    weight.isVisible = exercise.weightKg != null
    exercise.weightKg?.let { weight.text = formatWeight(it) }
}
