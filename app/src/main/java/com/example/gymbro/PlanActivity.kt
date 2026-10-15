package com.example.gymbro

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.gymbro.chat.CoachChat
import com.example.gymbro.chat.styledText
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.ExerciseEditor
import com.example.gymbro.plan.Plan
import com.example.gymbro.plan.PlanCache
import com.example.gymbro.plan.PlanForm
import com.example.gymbro.session.exerciseDetails
import kotlinx.coroutines.launch

// Page of a gym workout plan (open it from WORKOUT PLANS of the profile). The plan is read from the
// server every time the page is shown: it also shows the changes made by the coach in the chat.
// Changes by hand, saved on the server right away:
// - tap an exercise: change it, replace it with another one, or delete it
// - "+ Add exercise" under a day, "+ Add day" at the end
// - tap a day: rename or delete it; tap the title: rename the plan
// "Ask the coach to change it": chat about this plan (ChatActivity), where the coach sees the plan
// as it is now and can change it.
class PlanActivity : ThemedActivity() {

    private var planId = 0L
    private var plan: Plan? = null
    // a change is being saved: the other changes wait (they would start from an old plan)
    private var saving = false

    private lateinit var title: TextView
    private lateinit var content: LinearLayout
    private lateinit var loading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }
        planId = intent.getLongExtra(EXTRA_PLAN_ID, 0L)

        // link to the layout
        setContentView(R.layout.activity_plan)

        title = findViewById(R.id.planTitle)
        content = findViewById(R.id.planContent)
        loading = findViewById(R.id.planLoading)

        findViewById<View>(R.id.planBackButton).setOnClickListener { finish() }
        title.setOnClickListener { renamePlan() }
        findViewById<View>(R.id.deletePlanButton).setOnClickListener { confirmDeletePlan() }
        findViewById<Button>(R.id.askCoachButton).setOnClickListener {
            plan?.let { ChatActivity.open(this, it) }
        }

        // no bottom strip: the button stays above the navigation bar of the phone
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.planRoot)) { root, insets ->
            root.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        loadPlan()
    }

    // Reads the plan from the server (in background)
    private fun loadPlan() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                show(ApiClient.getPlan(token, planId))
            } catch (e: ApiException) {
                if (e.code == 404) {
                    Toast.makeText(this@PlanActivity, R.string.plan_not_found, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@PlanActivity, e.userMessage(this@PlanActivity), Toast.LENGTH_LONG).show()
                }
            } finally {
                loading.isVisible = false
            }
        }
    }


    // --------------------------------------------------------- PLAN

    // Information and advice of the coach, then each day with its exercises
    private fun show(plan: Plan) {
        this.plan = plan
        title.text = plan.name
        content.removeAllViews()
        addInfo(plan)

        layoutInflater.inflate(R.layout.item_plan_hint, content, true)

        plan.days.forEachIndexed { dayIndex, day ->
            val header = layoutInflater.inflate(R.layout.item_plan_day, content, false)
            header.findViewById<TextView>(R.id.dayName).text = day.name
            header.findViewById<TextView>(R.id.dayExerciseCount).text =
                resources.getQuantityString(R.plurals.plan_exercises, day.exercises.size, day.exercises.size)
            header.setOnClickListener { editDay(dayIndex) }
            content.addView(header)

            day.exercises.forEachIndexed { exerciseIndex, exercise ->
                val row = layoutInflater.inflate(R.layout.item_plan_exercise, content, false)
                row.findViewById<TextView>(R.id.planExerciseName).text = exercise.name
                row.findViewById<TextView>(R.id.planExerciseMuscle).apply {
                    text = exercise.muscleGroup
                    isVisible = exercise.muscleGroup.isNotBlank()
                }
                row.findViewById<TextView>(R.id.planExerciseSetsReps).text =
                    getString(R.string.plan_sets_reps, exercise.sets, exercise.reps)
                // e.g. "60 kg · Rest 90 s · keep the back straight"
                val details = listOfNotNull(
                    exercise.weightKg?.let { formatWeight(it) },
                    exerciseDetails(this, exercise.restSeconds, exercise.notes).takeIf { it.isNotEmpty() }
                ).joinToString(" · ")
                row.findViewById<TextView>(R.id.planExerciseDetails).apply {
                    text = details
                    isVisible = details.isNotEmpty()
                }
                row.setOnClickListener { showExerciseDialog(dayIndex, exerciseIndex) }
                content.addView(row)
            }

            if (day.exercises.size < Plan.MAX_DAY_EXERCISES) {
                addLink(R.string.add_exercise) { showExerciseDialog(dayIndex, null) }
            }
        }

        if (plan.days.size < Plan.MAX_DAYS) addLink(R.string.add_day) { addDay() }
    }

    // The active plan (followed by the gym sessions) or the button to make it active; what the user
    // asked (goal, level, days, minutes, preferences) and the advice of the coach
    private fun addInfo(plan: Plan) {
        val card = layoutInflater.inflate(R.layout.item_plan_info, content, false)
        val isActive = UserSession.user?.activePlanId == plan.id
        card.findViewById<View>(R.id.planActiveLabel).isVisible = isActive
        card.findViewById<Button>(R.id.planActivateButton).apply {
            isVisible = !isActive
            setOnClickListener { setActive(plan) }
        }
        val rows = card.findViewById<LinearLayout>(R.id.planInfoRows)
        plan.goal?.let { addDetailRow(rows, R.string.plan_goal_label, getString(it.label)) }
        plan.level?.let { addDetailRow(rows, R.string.plan_level_label, getString(it.label)) }
        plan.daysPerWeek?.let { addDetailRow(rows, R.string.plan_days_label, it.toString()) }
        plan.minutesPerSession?.let { addDetailRow(rows, R.string.plan_minutes_label, getString(R.string.minutes_value, it)) }
        if (plan.preferences.isNotBlank()) addDetailRow(rows, R.string.plan_preferences, plan.preferences)

        val notes = card.findViewById<TextView>(R.id.planNotes)
        notes.text = styledText(plan.notes, notes.paint)
        notes.isVisible = plan.notes.isNotBlank()
        card.findViewById<View>(R.id.planNotesTitle).isVisible = plan.notes.isNotBlank()
        card.findViewById<TextView>(R.id.planUpdatedText).text = getString(R.string.plan_updated_on, formatDate(plan.updatedAt))
        content.addView(card)
    }

    // "+ Add exercise" / "+ Add day"
    private fun addLink(@StringRes text: Int, onClick: () -> Unit) {
        val link = layoutInflater.inflate(R.layout.item_plan_add, content, false) as TextView
        link.setText(text)
        link.setOnClickListener { onClick() }
        content.addView(link)
    }

    // Saves a change on the server, then shows the saved plan. onError: the message of the error
    // (e.g. shown in the open window); onSaved: e.g. closes the window.
    private fun save(changed: Plan, onError: (String) -> Unit = ::showToast, onSaved: () -> Unit = {}) {
        val token = UserSession.token ?: return
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                show(ApiClient.updatePlan(token, changed))
                onSaved()
            } catch (e: ApiException) {
                onError(e.userMessage(this@PlanActivity))
            } finally {
                saving = false
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }


    // The gym sessions will follow this plan (the server remembers it for the account)
    private fun setActive(plan: Plan) {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                UserSession.updateUser(ApiClient.setActivePlan(token, plan.id))
                PlanCache.save(plan)
                Toast.makeText(this@PlanActivity, R.string.plan_now_active, Toast.LENGTH_SHORT).show()
                show(plan)
            } catch (e: ApiException) {
                showToast(e.userMessage(this@PlanActivity))
            }
        }
    }


    // --------------------------------------------------------- EXERCISES

    // Window to change an exercise (or replace it with another one written by the user, or delete it).
    // exerciseIndex = null: a new exercise at the end of the day.
    private fun showExerciseDialog(dayIndex: Int, exerciseIndex: Int?) {
        val plan = plan ?: return
        val exercise = exerciseIndex?.let { plan.days[dayIndex].exercises[it] }
        ExerciseEditor.open(
            this, this, exercise,
            title = if (exercise == null) R.string.new_exercise else R.string.edit_exercise,
            hint = if (exercise == null) null else R.string.edit_exercise_hint,
            onDelete = if (exerciseIndex == null) null else { editor ->
                save(plan.removeExercise(dayIndex, exerciseIndex), onError = editor::showError) { editor.close() }
            },
            onSave = { changes, editor ->
                val changed =
                    if (exerciseIndex == null) plan.addExercise(dayIndex, changes)
                    else plan.replaceExercise(dayIndex, exerciseIndex, changes)
                save(changed, onError = editor::showError) { editor.close() }
            }
        )
    }


    // --------------------------------------------------------- DAYS AND NAME

    // Window with a name to write. maxLength: as on the server. Returns the window, its text field
    // and its error text; the buttons are set up by the caller.
    private fun nameDialog(@StringRes dialogTitle: Int, name: String, maxLength: Int): Triple<Dialog, EditText, TextView> {
        val dialog = createModal(this, R.layout.dialog_plan_name, this)
        dialog.findViewById<TextView>(R.id.nameDialogTitle).setText(dialogTitle)
        val input = dialog.findViewById<EditText>(R.id.nameInput)
        input.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        input.setText(name)
        input.setSelection(input.text.length)
        return Triple(dialog, input, dialog.findViewById(R.id.nameError))
    }

    // Saves the name written in the window: the change is made by [change]
    private fun Dialog.onSaveName(input: EditText, error: TextView, change: (String) -> Plan) {
        findViewById<Button>(R.id.saveNameButton).setOnClickListener {
            when (val check = PlanForm.name(input.text.toString())) {
                is PlanForm.Check.Invalid -> error.showError(getString(check.message))
                is PlanForm.Check.Valid -> {
                    hideKeyboard(this)
                    save(change(check.value), onError = { error.showError(it) }) { dismiss() }
                }
            }
        }
    }

    private fun renamePlan() {
        val plan = plan ?: return
        val (dialog, input, error) = nameDialog(R.string.plan_name, plan.name, 60)
        dialog.onSaveName(input, error) { name -> plan.copy(name = name) }
        dialog.show()
    }

    // Rename the day, or delete it (after a confirmation; the last day cannot be deleted)
    private fun editDay(dayIndex: Int) {
        val plan = plan ?: return
        val (dialog, input, error) = nameDialog(R.string.day_name, plan.days[dayIndex].name, 40)
        dialog.onSaveName(input, error) { name -> plan.renameDay(dayIndex, name) }
        dialog.findViewById<Button>(R.id.deleteNameButton).apply {
            isVisible = plan.days.size > 1
            setOnClickListener {
                dialog.dismiss()
                showConfirmModal(
                    this@PlanActivity, this@PlanActivity,
                    R.string.delete_day_title, R.string.delete_day_message, R.string.delete
                ) { save(plan.removeDay(dayIndex)) }
            }
        }
        dialog.show()
    }

    private fun addDay() {
        val plan = plan ?: return
        val (dialog, input, error) = nameDialog(R.string.new_day, getString(R.string.day_default_name, plan.days.size + 1), 40)
        dialog.onSaveName(input, error) { name -> plan.addDay(name) }
        dialog.show()
    }

    private fun hideKeyboard(dialog: Dialog) {
        dialog.currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }


    // --------------------------------------------------------- DELETE

    // After a confirmation: deletes the plan on the server and the chat about it on the phone
    private fun confirmDeletePlan() {
        showConfirmModal(this, this, R.string.delete_plan_title, R.string.delete_plan_message, R.string.delete) {
            val token = UserSession.token ?: return@showConfirmModal
            val userId = UserSession.userId ?: return@showConfirmModal
            lifecycleScope.launch {
                try {
                    ApiClient.deletePlan(token, planId)
                    CoachChat.clear(this@PlanActivity, CoachChat.Conversation(userId, planId))
                    // it was the active plan: the server has no active plan now
                    UserSession.user?.takeIf { it.activePlanId == planId }?.let {
                        UserSession.updateUser(it.copy(activePlanId = null))
                    }
                    finish()
                } catch (e: ApiException) {
                    showToast(e.userMessage(this@PlanActivity))
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PLAN_ID = "plan_id"

        fun open(context: Context, planId: Long) {
            context.startActivity(Intent(context, PlanActivity::class.java).putExtra(EXTRA_PLAN_ID, planId))
        }
    }
}
