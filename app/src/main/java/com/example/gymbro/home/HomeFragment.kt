package com.example.gymbro.home

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
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
import com.example.gymbro.gymBroApp
import com.example.gymbro.session.SessionType
import kotlinx.coroutines.launch

// Home shown when no session is in progress: start training button + last sessions
class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.new_session_Button).setOnClickListener {
            showTrainingTypeDialog()
        }


        // --------------------------------------------------------- LAST SESSIONS

        val adapter = SessionHistoryAdapter()
        val sessionList = view.findViewById<RecyclerView>(R.id.sessionList)
        sessionList.layoutManager = LinearLayoutManager(requireContext())
        sessionList.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.emptySessionsText)

        // The sessions are read from the database in background, and the list is updated
        // automatically every time a session is saved
        val dao = requireContext().gymBroApp.database.trainingSessionDao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getAll().collect { sessions ->
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
}
