package com.example.gymbro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.gymbro.R
import com.example.gymbro.network.ApiException
import com.example.gymbro.plan.ExerciseNotInPlanException
import com.example.gymbro.plan.PlanExercise
import com.example.gymbro.plan.SessionPlanChanges
import com.example.gymbro.repository.Notices
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.Exercise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Gym session in progress (GymSessionFragment): the exercises of the day of the workout plan
// followed (or added during the session), kept in ActiveSession (also when the app is closed).
// Their changes can be kept only for this session or saved also in the plan (SessionPlanChanges),
// also offline: the changes of the plan are sent to the server when it is reachable again.
class GymSessionViewModel(
    private val planChanges: SessionPlanChanges,
    private val notices: Notices,
    private val appScope: CoroutineScope
) : ViewModel() {

    private val _exercises = MutableStateFlow(ActiveSession.exercises)
    val exercises: StateFlow<List<Exercise>> = _exercises.asStateFlow()

    // The plan and the day followed (null = session without plan)
    val planName: String?
        get() = ActiveSession.planName
    val planDayName: String?
        get() = ActiveSession.planDayName
    val followsPlan: Boolean
        get() = ActiveSession.planId != null

    private fun change(change: (MutableList<Exercise>) -> Unit) {
        ActiveSession.exercises = ActiveSession.exercises.toMutableList().also(change)
        _exercises.value = ActiveSession.exercises
    }

    fun toggleDone(index: Int) {
        change { it[index] = it[index].copy(done = !it[index].done) }
    }

    fun delete(index: Int) {
        change { it.removeAt(index) }
    }

    // The exercise at [index] (as it was when the window opened: [exercise]) with the changes
    fun edit(index: Int, exercise: Exercise, changes: PlanExercise) {
        change { it[index] = exercise.withChanges(changes) }
    }

    // A new exercise for this session; returns its position
    fun add(exercise: PlanExercise): Int {
        change { it.add(Exercise.fromPlan(exercise, planIndex = null)) }
        return ActiveSession.exercises.lastIndex
    }

    // The changes of an exercise also in the plan (the next sessions start from them)
    fun saveInPlan(planIndex: Int, oldName: String, changes: PlanExercise) {
        val planId = ActiveSession.planId ?: return
        val day = ActiveSession.planDay ?: return
        updatePlan {
            planChanges.replace(planId, day, planIndex, oldName, changes).synced
        }
    }

    // A new exercise also in the plan: it remembers its position in the day of the plan
    fun addToPlan(index: Int, exercise: PlanExercise) {
        val planId = ActiveSession.planId ?: return
        val day = ActiveSession.planDay ?: return
        updatePlan {
            val (planIndex, saved) = planChanges.add(planId, day, exercise)
            // (the page may be closed meanwhile, or the session stopped)
            val exercises = ActiveSession.exercises
            if (index in exercises.indices) {
                ActiveSession.exercises = exercises.toMutableList().also { it[index] = it[index].copy(planIndex = planIndex) }
                _exercises.value = ActiveSession.exercises
            }
            saved.synced
        }
    }

    // Saves a change of the plan in background: it completes also if the page is closed meanwhile.
    // update: true if saved also on the server (offline: on this phone, see Notices.savedOffline)
    private fun updatePlan(update: suspend () -> Boolean) {
        appScope.launch {
            try {
                if (update()) notices.show(R.string.plan_updated_toast)
            } catch (e: ExerciseNotInPlanException) {
                notices.show(R.string.exercise_not_in_plan, long = true)
            } catch (e: ApiException) {
                notices.showError(e, R.string.plan_not_updated)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = container
                GymSessionViewModel(SessionPlanChanges(app.plans), app.notices, app.appScope)
            }
        }
    }
}
