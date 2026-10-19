package com.example.gymbro.session

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.formatDuration
import com.example.gymbro.observe
import com.example.gymbro.viewmodel.MainViewModel
import com.google.android.gms.maps.MapView

// Common part of the gym, run and swim sessions: the top strip with title, timer, pause and stop
// buttons (res/layout/session_top_strip.xml, included in the layout of the session). The session
// in progress: MainViewModel, shared with the page (MainActivity).
abstract class SessionFragment(@LayoutRes layout: Int) : Fragment(layout) {

    protected val mainViewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private lateinit var titleText: TextView
    private lateinit var timerText: TextView
    private lateinit var pauseButton: ImageButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleText = view.findViewById(R.id.sessionTitle)
        timerText = view.findViewById(R.id.sessionTimer)
        pauseButton = view.findViewById(R.id.pauseButton)

        pauseButton.setOnClickListener { mainViewModel.pauseOrResume() }
        view.findViewById<ImageButton>(R.id.stopButton).setOnClickListener { showStopDialog() }

        // Paused or not, and the timer, updated every second while the page is visible
        viewLifecycleOwner.observe(mainViewModel.session) { session -> showPauseState(session) }
        viewLifecycleOwner.observe(mainViewModel.timer) { millis -> timerText.text = formatDuration(millis) }
    }

    private fun showPauseState(session: SessionStatus) {
        val type = session.type ?: return
        val paused = session.paused

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
    // Swim sessions: the final statistics of the swim.
    protected fun showStopDialog() {
        val activity = requireActivity() as MainActivity
        // what will be saved: the session as it is when the window opens
        val session = mainViewModel.finishedSession() ?: return
        var summaryMap: MapView? = null
        val dialog = createModal(requireContext(), R.layout.dialog_stop_session, viewLifecycleOwner) {
            summaryMap?.close()
        }

        val gymSummary = dialog.findViewById<TextView>(R.id.stopSummary)
        when (session.type) {
            SessionType.RUN -> {
                gymSummary.isVisible = false
                val runSummary = dialog.findViewById<View>(R.id.runSummary)
                runSummary.isVisible = true
                summaryMap = RunSummaryView.show(runSummary, session.toRunSummary())
            }
            SessionType.SWIM -> {
                gymSummary.isVisible = false
                val swimSummary = dialog.findViewById<View>(R.id.swimSummary)
                swimSummary.isVisible = true
                SwimSummaryView.show(swimSummary, session.toSwimSummary())
            }
            SessionType.GYM -> gymSummary.text = getString(
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
