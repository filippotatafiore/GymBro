package com.example.gymbro

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.home.SessionHistoryAdapter
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.profile.ManualExercises
import com.example.gymbro.profile.PersonalBestAdapter
import com.example.gymbro.profile.PersonalBests
import com.example.gymbro.profile.PostAdapter
import com.example.gymbro.profile.ProfilePictures
import com.example.gymbro.profile.displayTitle
import com.example.gymbro.profile.displayValue
import com.example.gymbro.profile.icon
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.bindExerciseRow
import com.example.gymbro.session.publishSession
import kotlinx.coroutines.launch

import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import java.util.Stack

class ProfileActivity : ComponentActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var menuContainer: FrameLayout
    private val menuHistory = Stack<Int>() // Stack to store previous menus

    private lateinit var bottomNav: BottomNavBar
    private lateinit var profilePicture: ImageView
    private lateinit var usernameText: TextView
    private lateinit var emailText: TextView
    private lateinit var emptyResultsText: TextView
    private lateinit var emptyPersonalBestsText: TextView
    private lateinit var postList: RecyclerView

    private val postAdapter = PostAdapter(
        onClick = { post -> showPostDetails(post) },
        onDelete = { post -> confirmDeletePost(post) }
    )
    private val personalBestAdapter = PersonalBestAdapter(onClick = { post -> showPostDetails(post) })

    // Opens the system photo picker (no permission needed); the lambda receives the chosen photo
    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) changePicture(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!UserSession.isLoggedIn) {
            WelcomeActivity.open(this)
            finish()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_profile)


        // --------------------------------------------------------- SLIDING MENU

        // Initialize DrawerLayout and NavigationView
        drawerLayout = findViewById(R.id.drawer_layout)
        menuButton = findViewById(R.id.menuButton)
        menuContainer = findViewById(R.id.menu_container)

        // Open the menu when the button is clicked
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

        // Function to load profile menu and store previous state
        fun loadProfileMenu(layoutId: Int, storePrevious: Boolean = true) {
            if (storePrevious) menuHistory.push(layoutId) // Store previous menu

            menuContainer.removeAllViews()
            layoutInflater.inflate(layoutId, menuContainer, true)


            // Handle the settings button (if present in the current layout)
            menuContainer.findViewById<Button>(R.id.settingsButton)?.setOnClickListener {
                loadProfileMenu(R.layout.settings) // Open settings menu
            }

            // Handle the logout button (if present in the current layout)
            menuContainer.findViewById<Button>(R.id.logoutButton)?.setOnClickListener {
                logout()
            }

            // Handle the back button (if present in the current layout)
            menuContainer.findViewById<ImageButton>(R.id.backmenuButton)?.setOnClickListener {
                if (menuHistory.isNotEmpty()) {
                    val previousMenu = menuHistory.pop() // Go back
                    loadProfileMenu(previousMenu, storePrevious = false)
                } else {
                    loadProfileMenu(R.layout.profile_menu) // Default fallback
                }
            }
        }

        // Load the initial profile menu
        loadProfileMenu(R.layout.profile_menu, storePrevious = false)

        // Listen for drawer close event
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {}

            override fun onDrawerClosed(drawerView: View) {
                menuHistory.clear() // Clear history when menu closes
                loadProfileMenu(R.layout.profile_menu, storePrevious = false)
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })


        // --------------------------------------------------------- PROFILE

        profilePicture = findViewById(R.id.profilePicture)
        usernameText = findViewById(R.id.profileUsername)
        emailText = findViewById(R.id.profileEmail)
        emptyResultsText = findViewById(R.id.emptyResultsText)
        emptyPersonalBestsText = findViewById(R.id.emptyPersonalBestsText)

        val personalBestList = findViewById<RecyclerView>(R.id.personalBestList)
        personalBestList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        personalBestList.adapter = personalBestAdapter

        postList = findViewById(R.id.postList)
        postList.layoutManager = LinearLayoutManager(this)
        postList.adapter = postAdapter

        // Tap the profile picture to change it
        profilePicture.setOnClickListener {
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.publishResultButton).setOnClickListener { showPublishChoice() }


        // --------------------------------------------------------- NAVBAR

        bottomNav = BottomNavBar(this, BottomNavBar.Page.PROFILE)
    }

    override fun onResume() {
        super.onResume()
        bottomNav.refresh()
        UserSession.user?.let { showUser(it) }
        refreshProfile()
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }


    // --------------------------------------------------------- PROFILE

    private fun showUser(user: User) {
        usernameText.text = user.username
        emailText.text = user.email
    }

    // Downloads profile data, picture and posts from the server (in background)
    private fun refreshProfile() {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                val user = ApiClient.getMe(token)
                UserSession.updateUser(user)
                showUser(user)

                val picture = if (user.hasPicture) ApiClient.getPicture(user.id) else null
                if (picture != null) {
                    profilePicture.setImageBitmap(picture)
                } else {
                    profilePicture.setImageResource(R.drawable.default_avatar)
                }

                showPosts(user.id)
            } catch (e: ApiException) {
                Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                // the login is no longer valid (e.g. logged out from the server)
                if (e.code == 401) logout()
            }
        }
    }

    // Shows the posts of the user and the personal bests chosen among them.
    // scrollToTop: shows the top of the list, where a new post appears.
    private suspend fun showPosts(userId: Long, scrollToTop: Boolean = false) {
        val posts = ApiClient.getPosts(userId)
        postAdapter.submitList(posts) {
            if (scrollToTop) postList.scrollToPosition(0)
        }
        emptyResultsText.isVisible = posts.isEmpty()

        val bests = PersonalBests.from(posts)
        personalBestAdapter.submitList(bests)
        emptyPersonalBestsText.isVisible = bests.isEmpty()
    }

    // Reloads posts and personal bests (after publishing or deleting a post)
    private fun reloadPosts(scrollToTop: Boolean = false) {
        val userId = UserSession.userId ?: return
        lifecycleScope.launch {
            try {
                showPosts(userId, scrollToTop)
            } catch (e: ApiException) {
                Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Back to the welcome page. The session in progress (if any) is kept for when this user logs in again.
    private fun logout() {
        val token = UserSession.token
        UserSession.clear()

        // Invalidate the token on the server too (if the server is not reachable there is nothing to do).
        // applicationScope: this page is closed right away.
        if (token != null) {
            gymBroApp.applicationScope.launch {
                try {
                    ApiClient.logout(token)
                } catch (_: ApiException) {
                }
            }
        }
        WelcomeActivity.open(this)
    }

    // The new picture is uploaded to the server right away
    private fun changePicture(uri: Uri) {
        val token = UserSession.token ?: return
        lifecycleScope.launch {
            try {
                val jpeg = ProfilePictures.prepare(this@ProfileActivity, uri)
                ApiClient.uploadPicture(token, jpeg)
                profilePicture.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            } catch (e: ApiException) {
                Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProfileActivity, R.string.error_picture, Toast.LENGTH_LONG).show()
            }
        }
    }


    // --------------------------------------------------------- PUBLISH

    // Modal window of the "Publish" button: a finished session or a result added manually
    private fun showPublishChoice() {
        val dialog = createModal(this, R.layout.dialog_publish_choice, this)
        dialog.findViewById<View>(R.id.publishSessionOption).setOnClickListener {
            dialog.dismiss()
            showSessionPicker()
        }
        dialog.findViewById<View>(R.id.publishManualOption).setOnClickListener {
            dialog.dismiss()
            showManualPublishDialog()
        }
        dialog.show()
    }

    // List of the finished sessions not published yet: tap one to publish it.
    // The session in progress is not in the list: it can be published only once it is finished.
    private fun showSessionPicker() {
        val userId = UserSession.userId ?: return
        val dialog = createModal(this, R.layout.dialog_publish_session, this)
        val list = dialog.findViewById<RecyclerView>(R.id.sessionPickerList)
        val noSessionsText = dialog.findViewById<TextView>(R.id.noSessionsText)
        dialog.findViewById<View>(R.id.activeSessionHint).isVisible = ActiveSession.isActive
        dialog.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }

        var publishing = false
        val adapter = SessionHistoryAdapter(onClick = { session ->
            if (!publishing) {
                publishing = true
                lifecycleScope.launch {
                    try {
                        publishSession(this@ProfileActivity, session)
                        dialog.dismiss()
                        Toast.makeText(this@ProfileActivity, R.string.session_published, Toast.LENGTH_SHORT).show()
                        reloadPosts(scrollToTop = true)
                    } catch (e: ApiException) {
                        Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                    } finally {
                        publishing = false
                    }
                }
            }
        })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // The sessions are read from the local database in background
        lifecycleScope.launch {
            val sessions = gymBroApp.database.trainingSessionDao().getUnpublished(userId)
            adapter.submitList(sessions)
            noSessionsText.isVisible = sessions.isEmpty()
        }
        dialog.show()
    }

    // Result added manually: exercise from a predefined list, measured by time or weight,
    // higher or lower result is better
    private fun showManualPublishDialog() {
        val dialog = createModal(this, R.layout.dialog_publish_manual, this)
        val exerciseSpinner = dialog.findViewById<Spinner>(R.id.exerciseSpinner)
        val metricGroup = dialog.findViewById<RadioGroup>(R.id.metricGroup)
        val betterGroup = dialog.findViewById<RadioGroup>(R.id.betterGroup)
        val weightInput = dialog.findViewById<EditText>(R.id.weightInput)
        val timeInputs = dialog.findViewById<View>(R.id.timeInputs)
        val minutesInput = dialog.findViewById<EditText>(R.id.minutesInput)
        val secondsInput = dialog.findViewById<EditText>(R.id.secondsInput)
        val notesInput = dialog.findViewById<EditText>(R.id.notesInput)
        val error = dialog.findViewById<TextView>(R.id.publishError)
        val confirm = dialog.findViewById<Button>(R.id.publishConfirmButton)

        // The result is a weight or a time, depending on how the exercise is measured
        metricGroup.setOnCheckedChangeListener { _, checkedId ->
            weightInput.isVisible = checkedId == R.id.metricWeight
            timeInputs.isVisible = checkedId == R.id.metricTime
        }

        // Choosing an exercise selects its usual way of evaluating it (it can then be changed)
        exerciseSpinner.adapter = ArrayAdapter(this, R.layout.item_spinner, ManualExercises.all.map { it.name })
        exerciseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val exercise = ManualExercises.all[position]
                metricGroup.check(if (exercise.metric == Metric.TIME) R.id.metricTime else R.id.metricWeight)
                betterGroup.check(if (exercise.better == Better.LOWER) R.id.betterLower else R.id.betterHigher)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        confirm.setOnClickListener {
            val metric = if (metricGroup.checkedRadioButtonId == R.id.metricTime) Metric.TIME else Metric.WEIGHT
            val better = if (betterGroup.checkedRadioButtonId == R.id.betterLower) Better.LOWER else Better.HIGHER

            // kg or seconds
            val amount = when (metric) {
                Metric.WEIGHT -> weightInput.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
                    ?: return@setOnClickListener error.showError(getString(R.string.error_invalid_weight))
                Metric.TIME -> {
                    val minutes = minutesInput.text.toString().toIntOrNull() ?: 0
                    val seconds = secondsInput.text.toString().toIntOrNull() ?: 0
                    if (seconds !in 0..59 || minutes * 60 + seconds <= 0) {
                        return@setOnClickListener error.showError(getString(R.string.error_invalid_time))
                    }
                    (minutes * 60 + seconds).toDouble()
                }
            }
            val exercise = ManualExercises.all[exerciseSpinner.selectedItemPosition]
            val token = UserSession.token ?: return@setOnClickListener

            lifecycleScope.launch {
                confirm.isEnabled = false
                try {
                    ApiClient.publishManual(token, exercise.name, metric, better, amount, notesInput.text.toString().trim())
                    dialog.dismiss()
                    reloadPosts(scrollToTop = true)
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    confirm.isEnabled = true
                }
            }
        }
        dialog.show()
    }


    // --------------------------------------------------------- POSTS

    // Asks for confirmation, then deletes the post (posts and personal bests are reloaded)
    private fun confirmDeletePost(post: Post) {
        showConfirmModal(
            this, this,
            R.string.delete_post_title, R.string.delete_post_message, R.string.delete
        ) {
            val token = UserSession.token ?: return@showConfirmModal
            lifecycleScope.launch {
                try {
                    ApiClient.deletePost(token, post.id)
                    // if the post was a session of LAST SESSIONS, that session can be published again
                    gymBroApp.database.trainingSessionDao().clearPublished(post.id)
                    reloadPosts()
                } catch (e: ApiException) {
                    Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Modal window with all the information of a post
    private fun showPostDetails(post: Post) {
        val dialog = createModal(this, R.layout.dialog_post_details, this)
        dialog.findViewById<ImageView>(R.id.detailsIcon).setImageResource(post.icon())
        dialog.findViewById<TextView>(R.id.detailsTitle).text = post.displayTitle(this)
        dialog.findViewById<TextView>(R.id.detailsSubtitle).text =
            getString(R.string.published_on, formatDate(post.createdAt))
        dialog.findViewById<View>(R.id.closeButton).setOnClickListener { dialog.dismiss() }

        val content = dialog.findViewById<LinearLayout>(R.id.detailsContent)
        val sessionDate = post.sessionStart?.let { formatDateTime(it.toEpochMilli()) }

        when (post.kind) {
            PostKind.MANUAL -> {
                addDetailRow(content, R.string.detail_result, post.displayValue())
                addDetailRow(content, R.string.detail_measured_by,
                    getString(if (post.metric == Metric.TIME) R.string.metric_time else R.string.metric_weight))
                addDetailRow(content, R.string.detail_better,
                    getString(if (post.better == Better.LOWER) R.string.better_lower else R.string.better_higher))
                if (!post.notes.isNullOrBlank()) addDetailRow(content, R.string.detail_notes, post.notes)
            }

            PostKind.RUN -> {
                val km = post.distanceKm ?: 0.0
                val seconds = post.durationSeconds ?: 0L
                addDetailRow(content, R.string.detail_distance, formatDistance(km))
                addDetailRow(content, R.string.detail_time, post.displayValue())
                if (km > 0) addDetailRow(content, R.string.detail_pace, formatPace(seconds, km))
                sessionDate?.let { addDetailRow(content, R.string.detail_date, it) }
            }

            PostKind.GYM -> {
                addDetailRow(content, R.string.detail_time, post.displayValue())
                sessionDate?.let { addDetailRow(content, R.string.detail_date, it) }

                // every exercise: sets x reps and weight
                content.addView(TextView(this).apply {
                    setText(R.string.detail_exercises)
                    setTextColor(getColor(R.color.lighterBlue))
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 16f
                    setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                })
                post.exercises.orEmpty().forEach { exercise ->
                    val row = layoutInflater.inflate(R.layout.item_exercise, content, false)
                    bindExerciseRow(row, exercise)
                    row.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        marginStart = 0
                        marginEnd = 0
                    }
                    content.addView(row)
                }
            }
        }
        dialog.show()
    }

    private fun addDetailRow(content: LinearLayout, label: Int, value: String) {
        val row = layoutInflater.inflate(R.layout.item_detail_row, content, false)
        row.findViewById<TextView>(R.id.detailLabel).setText(label)
        row.findViewById<TextView>(R.id.detailValue).text = value
        content.addView(row)
    }
}
