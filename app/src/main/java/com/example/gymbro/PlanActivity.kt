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
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.gymbro.chat.CoachChat
import com.example.gymbro.chat.styledText
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.SharedPlan
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.ExerciseEditor
import com.example.gymbro.plan.Plan
import com.example.gymbro.plan.PlanForm
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.session.exerciseDetails
import kotlinx.coroutines.launch

// Page of a gym workout plan. The plan is read every time the page is shown: it also shows the
// changes made by the coach in the chat.
// - your plans (WORKOUT PLANS of your profile): the version saved on this phone is shown at once,
//   then the one of the server (see PlanRepository). The changes by hand are saved at once on this
//   phone and on the server, also offline (sent to the server when it is reachable again):
//   - tap an exercise: change it, replace it with another one, or delete it
//   - "+ Add exercise" under a day, "+ Add day" at the end
//   - tap a day: rename or delete it; tap the title: rename the plan
//   - private or public (everyone sees it on your profile): only online
//   "Ask the coach to change it": chat about this plan (ChatActivity), where the coach sees the plan
//   as it is now and can change it (only online).
// - a public plan of another user (their profile, see openPublic) or a plan shared with Messages
//   (see openShared): only to see, with "Add to my plans": a copy, then a plan like the others.
class PlanActivity : ThemedActivity() {

    // OWN: a plan of the logged user; PUBLIC: a public plan of another user; SHARED: a plan sent
    // with Messages (by the logged user or to them)
    private enum class Mode { OWN, PUBLIC, SHARED }

    private var mode = Mode.OWN
    private var planId = 0L
    private var plan: Plan? = null
    // PUBLIC: the user of the plan; SHARED: the plan shared
    private var ownerId = 0L
    private var ownerName = ""
    private var shareId = 0L
    private var shared: SharedPlan? = null
    // a change is being saved: the other changes wait (they would start from an old plan)
    private var saving = false

    private lateinit var title: TextView
    private lateinit var content: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var bottomButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }
        planId = intent.getLongExtra(EXTRA_PLAN_ID, 0L)
        ownerId = intent.getLongExtra(EXTRA_OWNER_ID, 0L)
        ownerName = intent.getStringExtra(EXTRA_OWNER_NAME).orEmpty()
        shareId = intent.getLongExtra(EXTRA_SHARE_ID, 0L)
        mode = when {
            shareId > 0 -> Mode.SHARED
            ownerId > 0 -> Mode.PUBLIC
            else -> Mode.OWN
        }

        // link to the layout
        setContentView(R.layout.activity_plan)

        title = findViewById(R.id.planTitle)
        content = findViewById(R.id.planContent)
        loading = findViewById(R.id.planLoading)
        bottomButton = findViewById(R.id.askCoachButton)

        findViewById<View>(R.id.planBackButton).setOnClickListener { finish() }
        if (mode == Mode.OWN) {
            title.setOnClickListener { renamePlan() }
            findViewById<View>(R.id.deletePlanButton).setOnClickListener { confirmDeletePlan() }
            bottomButton.setOnClickListener { plan?.let { ChatActivity.open(this, it) } }
        } else {
            // nothing can be changed: the title is not a button, no delete
            title.background = null
            findViewById<View>(R.id.deletePlanButton).isVisible = false
            bottomButton.setText(R.string.add_to_my_plans)
            bottomButton.isVisible = false
            bottomButton.setOnClickListener { addToMyPlans() }
        }

        // no bottom strip: the button stays above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.planRoot))
    }

    override fun onResume() {
        super.onResume()
        loadPlan()
    }

    // Reads the plan (in background): your plans from this phone and then from the server, the
    // others only from the server
    private fun loadPlan() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                when (mode) {
                    Mode.OWN -> loadOwnPlan()
                    Mode.PUBLIC -> show(ApiClient.getPublicPlan(ownerId, planId))
                    Mode.SHARED -> {
                        val shared = ApiClient.getSharedPlan(token, shareId)
                        this@PlanActivity.shared = shared
                        ownerName = shared.user?.username.orEmpty()
                        show(shared.plan)
                    }
                }
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

    // One of your plans: the version saved on this phone at once (also offline), then the one of the
    // server (with the changes made offline sent first). Throws ApiException (404: deleted).
    private suspend fun loadOwnPlan() {
        PlanRepository.plan(planId)?.let {
            show(it)
            loading.isVisible = false
        }
        val problem = PlanRepository.trySync()
        val synced = PlanRepository.plan(planId)
        when {
            synced != null -> if (synced != plan) show(synced)
            // not on this phone and the server is not reachable
            problem != null -> throw problem
            // deleted (e.g. on another phone)
            else -> throw ApiException(404, getString(R.string.plan_not_found))
        }
    }


    // --------------------------------------------------------- PLAN

    // Information and advice of the coach, then each day with its exercises (they can be changed
    // only in your plans)
    private fun show(plan: Plan) {
        this.plan = plan
        val editable = mode == Mode.OWN
        title.text = plan.name
        content.removeAllViews()
        addInfo(plan)

        if (editable) layoutInflater.inflate(R.layout.item_plan_hint, content, true)

        plan.days.forEachIndexed { dayIndex, day ->
            val header = layoutInflater.inflate(R.layout.item_plan_day, content, false)
            header.findViewById<TextView>(R.id.dayName).text = day.name
            header.findViewById<TextView>(R.id.dayExerciseCount).text =
                resources.getQuantityString(R.plurals.plan_exercises, day.exercises.size, day.exercises.size)
            if (editable) header.setOnClickListener { editDay(dayIndex) } else header.background = null
            header.findViewById<View>(R.id.dayEditIcon).isVisible = editable
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
                if (editable) row.setOnClickListener { showExerciseDialog(dayIndex, exerciseIndex) } else row.foreground = null
                content.addView(row)
            }

            if (editable && day.exercises.size < Plan.MAX_DAY_EXERCISES) {
                addLink(R.string.add_exercise) { showExerciseDialog(dayIndex, null) }
            }
        }

        if (editable && plan.days.size < Plan.MAX_DAYS) addLink(R.string.add_day) { addDay() }
        showBottomButton()
    }

    // Your plans: the active plan (followed by the gym sessions) or the button to make it active, and
    // private / public. The plans of other users: who made or shared it. Then what the user asked
    // (goal, level, days, minutes, preferences) and the advice of the coach.
    private fun addInfo(plan: Plan) {
        val card = layoutInflater.inflate(R.layout.item_plan_info, content, false)
        val rows = card.findViewById<LinearLayout>(R.id.planInfoRows)
        val own = mode == Mode.OWN
        val isActive = own && UserSession.user?.activePlanId == plan.id
        card.findViewById<View>(R.id.planActiveLabel).isVisible = isActive
        card.findViewById<Button>(R.id.planActivateButton).apply {
            isVisible = own && !isActive
            setOnClickListener { setActive(plan) }
        }
        card.findViewById<View>(R.id.planVisibility).isVisible = own
        if (own) showVisibility(card, plan)

        val shared = shared
        when {
            mode == Mode.PUBLIC -> addDetailRow(rows, R.string.plan_by, getString(R.string.username_value, ownerName))
            shared != null -> addDetailRow(
                rows, if (shared.fromMe) R.string.plan_shared_with else R.string.plan_shared_by,
                getString(R.string.username_value, ownerName)
            )
            plan.fromUsername != null -> addDetailRow(rows, R.string.plan_copied_from, getString(R.string.username_value, plan.fromUsername))
        }
        plan.goal?.let { addDetailRow(rows, R.string.plan_goal_label, getString(it.label)) }
        plan.level?.let { addDetailRow(rows, R.string.plan_level_label, getString(it.label)) }
        plan.daysPerWeek?.let { addDetailRow(rows, R.string.plan_days_label, it.toString()) }
        plan.minutesPerSession?.let { addDetailRow(rows, R.string.plan_minutes_label, getString(R.string.minutes_value, it)) }
        if (plan.preferences.isNotBlank()) addDetailRow(rows, R.string.plan_preferences, plan.preferences)

        val notes = card.findViewById<TextView>(R.id.planNotes)
        notes.text = styledText(plan.notes, notes.paint)
        notes.isVisible = plan.notes.isNotBlank()
        card.findViewById<View>(R.id.planNotesTitle).isVisible = plan.notes.isNotBlank()
        card.findViewById<TextView>(R.id.planUpdatedText).text =
            if (shared != null) getString(R.string.plan_shared_snapshot, formatDate(shared.createdAt))
            else getString(R.string.plan_updated_on, formatDate(plan.updatedAt))
        content.addView(card)
    }

    // Private / public: like publishing, only online (saved on the server right away)
    private fun showVisibility(card: View, plan: Plan) {
        val group = card.findViewById<RadioGroup>(R.id.planVisibilityGroup)
        group.check(if (plan.isPublic) R.id.planPublicOption else R.id.planPrivateOption)
        card.findViewById<TextView>(R.id.planVisibilityHint)
            .setText(if (plan.isPublic) R.string.plan_public_hint else R.string.plan_private_hint)
        group.setOnCheckedChangeListener { _, checkedId -> setPublic(plan, checkedId == R.id.planPublicOption) }
    }

    private fun setPublic(plan: Plan, public: Boolean) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                show(PlanRepository.setPublic(plan.id, public))
            } catch (e: ApiException) {
                showToast(e.userMessage(this@PlanActivity))
                // the choice shown goes back to the saved one
                show(plan)
            } finally {
                saving = false
            }
        }
    }

    // Your plans: "Ask the coach to change it". The plans of the other users: "Add to my plans", or
    // "Open in my plans" if received and already added; nothing for the plans you shared.
    private fun showBottomButton() {
        val shared = shared
        when {
            mode == Mode.OWN -> return
            shared != null && shared.fromMe -> bottomButton.isVisible = false
            else -> {
                bottomButton.isVisible = true
                bottomButton.setText(if (shared?.savedPlanId != null) R.string.open_in_my_plans else R.string.add_to_my_plans)
            }
        }
    }

    // A copy of the plan among your plans (the plan received and already added: that one), then
    // its page opens: there it can be changed and followed
    private fun addToMyPlans() {
        val token = UserSession.token ?: return
        if (saving) return
        saving = true
        bottomButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val alreadyAdded = shared?.savedPlanId != null
                val copy = when (mode) {
                    Mode.SHARED -> ApiClient.saveSharedPlan(token, shareId)
                    else -> ApiClient.copyPublicPlan(token, ownerId, planId)
                }
                // also on this phone: it can be seen and followed offline
                PlanRepository.store(copy)
                if (!alreadyAdded) Toast.makeText(this@PlanActivity, R.string.plan_added, Toast.LENGTH_SHORT).show()
                open(this@PlanActivity, copy.id)
                finish()
            } catch (e: ApiException) {
                showToast(e.userMessage(this@PlanActivity))
            } finally {
                saving = false
                bottomButton.isEnabled = true
            }
        }
    }

    // "+ Add exercise" / "+ Add day"
    private fun addLink(@StringRes text: Int, onClick: () -> Unit) {
        val link = layoutInflater.inflate(R.layout.item_plan_add, content, false) as TextView
        link.setText(text)
        link.setOnClickListener { onClick() }
        content.addView(link)
    }

    // Saves a change on this phone and on the server (offline: on the server later, see
    // PlanRepository), then shows the saved plan. onError: the message of the error (e.g. shown in
    // the open window); onSaved: e.g. closes the window.
    private fun save(changed: Plan, onError: (String) -> Unit = ::showToast, onSaved: () -> Unit = {}) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                show(PlanRepository.update(changed).plan)
                onSaved()
            } catch (e: ApiException) {
                onError(e.userMessage(this@PlanActivity))
                // e.g. deleted on another phone
                if (e.code == 404) finish()
            } finally {
                saving = false
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }


    // The gym sessions will follow this plan (the server remembers it for the account): at once on
    // this phone, on the server now or (offline) when it is reachable again
    private fun setActive(plan: Plan) {
        UserRepository.setActivePlan(plan.id)
        Toast.makeText(this, R.string.plan_now_active, Toast.LENGTH_SHORT).show()
        show(plan)
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

    // After a confirmation: deletes the plan (on this phone at once, on the server now or, offline,
    // when it is reachable again, see PlanRepository) and the chat about it on the phone
    private fun confirmDeletePlan() {
        showConfirmModal(this, this, R.string.delete_plan_title, R.string.delete_plan_message, R.string.delete) {
            val userId = UserSession.userId ?: return@showConfirmModal
            lifecycleScope.launch {
                PlanRepository.delete(planId)
                CoachChat.clear(this@PlanActivity, CoachChat.Conversation(userId, planId))
                // it was the active plan: the server has no active plan when the plan is deleted
                UserSession.user?.takeIf { it.activePlanId == planId }?.let {
                    UserSession.changeLocally(it.copy(activePlanId = null))
                }
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_PLAN_ID = "plan_id"
        private const val EXTRA_OWNER_ID = "owner_id"
        private const val EXTRA_OWNER_NAME = "owner_name"
        private const val EXTRA_SHARE_ID = "share_id"

        // One of your plans
        fun open(context: Context, planId: Long) {
            context.startActivity(Intent(context, PlanActivity::class.java).putExtra(EXTRA_PLAN_ID, planId))
        }

        // A public plan of another user (from their profile)
        fun openPublic(context: Context, ownerId: Long, ownerName: String, planId: Long) {
            context.startActivity(
                Intent(context, PlanActivity::class.java)
                    .putExtra(EXTRA_PLAN_ID, planId)
                    .putExtra(EXTRA_OWNER_ID, ownerId)
                    .putExtra(EXTRA_OWNER_NAME, ownerName)
            )
        }

        // A plan shared with Messages (sent or received)
        fun openShared(context: Context, shareId: Long) {
            context.startActivity(Intent(context, PlanActivity::class.java).putExtra(EXTRA_SHARE_ID, shareId))
        }
    }
}
