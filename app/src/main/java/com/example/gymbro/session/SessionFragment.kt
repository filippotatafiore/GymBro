package com.example.gymbro.session

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.formatDuration
import com.google.android.gms.maps.MapView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Common part of the gym and run sessions: the top strip with title, timer, pause and stop
// buttons (res/layout/session_top_strip.xml, included in the layout of the session)
abstract class SessionFragment(@LayoutRes layout: Int) : Fragment(layout) {

    private lateinit var titleText: TextView
    private lateinit var timerText: TextView
    private lateinit var pauseButton: ImageButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleText = view.findViewById(R.id.sessionTitle)
        timerText = view.findViewById(R.id.sessionTimer)
        pauseButton = view.findViewById(R.id.pauseButton)

        pauseButton.setOnClickListener { togglePause() }
        view.findViewById<ImageButton>(R.id.stopButton).setOnClickListener { showStopDialog() }
        showPauseState()

        // Update the timer every second while the page is visible (coroutine: the waiting
        // does not block the app)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    timerText.text = formatDuration(ActiveSession.elapsedMillis())
                    delay(1000)
                }
            }
        }
    }

    private fun togglePause() {
        if (ActiveSession.isPaused) ActiveSession.resume() else ActiveSession.pause()
        showPauseState()
        timerText.text = formatDuration(ActiveSession.elapsedMillis())
        (requireActivity() as MainActivity).onSessionChanged()
    }

    private fun showPauseState() {
        val type = ActiveSession.type ?: return
        val paused = ActiveSession.isPaused

        titleText.text = if (paused) {
            getString(R.string.session_paused, getString(type.title))
        } else {
            getString(type.title)
        }
        timerText.alpha = if (paused) 0.4f else 1f
        pauseButton.setImageResource(if (paused) R.drawable.play_icon else R.drawable.pause_icon)
        pauseButton.contentDescription = getString(if (paused) R.string.resume else R.string.pause)
    }

    // Modal window asking to confirm: stop and save the session, and also publish it on the profile.
    // Run sessions: summary of the run (map with the whole path followed + final statistics).
    protected fun showStopDialog() {
        val activity = requireActivity() as MainActivity
        // what will be saved: the session as it is when the window opens
        val session = activity.finishedSession() ?: return
        var summaryMap: MapView? = null
        val dialog = createModal(requireContext(), R.layout.dialog_stop_session, viewLifecycleOwner) {
            summaryMap?.close()
        }

        val gymSummary = dialog.findViewById<TextView>(R.id.stopSummary)
        if (session.type == SessionType.RUN) {
            gymSummary.isVisible = false
            val runSummary = dialog.findViewById<View>(R.id.runSummary)
            runSummary.isVisible = true
            summaryMap = RunSummaryView.show(runSummary, session.toRunSummary())
        } else {
            gymSummary.text = getString(
                R.string.session_summary, getString(session.type.label), formatDuration(session.durationMillis)
            )
        }

        dialog.findViewById<View>(R.id.saveAndPublishButton).setOnClickListener {
            dialog.dismiss()
            activity.saveSession(session, publish = true)
        }
        dialog.findViewById<View>(R.id.saveOnlyButton).setOnClickListener {
            dialog.dismiss()
            activity.saveSession(session, publish = false)
        }
        dialog.findViewById<View>(R.id.cancelButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }
}
