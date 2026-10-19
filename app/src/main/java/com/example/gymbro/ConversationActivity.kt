package com.example.gymbro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.ConversationDetails
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.PlanPicker
import com.example.gymbro.social.Avatars
import com.example.gymbro.social.SharedPlanAdapter
import com.example.gymbro.viewmodel.ConversationViewModel

// The workout plans shared with one user (Messages): sent on the right, received on the left, the
// newest at the bottom. Tap a plan to see it (PlanActivity) and, if received, to add it to your
// plans. "Share a workout plan": choose one of your plans; the user receives a copy of it as it is
// now. Only between users that follow each other: otherwise the plans already shared stay visible,
// but no other plan can be shared. Data and sharing: ConversationViewModel.
class ConversationActivity : ThemedActivity() {

    private val userId: Long
        get() = intent.getLongExtra(EXTRA_USER_ID, 0L)

    private val viewModel: ConversationViewModel by viewModels {
        ConversationViewModel.factory(userId, intent.getStringExtra(EXTRA_USERNAME).orEmpty())
    }

    // The plans shown (null: none yet), and the last window to choose the plan to share
    private var shownDetails: ConversationDetails? = null
    private var picker: PlanPicker? = null

    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var shareButton: Button
    private lateinit var cannotShareText: View
    private lateinit var adapter: SharedPlanAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!viewModel.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_conversation)

        findViewById<View>(R.id.conversationBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.conversationUser).setOnClickListener {
            UserProfileActivity.open(this, userId, viewModel.state.value.username)
        }
        list = findViewById(R.id.sharedPlanList)
        emptyText = findViewById(R.id.conversationEmptyText)
        loading = findViewById(R.id.conversationLoading)
        shareButton = findViewById(R.id.sharePlanButton)
        cannotShareText = findViewById(R.id.cannotShareText)

        adapter = SharedPlanAdapter { shared -> PlanActivity.openShared(this, shared.id) }
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        shareButton.setOnClickListener { viewModel.choosePlanToShare() }

        // no bottom strip: the button stays above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.conversationRoot))

        observe(viewModel.state) { state -> show(state) }
        onEvents(viewModel.events) { event -> handle(event) }
    }

    override fun onResume() {
        super.onResume()
        // e.g. back from a plan received and added to your plans: "Added to your plans"
        viewModel.load()
    }

    private fun show(state: ConversationViewModel.State) {
        findViewById<TextView>(R.id.conversationUserName).text = state.username
        val details = state.details
        when {
            details != null -> showDetails(details, state.username)
            state.error != null -> emptyText.showError(state.error.userMessage(this))
        }
        state.shareEnabled?.let { shareButton.isEnabled = it }
        picker?.busy = state.sharing
        if (!state.loading) loading.isVisible = false
    }

    private fun showDetails(details: ConversationDetails, username: String) {
        if (details !== shownDetails) {
            val first = shownDetails == null
            shownDetails = details
            Avatars.show(findViewById<ImageView>(R.id.conversationUserAvatar), userId, details.user.hasPicture, lifecycleScope)

            val newPlans = details.shares.size != adapter.itemCount
            adapter.submitList(details.shares) {
                // the newest plan at the bottom
                if ((first || newPlans) && details.shares.isNotEmpty()) list.scrollToPosition(details.shares.lastIndex)
            }
        }
        emptyText.text = getString(R.string.conversation_empty, username)
        emptyText.isVisible = details.shares.isEmpty()
        shareButton.alpha = if (details.canShare) 1f else 0.5f
        cannotShareText.isVisible = !details.canShare
    }

    private fun handle(event: ConversationViewModel.Event) {
        when (event) {
            ConversationViewModel.Event.UserNotFound -> {
                Toast.makeText(this, R.string.user_not_found, Toast.LENGTH_LONG).show()
                finish()
            }
            is ConversationViewModel.Event.ChoosePlan -> choosePlan(event)
            is ConversationViewModel.Event.Shared -> {
                picker?.dialog?.dismiss()
                Toast.makeText(this, getString(R.string.plan_shared, event.username), Toast.LENGTH_SHORT).show()
            }
            is ConversationViewModel.Event.ShareFailed -> picker?.showError(event.error.userMessage(this))
            is ConversationViewModel.Event.Error ->
                Toast.makeText(this, event.error.userMessage(this), Toast.LENGTH_LONG).show()
        }
    }

    // Window with the plans of the user: the one tapped is shared
    private fun choosePlan(event: ConversationViewModel.Event.ChoosePlan) {
        val username = viewModel.state.value.username
        picker = PlanPicker.show(
            this, this,
            getString(R.string.share_plan_title), getString(R.string.share_plan_message, username),
            getString(R.string.no_plans_to_share), event.plans
        ) { plan, _ -> viewModel.share(plan.id) }
    }

    companion object {
        private const val EXTRA_USER_ID = "user_id"
        private const val EXTRA_USERNAME = "username"

        fun open(context: Context, userId: Long, username: String) {
            context.startActivity(
                Intent(context, ConversationActivity::class.java)
                    .putExtra(EXTRA_USER_ID, userId)
                    .putExtra(EXTRA_USERNAME, username)
            )
        }
    }
}
