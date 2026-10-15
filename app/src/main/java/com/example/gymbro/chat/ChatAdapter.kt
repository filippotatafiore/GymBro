package com.example.gymbro.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.data.ChatMessage
import com.example.gymbro.data.ChatRole

// A row of the chat: a message, the coach writing the answer, or the answer that did not arrive
sealed interface ChatItem {
    data class Message(val message: ChatMessage) : ChatItem
    data object Typing : ChatItem
    data class Problem(val text: String) : ChatItem
}

// Identifies the row also when its content changes: the id of the message, or the kind of row
val ChatItem.key: Any
    get() = if (this is ChatItem.Message) message.id else this::class

// Fills the list of the chat page:
// - messages of the user: blue bubbles on the right (res/layout/item_chat_user.xml)
// - answers of the coach: grey bubbles on the left, with the AI model that wrote them (item_chat_coach.xml)
// - three pulsing dots while the coach is writing (item_chat_typing.xml)
// - the error of the last request: tap it to try again (item_chat_problem.xml)
// Long press on a message: copied to the clipboard (onCopy)
class ChatAdapter(
    private val onRetry: () -> Unit,
    private val onCopy: (ChatMessage) -> Unit
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(DIFF) {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chatMessageText)
        val model: TextView? = view.findViewById(R.id.chatModelText)
    }

    class TypingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val dots = listOf<View>(
            view.findViewById(R.id.typingDot1), view.findViewById(R.id.typingDot2), view.findViewById(R.id.typingDot3)
        )
        private var animations = emptyList<ObjectAnimator>()

        // the dots light up one after the other
        fun start() {
            stop()
            animations = dots.mapIndexed { index, dot ->
                ObjectAnimator.ofFloat(dot, View.ALPHA, 0.25f, 1f).apply {
                    duration = 450
                    startDelay = index * 150L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        }

        fun stop() {
            animations.forEach { it.cancel() }
            animations = emptyList()
        }
    }

    class ProblemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chatProblemText)
    }

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is ChatItem.Message -> if (item.message.role == ChatRole.USER) TYPE_USER else TYPE_COACH
        ChatItem.Typing -> TYPE_TYPING
        is ChatItem.Problem -> TYPE_PROBLEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> MessageViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_COACH -> MessageViewHolder(inflater.inflate(R.layout.item_chat_coach, parent, false))
            TYPE_TYPING -> TypingViewHolder(inflater.inflate(R.layout.item_chat_typing, parent, false))
            else -> ProblemViewHolder(inflater.inflate(R.layout.item_chat_problem, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatItem.Message -> {
                holder as MessageViewHolder
                val message = item.message
                if (message.role == ChatRole.USER) {
                    holder.text.text = message.text
                } else {
                    holder.text.text = styledText(message.text, holder.text.paint)
                    // e.g. "openai/gpt-oss-120b" -> "gpt-oss-120b"
                    holder.model?.text = message.model?.substringAfterLast('/')
                    holder.model?.isVisible = !message.model.isNullOrBlank()
                }
                holder.text.setOnLongClickListener {
                    onCopy(message)
                    true
                }
            }
            ChatItem.Typing -> {}
            is ChatItem.Problem -> {
                holder as ProblemViewHolder
                holder.text.text = item.text
                holder.itemView.setOnClickListener { onRetry() }
            }
        }
    }

    // The dots move only while they are on the screen
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        (holder as? TypingViewHolder)?.start()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        (holder as? TypingViewHolder)?.stop()
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_COACH = 1
        private const val TYPE_TYPING = 2
        private const val TYPE_PROBLEM = 3

        // Lets the list animate only the rows that changed
        private val DIFF = object : DiffUtil.ItemCallback<ChatItem>() {
            override fun areItemsTheSame(old: ChatItem, new: ChatItem) = old.key == new.key

            override fun areContentsTheSame(old: ChatItem, new: ChatItem) = old == new
        }
    }
}
