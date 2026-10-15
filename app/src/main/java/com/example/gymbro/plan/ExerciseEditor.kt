package com.example.gymbro.plan

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.showError
import java.text.DecimalFormat

// Window to change an exercise (or replace it, writing another one), or to add one: name, muscle
// group, sets, reps, weight, rest and notes (res/layout/dialog_plan_exercise.xml). Used by the page
// of a workout plan and by the gym session in progress. The checks are in PlanForm.
class ExerciseEditor private constructor(private val dialog: Dialog, private val error: TextView) {

    // e.g. the error of the server when saving: the window stays open
    fun showError(message: String) = error.showError(message)

    fun close() = dialog.dismiss()

    companion object {
        // exercise = null: a new exercise (empty fields, 3 sets). hint: text under the title
        // (null = none). onDelete = null: no delete button. onSave: the checked exercise; the
        // window stays open until close().
        fun open(
            context: Context,
            owner: LifecycleOwner,
            exercise: PlanExercise?,
            @StringRes title: Int,
            @StringRes hint: Int?,
            onDelete: ((ExerciseEditor) -> Unit)?,
            onSave: (PlanExercise, ExerciseEditor) -> Unit
        ) {
            val dialog = createModal(context, R.layout.dialog_plan_exercise, owner)
            val editor = ExerciseEditor(dialog, dialog.findViewById(R.id.planExerciseError))
            val name = dialog.findViewById<EditText>(R.id.planExerciseNameInput)
            val muscle = dialog.findViewById<EditText>(R.id.planExerciseMuscleInput)
            val sets = dialog.findViewById<EditText>(R.id.planExerciseSetsInput)
            val reps = dialog.findViewById<EditText>(R.id.planExerciseRepsInput)
            val weight = dialog.findViewById<EditText>(R.id.planExerciseWeightInput)
            val rest = dialog.findViewById<EditText>(R.id.planExerciseRestInput)
            val notes = dialog.findViewById<EditText>(R.id.planExerciseNotesInput)

            dialog.findViewById<TextView>(R.id.exerciseDialogTitle).setText(title)
            dialog.findViewById<TextView>(R.id.exerciseDialogHint).apply {
                isVisible = hint != null
                hint?.let { setText(it) }
            }
            if (exercise != null) {
                name.setText(exercise.name)
                muscle.setText(exercise.muscleGroup)
                sets.setText(exercise.sets.toString())
                reps.setText(exercise.reps)
                exercise.weightKg?.let { weight.setText(DecimalFormat("0.##").format(it)) }
                exercise.restSeconds?.let { rest.setText(it.toString()) }
                notes.setText(exercise.notes)
            } else {
                sets.setText("3")
            }

            dialog.findViewById<Button>(R.id.deleteExerciseButton).apply {
                isVisible = onDelete != null
                setOnClickListener { onDelete?.invoke(editor) }
            }
            dialog.findViewById<Button>(R.id.saveExerciseButton).setOnClickListener { button ->
                val check = PlanForm.exercise(
                    name.text.toString(), muscle.text.toString(), sets.text.toString(), reps.text.toString(),
                    rest.text.toString(), notes.text.toString(), weight.text.toString()
                )
                when (check) {
                    is PlanForm.Check.Invalid -> editor.showError(context.getString(check.message))
                    is PlanForm.Check.Valid -> {
                        hideKeyboard(context, button)
                        onSave(check.value, editor)
                    }
                }
            }
            dialog.show()
        }

        private fun hideKeyboard(context: Context, view: View) {
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
