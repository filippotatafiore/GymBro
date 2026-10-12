package com.example.gymbro.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.formatDateTime
import com.example.gymbro.formatDuration

// Fills the LAST SESSIONS list of the home page: one row (res/layout/item_session.xml) per session
class SessionHistoryAdapter :
    ListAdapter<TrainingSession, SessionHistoryAdapter.SessionViewHolder>(DIFF) {

    class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.sessionIcon)
        val type: TextView = view.findViewById(R.id.sessionType)
        val date: TextView = view.findViewById(R.id.sessionDate)
        val duration: TextView = view.findViewById(R.id.sessionDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = getItem(position)
        holder.icon.setImageResource(session.type.icon)
        holder.type.setText(session.type.label)
        holder.date.text = formatDateTime(session.startTime)
        holder.duration.text = formatDuration(session.durationMillis)
    }

    companion object {
        // Lets the list animate only the rows that changed
        private val DIFF = object : DiffUtil.ItemCallback<TrainingSession>() {
            override fun areItemsTheSame(old: TrainingSession, new: TrainingSession) = old.id == new.id
            override fun areContentsTheSame(old: TrainingSession, new: TrainingSession) = old == new
        }
    }
}
