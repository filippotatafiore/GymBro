package com.example.gymbro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.social.ConversationAdapter
import com.example.gymbro.viewmodel.MessagesViewModel

// Messages (paper plane of the social page): not a chat, only workout plans can be shared, and only
// between users that follow each other. The list has those users and the users already shared plans
// with, the last shared first, with a search by username. Tap one to see the plans shared with them
// and to share another one (ConversationActivity). Data and search: MessagesViewModel.
class MessagesActivity : ThemedActivity() {

    private val viewModel: MessagesViewModel by viewModels { MessagesViewModel.Factory }

    private lateinit var search: EditText
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var adapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!viewModel.isLoggedIn) {
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
        search.doAfterTextChanged { viewModel.search(it.toString()) }

        // no bottom strip: the page ends above the keyboard, or above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.messagesRoot))

        observe(viewModel.state) { state ->
            adapter.submitList(state.conversations)
            emptyText.showEmptyText(state.empty)
            if (!state.loading) loading.isVisible = false
        }
    }

    override fun onResume() {
        super.onResume()
        // e.g. back from a conversation: the plans received are seen now
        viewModel.load()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, MessagesActivity::class.java))
        }
    }
}
