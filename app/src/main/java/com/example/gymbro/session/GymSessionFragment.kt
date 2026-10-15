package com.example.gymbro.session

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.gymBroApp
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.ExerciseEditor
import com.example.gymbro.plan.ExerciseNotInPlanException
import com.example.gymbro.plan.PlanExercise
import com.example.gymbro.plan.SessionPlanChanges
import com.example.gymbro.showChoiceModal
import kotlinx.coroutines.launch

// Gym session in progress: the exercises of the day of the workout plan followed (or added during
// the session), saved with the session when it stops. Every exercise can be marked as done and
// changed (e.g. the weight used): the changes can be kept only for this session or saved also in
// the plan. The exercises are kept in ActiveSession (also when the app is closed).
class GymSessionFragment : SessionFragment(R.layout.fragment_gym_session) {

    private lateinit var adapter: GymExerciseAdapter
    private lateinit var progressText: TextView
    private lateinit var emptyText: View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // e.g. "Hypertrophy · Day 1 · Push"
        view.findViewById<TextView>(R.id.gymPlanText).text = ActiveSession.planName
            ?.let { getString(R.string.plan_and_day, it, ActiveSession.planDayName.orEmpty()) }
            ?: getString(R.string.gym_without_plan)
        progressText = view.findViewById(R.id.gymProgressText)
        emptyText = view.findViewById(R.id.gymEmptyText)

        adapter = GymExerciseAdapter(onToggleDone = ::toggleDone, onEdit = ::editExercise)
        val exerciseList = view.findViewById<RecyclerView>(R.id.exerciseList)
        exerciseList.layoutManager = LinearLayoutManager(requireContext())
        exerciseList.adapter = adapter
        view.findViewById<View>(R.id.addSessionExerciseButton).setOnClickListener { addExercise() }

        show()
    }

    private fun show() {
        val exercises = ActiveSession.exercises
        adapter.show(exercises)
        progressText.text = getString(R.string.gym_progress, exercises.count { it.done }, exercises.size)
        progressText.isVisible = exercises.isNotEmpty()
        emptyText.isVisible = exercises.isEmpty()
    }

    private fun change(change: (MutableList<Exercise>) -> Unit) {
        ActiveSession.exercises = ActiveSession.exercises.toMutableList().also(change)
        show()
    }

    private fun toggleDone(index: Int) {
        if (index == RecyclerView.NO_POSITION) return
        change { it[index] = it[index].copy(done = !it[index].done) }
    }

    // Window to change the exercise (e.g. the weight used); then, if the session follows a plan:
    // keep the changes only for this session, or save them also in the plan
    private fun editExercise(index: Int) {
        if (index == RecyclerView.NO_POSITION) return
        val exercise = ActiveSession.exercises[index]
        ExerciseEditor.open(
            requireContext(), viewLifecycleOwner, exercise.toPlanExercise(), R.string.edit_exercise, null,
            onDelete = { editor ->
                editor.close()
                change { it.removeAt(index) }
            },
            onSave = { changes, editor ->
                editor.close()
                change { it[index] = exercise.withChanges(changes) }
                val planIndex = exercise.planIndex
                if (planIndex != null && changes != exercise.toPlanExercise()) {
                    askSaveInPlan { saveInPlan(planIndex, exercise.name, changes) }
                }
            }
        )
    }

    // A new exercise for this session; if the session follows a plan it can be added also to the plan
    private fun addExercise() {
        ExerciseEditor.open(
            requireContext(), viewLifecycleOwner, null, R.string.new_exercise, null, onDelete = null,
            onSave = { exercise, editor ->
                editor.close()
                change { it.add(Exercise.fromPlan(exercise, planIndex = null)) }
                if (ActiveSession.planId != null) {
                    val index = ActiveSession.exercises.lastIndex
                    askSaveInPlan { addToPlan(index, exercise) }
                }
            }
        )
    }

    private fun askSaveInPlan(onSave: () -> Unit) {
        showChoiceModal(
            requireContext(), viewLifecycleOwner,
            getString(R.string.save_in_plan_title),
            getString(R.string.save_in_plan_message, ActiveSession.planName.orEmpty()),
            getString(R.string.also_in_plan), getString(R.string.only_this_session),
            onFirst = onSave
        )
    }

    // The changes of an exercise also in the plan (the next sessions start from them)
    private fun saveInPlan(planIndex: Int, oldName: String, changes: PlanExercise) {
        val token = UserSession.token ?: return
        val planId = ActiveSession.planId ?: return
        val day = ActiveSession.planDay ?: return
        updatePlan {
            SessionPlanChanges.replace(token, planId, day, planIndex, oldName, changes)
        }
    }

    // A new exercise also in the plan: it remembers its position in the day of the plan
    private fun addToPlan(index: Int, exercise: PlanExercise) {
        val token = UserSession.token ?: return
        val planId = ActiveSession.planId ?: return
        val day = ActiveSession.planDay ?: return
        updatePlan {
            val planIndex = SessionPlanChanges.add(token, planId, day, exercise)
            // (nothing to show: the page may be closed meanwhile, or the session stopped)
            val exercises = ActiveSession.exercises
            if (index in exercises.indices) {
                ActiveSession.exercises = exercises.toMutableList().also { it[index] = it[index].copy(planIndex = planIndex) }
            }
        }
    }

    // Saves a change of the plan in background: it completes also if the page is closed meanwhile
    private fun updatePlan(update: suspend () -> Unit) {
        val app = requireContext().gymBroApp
        app.applicationScope.launch {
            try {
                update()
                Toast.makeText(app, R.string.plan_updated_toast, Toast.LENGTH_SHORT).show()
            } catch (e: ExerciseNotInPlanException) {
                Toast.makeText(app, R.string.exercise_not_in_plan, Toast.LENGTH_LONG).show()
            } catch (e: ApiException) {
                Toast.makeText(app, app.getString(R.string.plan_not_updated, e.userMessage(app)), Toast.LENGTH_LONG).show()
            }
        }
    }
}
