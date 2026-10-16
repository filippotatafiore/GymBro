package com.example.gymbro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Conversation
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.social.ConversationAdapter
import kotlinx.coroutines.launch

// Messages (paper plane of the social page): not a chat, only workout plans can be shared, and only
// between users that follow each other. The list has those users and the users already shared plans
// with, the last shared first, with a search by username. Tap one to see the plans shared with them
// and to share another one (ConversationActivity).
class MessagesActivity : ThemedActivity() {

    private var conversations = emptyList<Conversation>()

    private lateinit var search: EditText
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var adapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_messages)

        findViewById<View>(R.id.messagesBackButton).setOnClickListener { finish() }
        search = findViewById(R.id.messagesSearchInput)
        emptyText = findViewById(R.id.messagesEmptyText)
        loading = findViewById(R.id.messagesLoading)

        adapter = ConversationAdapter(lifecycleScope) { conversation ->
            ConversationActivity.open(this, conversation.user.id, conversation.user.username)
        }
        val list = findViewById<RecyclerView>(R.id.conversationList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        search.doAfterTextChanged { showConversations() }

        // no bottom strip: the page ends above the keyboard, or above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.messagesRoot))
    }

    override fun onResume() {
        super.onResume()
        // e.g. back from a conversation: the plans received are seen now
        load()
    }

    private fun load() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                conversations = ApiClient.getConversations(token)
                showConversations()
            } catch (e: ApiException) {
                if (conversations.isEmpty()) {
                    emptyText.text = e.userMessage(this@MessagesActivity)
                    emptyText.isVisible = true
                }
            } finally {
                loading.isVisible = false
            }
        }
    }

    // The users matching the search (upper/lower case ignored)
    private fun showConversations() {
        val text = search.text.toString().trim().removePrefix("@")
        val found = if (text.isEmpty()) conversations
            else conversations.filter { it.user.username.contains(text, ignoreCase = true) }
        adapter.submitList(found)
        emptyText.setText(if (text.isEmpty()) R.string.messages_empty else R.string.no_users_found)
        emptyText.isVisible = found.isEmpty()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, MessagesActivity::class.java))
        }
    }
}
