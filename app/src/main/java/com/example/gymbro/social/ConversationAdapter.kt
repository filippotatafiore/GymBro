package com.example.gymbro.social

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatTimeAgo
import com.example.gymbro.network.Conversation
import com.example.gymbro.themeColor
import kotlinx.coroutines.CoroutineScope

// Fills the list of Messages: one row (res/layout/item_conversation.xml) per user, e.g.
// "Shared “Push Pull Legs” with you" · "2 hours ago", and how many plans received were not seen
// yet (in bold, in the favorite color). Tap a row to see the plans shared with the user.
class ConversationAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(DIFF) {

    class ConversationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.conversationAvatar)
        val name: TextView = view.findViewById(R.id.conversationName)
        val last: TextView = view.findViewById(R.id.conversationLast)
        val time: TextView = view.findViewById(R.id.conversationTime)
        val unread: TextView = view.findViewById(R.id.conversationUnread)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val conversation = getItem(position)
        val context = holder.itemView.context
        val last = conversation.last

        Avatars.show(holder.avatar, conversation.user.id, conversation.user.hasPicture, scope)
        holder.name.text = conversation.user.username
        holder.last.text = when {
            last == null -> context.getString(R.string.no_plans_shared)
            last.fromMe -> context.getString(R.string.you_shared_plan, last.planName)
            else -> context.getString(R.string.shared_plan_with_you, last.planName)
        }
        // plans not seen yet: the last line stands out
        val unread = conversation.unread > 0
        holder.last.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
        holder.last.setTextColor(
            if (unread) context.themeColor(R.attr.accentTextColor) else context.getColor(R.color.textSecondary)
        )
        holder.time.text = last?.let { formatTimeAgo(it.createdAt) }
        holder.time.isVisible = last != null
        holder.unread.text = conversation.unread.toString()
        holder.unread.isVisible = unread
        holder.itemView.setOnClickListener { onClick(conversation) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Conversation>() {
            override fun areItemsTheSame(old: Conversation, new: Conversation) = old.user.id == new.user.id
            override fun areContentsTheSame(old: Conversation, new: Conversation) = old == new
        }
    }
}
