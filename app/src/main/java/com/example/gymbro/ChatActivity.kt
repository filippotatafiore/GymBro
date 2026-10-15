package com.example.gymbro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.chat.ChatAdapter
import com.example.gymbro.chat.ChatFormatting
import com.example.gymbro.chat.ChatItem
import com.example.gymbro.chat.CoachChat
import com.example.gymbro.chat.key
import com.example.gymbro.data.ChatMessage
import com.example.gymbro.data.ChatRole
import com.example.gymbro.network.UserSession
import com.example.gymbro.plan.Plan
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// Chat page: conversation with the AI coach (see chat/CoachChat.kt). Two kinds of chat:
// - general chat (second icon of the bottom strip): questions about exercises, technique,
//   training plans and running
// - chat about a workout plan (button "Ask the coach" of the page of a plan, see open): the coach
//   sees the plan, also after the changes made by hand, and can change it. Without bottom strip.
// The conversations are saved on the phone (one for each user and plan), the answers come from the server.
class ChatActivity : ThemedActivity() {

    private var bottomNav: BottomNavBar? = null
    private lateinit var conversation: CoachChat.Conversation
    private lateinit var chatList: RecyclerView
    private lateinit var welcome: View
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var adapter: ChatAdapter

    // Messages of the conversation now shown (to know if the user has started the chat)
    private var messageCount = 0
    // The last row of the list now shown (see ChatItem.key)
    private var lastRowKey: Any? = null

    private val isPlanChat: Boolean
        get() = conversation.planId != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        val userId = UserSession.userId
        if (userId == null) {
            WelcomeActivity.open(this)
            finish()
            return
        }
        val planId = intent.getLongExtra(EXTRA_PLAN_ID, 0L).takeIf { it > 0 }
        conversation = CoachChat.Conversation(userId, planId)

        // link to the layout
        setContentView(R.layout.activity_chat)

        chatList = findViewById(R.id.chatList)
        welcome = findViewById(R.id.chatWelcome)
        input = findViewById(R.id.chatInput)
        sendButton = findViewById(R.id.sendButton)

        adapter = ChatAdapter(
            onRetry = { CoachChat.retry(this, conversation) },
            onCopy = { message -> copy(message) },
            // chat about a plan: back to the page of the plan, which shows the changes
            onPlanUpdatedClick = { finish() }
        )
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter
        // the list gets shorter when the keyboard opens: it scrolls up as much, so that the last
        // messages stay visible above the text field
        chatList.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) chatList.post { chatList.scrollBy(0, oldBottom - bottom) }
        }

        if (isPlanChat) showPlanChatTexts()
        followKeyboard()


        // --------------------------------------------------------- SEND

        input.doAfterTextChanged { updateSendButton() }
        sendButton.setOnClickListener {
            send(input.text.toString())
            input.text.clear()
        }
        // example questions of the welcome
        for (id in listOf(R.id.suggestion1, R.id.suggestion2, R.id.suggestion3, R.id.suggestion4)) {
            findViewById<TextView>(id).setOnClickListener { view -> send((view as TextView).text.toString()) }
        }

        // new chat: after a confirmation, the conversation is deleted
        findViewById<View>(R.id.newChatButton).setOnClickListener {
            if (messageCount == 0 && !CoachChat.isWaiting(conversation)) return@setOnClickListener
            showConfirmModal(this, this, R.string.new_chat_title, R.string.new_chat_message, R.string.new_chat) {
                CoachChat.clear(this, conversation)
            }
        }


        // --------------------------------------------------------- CONVERSATION

        // The messages of this conversation are read from the database in background; the list is
        // updated every time a message is saved or the coach starts/stops writing
        val dao = gymBroApp.database.chatMessageDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(dao.getAll(userId, planId), CoachChat.status(conversation)) { list, status -> list to status }
                    .collect { (list, status) -> showConversation(list, status) }
            }
        }
        // the last message has no answer (e.g. the app was closed while the coach was writing):
        // it can be asked again
        lifecycleScope.launch {
            if (dao.getLast(userId, planId, 1).firstOrNull()?.role == ChatRole.USER) {
                CoachChat.answerMissing(conversation, getString(R.string.coach_no_answer))
            }
        }


        // --------------------------------------------------------- NAVBAR

        if (isPlanChat) {
            findViewById<View>(R.id.bottomNav).isVisible = false
        } else {
            bottomNav = BottomNavBar(this, BottomNavBar.Page.CHAT)
        }
    }

    override fun onResume() {
        super.onResume()
        bottomNav?.refresh()
    }

    override fun onPause() {
        super.onPause()
        bottomNav?.stopGlow()
    }

    // Chat about a plan: back button, name of the plan, welcome and example questions about the plan
    private fun showPlanChatTexts() {
        findViewById<View>(R.id.chatBackButton).apply {
            isVisible = true
            setOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.chatTitle).text =
            intent.getStringExtra(EXTRA_PLAN_NAME) ?: getString(R.string.workout_plan)
        findViewById<TextView>(R.id.chatWelcomeTitle).setText(R.string.plan_chat_welcome_title)
        findViewById<TextView>(R.id.chatWelcomeMessage).setText(R.string.plan_chat_welcome_message)
        findViewById<TextView>(R.id.suggestion1).setText(R.string.plan_suggestion_1)
        findViewById<TextView>(R.id.suggestion2).setText(R.string.plan_suggestion_2)
        findViewById<TextView>(R.id.suggestion3).setText(R.string.plan_suggestion_3)
        findViewById<TextView>(R.id.suggestion4).setText(R.string.plan_suggestion_4)
        input.setHint(R.string.plan_chat_input_hint)
    }

    // While the keyboard is open, the bottom strip is hidden and the page ends above the keyboard
    // (the page is drawn under the system bars, see ThemedActivity). Chat about a plan
    // (no bottom strip): the text field stays above the navigation bar of the phone.
    private fun followKeyboard() {
        val navBar = findViewById<View>(R.id.bottomNav)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot)) { root, insets ->
            val keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
            navBar.isVisible = !keyboardOpen && !isPlanChat
            val bottom = when {
                keyboardOpen -> insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                isPlanChat -> insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                else -> 0
            }
            root.updatePadding(bottom = bottom)
            insets
        }
    }

    private fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || CoachChat.isWaiting(conversation)) return
        CoachChat.send(this, conversation, message)
    }

    private fun updateSendButton() {
        sendButton.isEnabled = input.text.isNotBlank() && !CoachChat.isWaiting(conversation)
    }

    // Messages + the last row: the coach writing, or the answer that did not arrive (tap to retry)
    private fun showConversation(messages: List<ChatMessage>, status: CoachChat.Status) {
        val items = messages.map<ChatMessage, ChatItem> { ChatItem.Message(it) }.toMutableList()
        when (status) {
            CoachChat.Status.Waiting -> items.add(ChatItem.Typing)
            is CoachChat.Status.Failed -> items.add(ChatItem.Problem(status.message))
            CoachChat.Status.Idle -> {}
        }

        welcome.isVisible = items.isEmpty()
        chatList.isVisible = items.isNotEmpty()
        messageCount = messages.size

        // a new last row (a message, the coach writing, an error): the list scrolls to it
        val lastRowChanged = items.lastOrNull()?.key != lastRowKey
        lastRowKey = items.lastOrNull()?.key
        adapter.submitList(items) {
            if (lastRowChanged && items.isNotEmpty()) scrollToLastRow(items)
        }
        updateSendButton()
    }

    // Shows the beginning of the last row (if the rows after it are short, the end of the list).
    // A new answer: from its question, so that a long answer is read from its beginning.
    private fun scrollToLastRow(items: List<ChatItem>) {
        var position = items.lastIndex
        val last = items[position]
        val previous = items.getOrNull(position - 1)
        if (last is ChatItem.Message && last.message.role == ChatRole.ASSISTANT &&
            previous is ChatItem.Message && previous.message.role == ChatRole.USER
        ) {
            position--
        }
        (chatList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
    }

    // Long press on a message: copied to the clipboard (Android shows a confirmation)
    private fun copy(message: ChatMessage) {
        val text = if (message.role == ChatRole.ASSISTANT) ChatFormatting.parse(message.text).text else message.text
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.chat), text))
    }

    companion object {
        private const val EXTRA_PLAN_ID = "plan_id"
        private const val EXTRA_PLAN_NAME = "plan_name"

        // Opens the chat about a workout plan
        fun open(context: Context, plan: Plan) {
            context.startActivity(
                Intent(context, ChatActivity::class.java)
                    .putExtra(EXTRA_PLAN_ID, plan.id)
                    .putExtra(EXTRA_PLAN_NAME, plan.name)
            )
        }
    }
}
