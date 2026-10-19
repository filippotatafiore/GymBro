package com.example.gymbro.session

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.observe
import com.example.gymbro.plan.ExerciseEditor
import com.example.gymbro.showChoiceModal
import com.example.gymbro.viewmodel.GymSessionViewModel

// Gym session in progress: the exercises of the day of the workout plan followed (or added during
// the session), saved with the session when it stops. Every exercise can be marked as done and
// changed (e.g. the weight used): the changes can be kept only for this session or saved also in
// the plan. Exercises and changes: GymSessionViewModel. Everything works also offline: the changes
// of the plan are sent to the server when it is reachable again.
class GymSessionFragment : SessionFragment(R.layout.fragment_gym_session) {

    private val viewModel: GymSessionViewModel by viewModels { GymSessionViewModel.Factory }

    private lateinit var adapter: GymExerciseAdapter
    private lateinit var progressText: TextView
    private lateinit var emptyText: View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // e.g. "Hypertrophy · Day 1 · Push"
        view.findViewById<TextView>(R.id.gymPlanText).text = viewModel.planName
            ?.let { getString(R.string.plan_and_day, it, viewModel.planDayName.orEmpty()) }
            ?: getString(R.string.gym_without_plan)
        progressText = view.findViewById(R.id.gymProgressText)
        emptyText = view.findViewById(R.id.gymEmptyText)

        adapter = GymExerciseAdapter(onToggleDone = ::toggleDone, onEdit = ::editExercise)
        val exerciseList = view.findViewById<RecyclerView>(R.id.exerciseList)
        exerciseList.layoutManager = LinearLayoutManager(requireContext())
        exerciseList.adapter = adapter
        view.findViewById<View>(R.id.addSessionExerciseButton).setOnClickListener { addExercise() }

        viewLifecycleOwner.observe(viewModel.exercises) { exercises ->
            adapter.show(exercises)
            progressText.text = getString(R.string.gym_progress, exercises.count { it.done }, exercises.size)
            progressText.isVisible = exercises.isNotEmpty()
            emptyText.isVisible = exercises.isEmpty()
        }
    }

    private fun toggleDone(index: Int) {
        if (index == RecyclerView.NO_POSITION) return
        viewModel.toggleDone(index)
    }

    // Window to change the exercise (e.g. the weight used); then, if the session follows a plan:
    // keep the changes only for this session, or save them also in the plan
    private fun editExercise(index: Int) {
        if (index == RecyclerView.NO_POSITION) return
        val exercise = viewModel.exercises.value[index]
        ExerciseEditor.open(
            requireContext(), viewLifecycleOwner, exercise.toPlanExercise(), R.string.edit_exercise, null,
            onDelete = { editor ->
                editor.close()
                viewModel.delete(index)
            },
            onSave = { changes, editor ->
                editor.close()
                viewModel.edit(index, exercise, changes)
                val planIndex = exercise.planIndex
                if (planIndex != null && changes != exercise.toPlanExercise()) {
                    askSaveInPlan { viewModel.saveInPlan(planIndex, exercise.name, changes) }
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
                val index = viewModel.add(exercise)
                if (viewModel.followsPlan) {
                    askSaveInPlan { viewModel.addToPlan(index, exercise) }
                }
            }
        )
    }

    private fun askSaveInPlan(onSave: () -> Unit) {
        showChoiceModal(
            requireContext(), viewLifecycleOwner,
            getString(R.string.save_in_plan_title),
            getString(R.string.save_in_plan_message, viewModel.planName.orEmpty()),
            getString(R.string.also_in_plan), getString(R.string.only_this_session),
            onFirst = onSave
        )
    }
}
