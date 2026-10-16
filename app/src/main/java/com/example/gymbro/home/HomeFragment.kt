package com.example.gymbro.home

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.MainActivity
import com.example.gymbro.R
import com.example.gymbro.createModal
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.gymBroApp
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.userMessage
import com.example.gymbro.repository.SessionRepository
import com.example.gymbro.session.publishSession
import com.example.gymbro.showConfirmModal
import com.example.gymbro.showSessionDetails
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// Home shown when no session is in progress: start training button + last sessions of the logged user
class HomeFragment : Fragment(R.layout.fragment_home) {

    // Sessions being published right now (clientId: avoids publishing twice with a double tap)
    private val publishing = mutableSetOf<String>()

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
            onPublish = { session -> publish(session) },
            onDelete = { session -> confirmDelete(session) },
            onClick = { session -> showSessionDetails(requireContext(), viewLifecycleOwner, session) }
        )
        val sessionList = view.findViewById<RecyclerView>(R.id.sessionList)
        sessionList.layoutManager = LinearLayoutManager(requireContext())
        sessionList.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.emptySessionsText)

        // The sessions of this user saved on this phone (also offline), synchronized with the server
        // by MainActivity every time the page is shown: the list is updated every time a session is
        // saved, published, deleted or downloaded
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(SessionRepository.sessions(), SessionRepository.refreshFailed) { sessions, failed -> sessions to failed }
                    .collect { (sessions, failed) ->
                        adapter.submitList(sessions)
                        emptyText.isVisible = sessions.isEmpty()
                        emptyText.setText(if (failed) R.string.error_server_unreachable else R.string.no_sessions_yet)
                    }
            }
        }
    }

    // Modal window with the "Gym" and "Run" buttons.
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
        dialog.show()
    }

    // Publishes the session on the profile (its button becomes a check mark). Only online: a session
    // finished offline is sent to the server first.
    private fun publish(session: TrainingSession) {
        if (!publishing.add(session.clientId)) return
        val context = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                publishSession(context, session)
                Toast.makeText(context, R.string.session_published, Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Toast.makeText(context, e.userMessage(context), Toast.LENGTH_LONG).show()
            } finally {
                publishing.remove(session.clientId)
            }
        }
    }

    // Asks for confirmation, then deletes the session from LAST SESSIONS: at once on this phone, on the
    // server now or (offline) when it is reachable again
    private fun confirmDelete(session: TrainingSession) {
        showConfirmModal(
            requireContext(), viewLifecycleOwner,
            R.string.delete_session_title, R.string.delete_session_message, R.string.delete
        ) {
            val app = requireContext().gymBroApp
            app.applicationScope.launch {
                try {
                    SessionRepository.delete(session)
                } catch (e: ApiException) {
                    Toast.makeText(app, e.userMessage(app), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
