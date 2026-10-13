package com.example.gymbro.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.data.TrainingSession
import com.example.gymbro.displayTitle
import com.example.gymbro.formatDateTime
import com.example.gymbro.formatDuration

// Fills a list of finished sessions: one row (res/layout/item_session.xml) per session.
// - home page: with the "publish" and "delete" buttons (onPublish, onDelete)
// - window to choose a session to publish: rows can be tapped (onClick), no buttons
class SessionHistoryAdapter(
    private val onPublish: ((TrainingSession) -> Unit)? = null,
    private val onDelete: ((TrainingSession) -> Unit)? = null,
    private val onClick: ((TrainingSession) -> Unit)? = null
) : ListAdapter<TrainingSession, SessionHistoryAdapter.SessionViewHolder>(DIFF) {

    class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.sessionIcon)
        val type: TextView = view.findViewById(R.id.sessionType)
        val date: TextView = view.findViewById(R.id.sessionDate)
        val duration: TextView = view.findViewById(R.id.sessionDuration)
        val publishButton: ImageButton = view.findViewById(R.id.publishSessionButton)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteSessionButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = getItem(position)
        val context = holder.itemView.context

        holder.icon.setImageResource(session.type.icon)
        holder.type.text = session.displayTitle(context)
        holder.date.text = formatDateTime(session.startTime)
        holder.duration.text = formatDuration(session.durationMillis)

        // publish button: a check mark (not clickable) once the session is published
        val published = session.publishedPostId != null
        holder.publishButton.isVisible = onPublish != null
        holder.publishButton.isEnabled = !published
        holder.publishButton.setImageResource(if (published) R.drawable.check_icon else R.drawable.publish_icon)
        holder.publishButton.contentDescription =
            context.getString(if (published) R.string.published else R.string.publish_on_profile)
        holder.publishButton.setOnClickListener { onPublish?.invoke(session) }

        holder.deleteButton.isVisible = onDelete != null
        holder.deleteButton.setOnClickListener { onDelete?.invoke(session) }

        holder.itemView.setOnClickListener { onClick?.invoke(session) }
        holder.itemView.isClickable = onClick != null  // after setOnClickListener, that makes it clickable
    }

    companion object {
        // Lets the list animate only the rows that changed
        private val DIFF = object : DiffUtil.ItemCallback<TrainingSession>() {
            override fun areItemsTheSame(old: TrainingSession, new: TrainingSession) = old.id == new.id
            override fun areContentsTheSame(old: TrainingSession, new: TrainingSession) = old == new
        }
    }
}
