package com.example.gymbro.home

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.observe
import com.example.gymbro.showConfirmModal
import com.example.gymbro.showSessionDetails
import com.example.gymbro.viewmodel.HomeViewModel

// Home shown when no session is in progress: start training button + last sessions of the logged
// user (HomeViewModel, shared with the page: MainActivity)
class HomeFragment : Fragment(R.layout.fragment_home) {

    private val viewModel: HomeViewModel by activityViewModels { HomeViewModel.Factory }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.new_session_Button).setOnClickListener {
            showTrainingTypeDialog()
        }
        // calendar sliding from the left (MainActivity / CalendarDrawer)
        view.findViewById<View>(R.id.calendarButton).setOnClickListener {
            (requireActivity() as MainActivity).openCalendar()
        }


        // --------------------------------------------------------- LAST SESSIONS

        val adapter = SessionHistoryAdapter(
            onPublish = { session -> viewModel.publish(session) },
            onDelete = { session -> confirmDelete(session) },
            onClick = { session -> showSessionDetails(requireContext(), viewLifecycleOwner, session) }
        )
        val sessionList = view.findViewById<RecyclerView>(R.id.sessionList)
        sessionList.layoutManager = LinearLayoutManager(requireContext())
        sessionList.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.emptySessionsText)

        // The sessions of this user saved on this phone (also offline), synchronized with the server
        // every time the page is shown: the list is updated every time a session is saved,
        // published, deleted or downloaded
        viewLifecycleOwner.observe(viewModel.state) { state ->
            state ?: return@observe
            adapter.submitList(state.sessions)
            emptyText.isVisible = state.sessions.isEmpty()
            emptyText.setText(if (state.refreshFailed) R.string.error_server_unreachable else R.string.no_sessions_yet)
        }
    }

    // Modal window with the "Gym", "Run" and "Swim" buttons.
    // Its look is in res/layout/dialog_training_type.xml, its animation in res/anim/modal_*.xml
    private fun showTrainingTypeDialog() {
        val dialog = createModal(requireContext(), R.layout.dialog_training_type, viewLifecycleOwner)

        dialog.findViewById<View>(R.id.gymOption).setOnClickListener {
            dialog.dismiss()
            // before the session: the day of the active workout plan to follow
            (requireActivity() as MainActivity).prepareGym()
        }
        dialog.findViewById<View>(R.id.runOption).setOnClickListener {
            dialog.dismiss()
            // before the run starts: permissions and goal (distance or route)
            (requireActivity() as MainActivity).prepareRun()
        }
        dialog.findViewById<View>(R.id.swimOption).setOnClickListener {
            dialog.dismiss()
            // before the swim starts: microphone, pool length and goal
            (requireActivity() as MainActivity).prepareSwim()
        }
        dialog.show()
    }

    // Asks for confirmation, then deletes the session from LAST SESSIONS: at once on this phone, on the
    // server now or (offline) when it is reachable again
    private fun confirmDelete(session: TrainingSession) {
        showConfirmModal(
            requireContext(), viewLifecycleOwner,
            R.string.delete_session_title, R.string.delete_session_message, R.string.delete
        ) {
            viewModel.delete(session)
        }
    }
}
