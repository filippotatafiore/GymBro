package com.example.gymbro.session

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatWeight
import com.example.gymbro.plan.PlanForm

// Fills the exercise list of the gym session in progress: one row (res/layout/item_gym_exercise.xml)
// for each exercise. Tap a row (or its circle) to mark it done / not done; tap the pencil to change it.
class GymExerciseAdapter(
    private val onToggleDone: (Int) -> Unit,
    private val onEdit: (Int) -> Unit
) : RecyclerView.Adapter<GymExerciseAdapter.ExerciseViewHolder>() {

    private var exercises: List<Exercise> = emptyList()

    class ExerciseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val doneButton: ImageButton = view.findViewById(R.id.exerciseDoneButton)
        val texts: View = view.findViewById(R.id.exerciseTexts)
        val editButton: ImageButton = view.findViewById(R.id.exerciseEditButton)
    }

    // NotifyDataSetChanged: the list is small and every change can move the progress of all rows
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun show(exercises: List<Exercise>) {
        this.exercises = exercises
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExerciseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gym_exercise, parent, false)
        return ExerciseViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExerciseViewHolder, position: Int) {
        val exercise = exercises[position]
        bindExerciseRow(holder.itemView, exercise)

        // done: green circle with a check, texts dimmed
        holder.doneButton.isSelected = exercise.done
        holder.doneButton.imageAlpha = if (exercise.done) 255 else 0
        holder.doneButton.contentDescription =
            holder.itemView.context.getString(if (exercise.done) R.string.mark_not_done else R.string.mark_done)
        holder.texts.alpha = if (exercise.done) 0.5f else 1f

        holder.doneButton.setOnClickListener { onToggleDone(holder.bindingAdapterPosition) }
        holder.itemView.setOnClickListener { onToggleDone(holder.bindingAdapterPosition) }
        holder.editButton.setOnClickListener { onEdit(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = exercises.size
}

// Writes the data of an exercise into a row: name, muscle group, sets × reps, weight, rest and notes
// (res/layout/item_gym_exercise.xml, and res/layout/item_exercise.xml in the details of a session).
fun bindExerciseRow(row: View, exercise: Exercise) {
    row.findViewById<TextView>(R.id.exerciseName).text = exercise.name
    row.findViewById<TextView>(R.id.exerciseMuscleGroup).apply {
        text = exercise.muscleGroup
        isVisible = exercise.muscleGroup.isNotBlank()
    }
    row.findViewById<TextView>(R.id.exerciseSetsReps).text =
        row.context.getString(R.string.plan_sets_reps, exercise.sets, exercise.reps)

    val weight = row.findViewById<TextView>(R.id.exerciseWeight)
    weight.isVisible = exercise.weightKg != null
    exercise.weightKg?.let { weight.text = formatWeight(it) }

    row.findViewById<TextView?>(R.id.exerciseDetails)?.apply {
        text = exerciseDetails(context, exercise.restSeconds, exercise.notes)
        isVisible = text.isNotEmpty()
    }
}

// e.g. "Rest 90 s · keep the back straight" (empty if there are no rest and notes)
fun exerciseDetails(context: Context, restSeconds: Int?, notes: String): String =
    listOfNotNull(
        restSeconds?.let { context.getString(R.string.plan_rest, PlanForm.formatRest(it)) },
        notes.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
