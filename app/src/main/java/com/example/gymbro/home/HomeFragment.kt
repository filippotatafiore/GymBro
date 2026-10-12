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
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.session.SessionType
import com.example.gymbro.session.publishSession
import com.example.gymbro.showConfirmModal
import kotlinx.coroutines.launch

// Home shown when no session is in progress: start training button + last sessions of the logged user
class HomeFragment : Fragment(R.layout.fragment_home) {

    // Sessions being published right now (avoids publishing twice with a double tap)
    private val publishing = mutableSetOf<Long>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.new_session_Button).setOnClickListener {
            showTrainingTypeDialog()
        }


        // --------------------------------------------------------- LAST SESSIONS

        val userId = UserSession.userId ?: return

        val adapter = SessionHistoryAdapter(
            onPublish = { session -> publish(session) },
            onDelete = { session -> confirmDelete(session) }
        )
        val sessionList = view.findViewById<RecyclerView>(R.id.sessionList)
        sessionList.layoutManager = LinearLayoutManager(requireContext())
        sessionList.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.emptySessionsText)

        // The sessions of this user are read from the database in background, and the list is
        // updated automatically every time a session is saved, published or deleted
        val dao = requireContext().gymBroApp.database.trainingSessionDao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getAll(userId).collect { sessions ->
                    adapter.submitList(sessions)
                    emptyText.isVisible = sessions.isEmpty()
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
            (requireActivity() as MainActivity).startSession(SessionType.GYM)
        }
        dialog.findViewById<View>(R.id.runOption).setOnClickListener {
            dialog.dismiss()
            (requireActivity() as MainActivity).startSession(SessionType.RUN)
        }
        dialog.show()
    }

    // Publishes the session on the profile (its button becomes a check mark)
    private fun publish(session: TrainingSession) {
        if (!publishing.add(session.id)) return
        val context = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                publishSession(context, session)
                Toast.makeText(context, R.string.session_published, Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Toast.makeText(context, e.userMessage(context), Toast.LENGTH_LONG).show()
            } finally {
                publishing.remove(session.id)
            }
        }
    }

    // Asks for confirmation, then deletes the session from LAST SESSIONS
    private fun confirmDelete(session: TrainingSession) {
        showConfirmModal(
            requireContext(), viewLifecycleOwner,
            R.string.delete_session_title, R.string.delete_session_message, R.string.delete
        ) {
            val app = requireContext().gymBroApp
            app.applicationScope.launch {
                app.database.trainingSessionDao().delete(session.id)
            }
        }
    }
}
