package com.example.gymbro.session

import android.Manifest
import android.app.Dialog
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.formatDuration
import com.example.gymbro.formatMeters
import com.example.gymbro.formatPace100
import com.example.gymbro.themeColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Swim session in progress: the laps swum (in a ring that fills up to the goal), the distance and
// the statistics, and a window when the goal is reached. The laps are added with the big buttons
// (+2, +4, +8, +10) or by voice (VoiceLapCounter): say "GymBro" (or any word), wait for the page to
// show that it is listening, then say the laps, e.g. "four" or "quattro". "Undo" removes the last
// laps added. The screen stays on while the page is shown. The laps are kept in SwimTracker.
class SwimSessionFragment : SessionFragment(R.layout.fragment_swim_session), VoiceLapCounter.Listener {

    private lateinit var ring: LapRingView
    private lateinit var lapCount: TextView
    private lateinit var lapGoalText: TextView
    private lateinit var distanceText: TextView
    private lateinit var toGoText: TextView
    private lateinit var stats: StatGrid
    private lateinit var voiceLevel: VoiceLevelView
    private lateinit var voiceStatus: TextView
    private lateinit var voiceToggle: ImageButton
    private lateinit var undoButton: TextView
    private lateinit var voice: VoiceLapCounter

    // the laps shown: the ring moves only when they change
    private var shownLaps: Int? = null
    private var goalDialog: Dialog? = null

    // A message of the voice counting (e.g. "+4 laps · heard “quattro”"), shown for a few seconds
    private var feedbackJob: Job? = null

    // Asked when the voice counting is turned on without the microphone permission
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            SwimTracker.voiceCounting = true
            voice.start()
        } else {
            Toast.makeText(requireContext(), R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // the phone is usually on the edge of the pool: the counter stays visible
        view.keepScreenOn = true

        ring = view.findViewById(R.id.lapRing)
        lapCount = view.findViewById(R.id.lapCount)
        lapGoalText = view.findViewById(R.id.lapGoalText)
        distanceText = view.findViewById(R.id.swimDistance)
        toGoText = view.findViewById(R.id.swimToGo)
        stats = StatGrid(view.findViewById(R.id.swimStats), columns = 3)
        voiceLevel = view.findViewById(R.id.voiceLevel)
        voiceStatus = view.findViewById(R.id.voiceStatus)
        voiceToggle = view.findViewById(R.id.voiceToggle)
        undoButton = view.findViewById(R.id.undoLapsButton)
        voice = VoiceLapCounter(requireContext(), viewLifecycleOwner.lifecycleScope, this)

        mapOf(R.id.addLaps2 to 2, R.id.addLaps4 to 4, R.id.addLaps8 to 8, R.id.addLaps10 to 10).forEach { (id, laps) ->
            view.findViewById<Button>(id).apply {
                text = getString(R.string.add_laps, laps)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    SwimTracker.add(laps)
                }
            }
        }
        undoButton.setOnClickListener { SwimTracker.undo() }

        view.findViewById<View>(R.id.voiceCard).setOnClickListener { onVoiceCardClick() }
        voiceToggle.setOnClickListener { toggleVoice() }
        showVoiceState(VoiceState.OFF)

        // While the page is visible: updated at every change of the laps, and every second for the
        // statistics that change with the time (pace)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SwimTracker.changes.collect { showLaps() } }
                launch { voice.level.collect { voiceLevel.level = it } }
                while (true) {
                    showStats()
                    delay(1000)
                }
            }
        }
    }

    // The microphone is used only while the page is on the screen
    override fun onStart() {
        super.onStart()
        if (SwimTracker.voiceCounting) voice.start()
    }

    override fun onStop() {
        voice.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        goalDialog = null
        shownLaps = null
        super.onDestroyView()
    }


    // --------------------------------------------------------- LAPS

    // Laps, ring, distance (red until the goal is reached, then green), undo, goal
    private fun showLaps() {
        val context = context ?: return
        val laps = SwimTracker.laps
        val goal = SwimTracker.goalLaps
        val pool = SwimTracker.poolLengthM

        lapCount.text = laps.toString()
        lapGoalText.text = if (goal != null) getString(R.string.laps_of_goal, goal) else getString(R.string.laps_label)
        ring.show(laps, goal, animate = shownLaps != null && shownLaps != laps)
        shownLaps = laps

        distanceText.text =
            if (goal != null) getString(R.string.run_progress, formatMeters(SwimTracker.meters), formatMeters(SwimMath.meters(goal, pool)))
            else formatMeters(SwimTracker.meters)
        distanceText.setTextColor(context.getColor(when {
            goal == null -> R.color.textPrimary
            laps >= goal -> R.color.successGreen
            else -> R.color.errorRed
        }))
        toGoText.isVisible = goal != null && laps < goal
        if (goal != null) {
            val left = (goal - laps).coerceAtLeast(0)
            toGoText.text = resources.getQuantityString(R.plurals.laps_to_go, left, left)
        }

        val last = SwimTracker.lastAdded
        undoButton.visibility = if (last != null) View.VISIBLE else View.INVISIBLE
        if (last != null) undoButton.text = getString(R.string.undo_laps, last)

        showStats()
        checkGoal()
    }

    // Average pace per 100 m, average time of a lap, pool length
    private fun showStats() {
        if (context == null) return
        val seconds = ActiveSession.elapsedMillis() / 1000
        val none = getString(R.string.no_value)
        stats.show(listOf(
            getString(R.string.stat_avg_pace) to
                    (SwimMath.secondsPer100m(seconds, SwimTracker.meters)?.let { formatPace100(it) } ?: none),
            getString(R.string.stat_lap_time) to
                    (SwimMath.secondsPerLap(seconds, SwimTracker.laps)?.let { formatDuration(it * 1000) } ?: none),
            getString(R.string.stat_pool) to formatMeters(SwimTracker.poolLengthM),
        ))
    }

    // The first time the laps reach the goal: window to keep swimming or finish the swim
    private fun checkGoal() {
        if (!SwimTracker.goalReached || SwimTracker.goalSeen || goalDialog != null || view == null) return
        val goal = SwimTracker.goalLaps ?: return

        val dialog = createModal(requireContext(), R.layout.dialog_goal_reached, viewLifecycleOwner) {
            goalDialog = null
            SwimTracker.goalSeen = true
        }
        dialog.findViewById<TextView>(R.id.goalReachedMessage).text = getString(
            R.string.swim_goal_reached_message,
            formatMeters(SwimTracker.meters),
            resources.getQuantityString(R.plurals.laps_count, goal, goal)
        )
        dialog.findViewById<Button>(R.id.keepRunningButton).apply {
            setText(R.string.keep_swimming)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<Button>(R.id.finishRunButton).apply {
            setText(R.string.finish_swim)
            setOnClickListener {
                dialog.dismiss()
                showStopDialog()
            }
        }
        goalDialog = dialog
        dialog.show()
    }


    // --------------------------------------------------------- VOICE COUNTING

    private fun onVoiceCardClick() {
        when (voice.state) {
            VoiceState.OFF, VoiceState.NO_PERMISSION -> turnVoiceOn()
            VoiceState.UNAVAILABLE -> voice.start()   // try again
            VoiceState.WAITING -> voice.listenNow()   // without saying a word first
            VoiceState.LISTENING -> {}
        }
    }

    private fun toggleVoice() {
        if (voice.state == VoiceState.OFF || voice.state == VoiceState.NO_PERMISSION) {
            turnVoiceOn()
        } else {
            SwimTracker.voiceCounting = false
            voice.stop()
        }
    }

    private fun turnVoiceOn() {
        if (VoiceLapCounter.hasPermission(requireContext())) {
            SwimTracker.voiceCounting = true
            voice.start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStateChanged(state: VoiceState) {
        showVoiceState(state)
    }

    // A number said aloud: added to the laps
    override fun onNumber(laps: Int, heard: String) {
        SwimTracker.add(laps)
        voiceLevel.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        showFeedback(resources.getQuantityString(R.plurals.voice_added, laps, laps, heard), R.color.successGreen)
    }

    override fun onNothing(heard: String?) {
        showFeedback(
            if (heard.isNullOrBlank()) getString(R.string.voice_no_number) else getString(R.string.voice_no_number_in, heard),
            R.color.textSecondary
        )
    }

    override fun onProblem() {
        showFeedback(getString(R.string.voice_problem), R.color.errorRed)
    }

    // The circle of the microphone, the text under VOICE COUNTING and the on / off button
    private fun showVoiceState(state: VoiceState) {
        if (view == null) return
        voiceLevel.state = state
        val on = state != VoiceState.OFF && state != VoiceState.NO_PERMISSION
        voiceToggle.setImageResource(if (on) R.drawable.mic_off_icon else R.drawable.mic_icon)
        voiceToggle.contentDescription = getString(if (on) R.string.voice_turn_off else R.string.voice_turn_on)
        // a message (e.g. "+4 laps") stays until its time ends, except when listening starts
        if (feedbackJob?.isActive == true && state != VoiceState.LISTENING) return
        feedbackJob?.cancel()
        showStatusText(state)
    }

    private fun showStatusText(state: VoiceState) {
        val context = context ?: return
        voiceStatus.text = getString(when (state) {
            VoiceState.OFF -> R.string.voice_off
            VoiceState.NO_PERMISSION -> R.string.voice_no_permission
            VoiceState.UNAVAILABLE -> R.string.voice_unavailable
            VoiceState.WAITING -> R.string.voice_waiting
            VoiceState.LISTENING -> R.string.voice_listening
        })
        voiceStatus.setTextColor(
            if (state == VoiceState.LISTENING) context.themeColor(R.attr.accentTextColor)
            else context.getColor(if (state == VoiceState.WAITING) R.color.textPrimary else R.color.textSecondary)
        )
    }

    private fun showFeedback(message: String, color: Int) {
        val context = context ?: return
        feedbackJob?.cancel()
        voiceStatus.text = message
        voiceStatus.setTextColor(context.getColor(color))
        feedbackJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(FEEDBACK_MS)
            showStatusText(voice.state)
        }
    }

    companion object {
        private const val FEEDBACK_MS = 3500L
    }
}
