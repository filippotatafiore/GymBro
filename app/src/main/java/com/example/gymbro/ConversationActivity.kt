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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.ConversationDetails
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.PlanPicker
import com.example.gymbro.social.Avatars
import com.example.gymbro.social.SharedPlanAdapter
import kotlinx.coroutines.launch

// The workout plans shared with one user (Messages): sent on the right, received on the left, the
// newest at the bottom. Tap a plan to see it (PlanActivity) and, if received, to add it to your
// plans. "Share a workout plan": choose one of your plans; the user receives a copy of it as it is
// now. Only between users that follow each other: otherwise the plans already shared stay visible,
// but no other plan can be shared.
class ConversationActivity : ThemedActivity() {

    private var userId = 0L
    private var username = ""
    private var details: ConversationDetails? = null

    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var shareButton: Button
    private lateinit var cannotShareText: View
    private lateinit var adapter: SharedPlanAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }
        userId = intent.getLongExtra(EXTRA_USER_ID, 0L)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()

        // link to the layout
        setContentView(R.layout.activity_conversation)

        findViewById<View>(R.id.conversationBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.conversationUserName).text = username
        findViewById<View>(R.id.conversationUser).setOnClickListener { UserProfileActivity.open(this, userId, username) }
        list = findViewById(R.id.sharedPlanList)
        emptyText = findViewById(R.id.conversationEmptyText)
        loading = findViewById(R.id.conversationLoading)
        shareButton = findViewById(R.id.sharePlanButton)
        cannotShareText = findViewById(R.id.cannotShareText)

        adapter = SharedPlanAdapter { shared -> PlanActivity.openShared(this, shared.id) }
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        shareButton.setOnClickListener { choosePlanToShare() }

        // no bottom strip: the button stays above the navigation bar of the phone
        keepAboveKeyboard(findViewById(R.id.conversationRoot))
    }

    override fun onResume() {
        super.onResume()
        // e.g. back from a plan received and added to your plans: "Added to your plans"
        load()
    }

    private fun load() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                show(ApiClient.getConversation(token, userId))
            } catch (e: ApiException) {
                if (e.code == 404) {
                    Toast.makeText(this@ConversationActivity, R.string.user_not_found, Toast.LENGTH_LONG).show()
                    finish()
                } else if (details == null) {
                    emptyText.text = e.userMessage(this@ConversationActivity)
                    emptyText.isVisible = true
                }
            } finally {
                loading.isVisible = false
            }
        }
    }

    private fun show(details: ConversationDetails) {
        val first = this.details == null
        this.details = details
        username = details.user.username
        findViewById<TextView>(R.id.conversationUserName).text = username
        Avatars.show(findViewById<ImageView>(R.id.conversationUserAvatar), userId, details.user.hasPicture, lifecycleScope)

        val newPlans = details.shares.size != adapter.itemCount
        adapter.submitList(details.shares) {
            // the newest plan at the bottom
            if ((first || newPlans) && details.shares.isNotEmpty()) list.scrollToPosition(details.shares.lastIndex)
        }
        emptyText.text = getString(R.string.conversation_empty, username)
        emptyText.isVisible = details.shares.isEmpty()
        shareButton.isEnabled = details.canShare
        shareButton.alpha = if (details.canShare) 1f else 0.5f
        cannotShareText.isVisible = !details.canShare

        // the plans received are seen now
        if (details.shares.any { !it.fromMe && !it.read }) markRead()
    }

    private fun markRead() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                ApiClient.markConversationRead(token, userId)
            } catch (e: ApiException) {
                // they stay "not seen" until the next time
            }
        }
    }

    // Window with the plans of the user: the one tapped is shared
    private fun choosePlanToShare() {
        val token = UserSession.token ?: return
        shareButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val plans = ApiClient.getPlans(token)
                PlanPicker.show(
                    this@ConversationActivity, this@ConversationActivity,
                    getString(R.string.share_plan_title), getString(R.string.share_plan_message, username),
                    getString(R.string.no_plans_to_share), plans
                ) { plan, picker -> share(plan.id, picker) }
            } catch (e: ApiException) {
                Toast.makeText(this@ConversationActivity, e.userMessage(this@ConversationActivity), Toast.LENGTH_LONG).show()
            } finally {
                shareButton.isEnabled = details?.canShare == true
            }
        }
    }

    private fun share(planId: Long, picker: PlanPicker) {
        val token = UserSession.token ?: return
        picker.busy = true
        lifecycleScope.launch {
            try {
                ApiClient.sharePlan(token, userId, planId)
                picker.dialog.dismiss()
                Toast.makeText(this@ConversationActivity, getString(R.string.plan_shared, username), Toast.LENGTH_SHORT).show()
                load()
            } catch (e: ApiException) {
                picker.showError(e.userMessage(this@ConversationActivity))
            } finally {
                picker.busy = false
            }
        }
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
