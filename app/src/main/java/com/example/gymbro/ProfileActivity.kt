package com.example.gymbro

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.home.SessionHistoryAdapter
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.PlanRequest
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind
import com.example.gymbro.network.Sex
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.PlanAdapter
import com.example.gymbro.plan.PlanGoal
import com.example.gymbro.plan.PlanLevel
import com.example.gymbro.profile.BodyData
import com.example.gymbro.profile.ManualExercises
import com.example.gymbro.profile.PersonalBestAdapter
import com.example.gymbro.profile.PersonalBests
import com.example.gymbro.profile.PostAdapter
import com.example.gymbro.profile.ProfilePictures
import com.example.gymbro.profile.displayTitle
import com.example.gymbro.profile.displayValue
import com.example.gymbro.profile.icon
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.RunTrackingService
import com.example.gymbro.session.publishSession
import com.example.gymbro.session.toRunSummary
import kotlinx.coroutines.launch

import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import java.text.DecimalFormat
import java.time.Year
import java.util.Locale
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
    private lateinit var emptyPlansText: TextView
    private lateinit var postList: RecyclerView

    private val postAdapter = PostAdapter(
        onClick = { post -> showPostDetails(post) },
        onDelete = { post -> confirmDeletePost(post) }
    )
    private val personalBestAdapter = PersonalBestAdapter(onClick = { post -> showPostDetails(post) })
    private val planAdapter = PlanAdapter(onClick = { plan -> PlanActivity.open(this, plan.id) })

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

        // Menu shown now: saved in menuHistory when another one is opened, to come back to it
        var currentMenu = R.layout.profile_menu

        // Function to load profile menu and store previous state
        fun loadProfileMenu(layoutId: Int, storePrevious: Boolean = true) {
            if (storePrevious) menuHistory.push(currentMenu) // Store previous menu
            currentMenu = layoutId

            menuContainer.removeAllViews()
            layoutInflater.inflate(layoutId, menuContainer, true)

            // Handle the modify account button (if present in the current layout)
            menuContainer.findViewById<Button>(R.id.modifyAccountButton)?.setOnClickListener {
                loadProfileMenu(R.layout.account_menu) // Open the body data of the user
            }

            // Fill the form with the body data (if the current layout is the account menu)
            if (layoutId == R.layout.account_menu) showAccountForm()


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
                    loadProfileMenu(R.layout.profile_menu, storePrevious = false) // Default fallback
                }
            }
        }

        // Load the initial profile menu
        loadProfileMenu(R.layout.profile_menu, storePrevious = false)

        // The back button closes the menu (instead of the page) while it is open
        val closeMenuOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = drawerLayout.closeDrawer(GravityCompat.END)
        }
        onBackPressedDispatcher.addCallback(this, closeMenuOnBack)

        // Listen for drawer close event
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                closeMenuOnBack.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                closeMenuOnBack.isEnabled = false
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
        emptyPlansText = findViewById(R.id.emptyPlansText)

        val personalBestList = findViewById<RecyclerView>(R.id.personalBestList)
        personalBestList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        personalBestList.adapter = personalBestAdapter

        val planList = findViewById<RecyclerView>(R.id.planList)
        planList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        planList.adapter = planAdapter

        postList = findViewById(R.id.postList)
        postList.layoutManager = LinearLayoutManager(this)
        postList.adapter = postAdapter

        // Tap the profile picture to change it
        profilePicture.setOnClickListener {
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.publishResultButton).setOnClickListener { showPublishChoice() }
        findViewById<Button>(R.id.newPlanButton).setOnClickListener { showCreatePlanDialog() }


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

    // Downloads profile data, picture, posts and workout plans from the server (in background)
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
                showPlans(token)
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

    // Workout plans of the user, the last changed first
    private suspend fun showPlans(token: String) {
        val plans = ApiClient.getPlans(token)
        planAdapter.submitList(plans)
        emptyPlansText.isVisible = plans.isEmpty()
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

    // Back to the welcome page. The session in progress (if any) is kept for when this user logs in
    // again; the GPS of a run in progress stops until then.
    private fun logout() {
        RunTrackingService.stop(this)
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


    // --------------------------------------------------------- MODIFY ACCOUNT

    // "Modify account" page of the menu: body data of the user (height, weight, year of birth,
    // sex), saved on the server. The weight is used for the calories of the runs.
    // An empty field = not set.
    private fun showAccountForm() {
        val user = UserSession.user ?: return
        val heightInput = menuContainer.findViewById<EditText>(R.id.profileHeightInput)
        val weightInput = menuContainer.findViewById<EditText>(R.id.profileWeightInput)
        val birthYearInput = menuContainer.findViewById<EditText>(R.id.profileBirthYearInput)
        val sexGroup = menuContainer.findViewById<RadioGroup>(R.id.profileSexGroup)
        val bmiText = menuContainer.findViewById<TextView>(R.id.profileBmiText)
        val error = menuContainer.findViewById<TextView>(R.id.profileError)
        val save = menuContainer.findViewById<Button>(R.id.saveProfileButton)

        val numberFormat = DecimalFormat("0.#")
        user.heightCm?.let { heightInput.setText(numberFormat.format(it)) }
        user.weightKg?.let { weightInput.setText(numberFormat.format(it)) }
        user.birthYear?.let { birthYearInput.setText(String.format(Locale.ROOT, "%d", it)) }
        when (user.sex) {
            Sex.MALE -> sexGroup.check(R.id.sexMale)
            Sex.FEMALE -> sexGroup.check(R.id.sexFemale)
            Sex.OTHER -> sexGroup.check(R.id.sexOther)
            null -> {}
        }

        fun EditText.number(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()

        // BMI, updated while typing height and weight
        fun showBmi() {
            val bmi = BodyData.bmi(heightInput.number(), weightInput.number())
            bmiText.text =
                if (bmi == null || bmi !in 5.0..100.0) getString(R.string.bmi_missing)
                else getString(R.string.bmi_value, DecimalFormat("0.0").format(bmi), getString(BodyData.bmiCategory(bmi)))
        }
        heightInput.doAfterTextChanged { showBmi() }
        weightInput.doAfterTextChanged { showBmi() }
        showBmi()

        save.setOnClickListener {
            val height = heightInput.number()
            val weight = weightInput.number()
            val birthYear = birthYearInput.text.toString().trim().toIntOrNull()
            val thisYear = Year.now().value
            if (heightInput.text.isNotBlank() && (height == null || height !in 100.0..250.0)) {
                return@setOnClickListener error.showError(getString(R.string.error_invalid_height))
            }
            if (weightInput.text.isNotBlank() && (weight == null || weight !in 30.0..300.0)) {
                return@setOnClickListener error.showError(getString(R.string.error_invalid_body_weight))
            }
            if (birthYearInput.text.isNotBlank() && (birthYear == null || birthYear !in 1900..thisYear)) {
                return@setOnClickListener error.showError(getString(R.string.error_invalid_birth_year, thisYear))
            }
            val sex = when (sexGroup.checkedRadioButtonId) {
                R.id.sexMale -> Sex.MALE
                R.id.sexFemale -> Sex.FEMALE
                R.id.sexOther -> Sex.OTHER
                else -> null
            }
            val token = UserSession.token ?: return@setOnClickListener
            error.isVisible = false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(save.windowToken, 0)

            lifecycleScope.launch {
                save.isEnabled = false
                try {
                    UserSession.updateUser(ApiClient.updateProfile(token, height, weight, birthYear, sex))
                    Toast.makeText(this@ProfileActivity, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                    drawerLayout.closeDrawer(GravityCompat.END)
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    save.isEnabled = true
                }
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


    // --------------------------------------------------------- WORKOUT PLANS

    // Modal window of the "New" button of WORKOUT PLANS: the AI coach writes a gym plan from the body
    // data of the profile (shown in the window: never username, email or password), the goal, the
    // level, the days per week, the minutes per session and the preferences. Then the plan opens.
    private fun showCreatePlanDialog() {
        val dialog = createModal(this, R.layout.dialog_create_plan, this)
        val goalSpinner = dialog.findViewById<Spinner>(R.id.planGoalSpinner)
        val levelGroup = dialog.findViewById<RadioGroup>(R.id.planLevelGroup)
        val daysGroup = dialog.findViewById<RadioGroup>(R.id.planDaysGroup)
        val minutesGroup = dialog.findViewById<RadioGroup>(R.id.planMinutesGroup)
        val preferences = dialog.findViewById<EditText>(R.id.planPreferencesInput)
        val error = dialog.findViewById<TextView>(R.id.createPlanError)
        val progress = dialog.findViewById<View>(R.id.createPlanProgress)
        val create = dialog.findViewById<Button>(R.id.createPlanButton)

        val user = UserSession.user
        dialog.findViewById<TextView>(R.id.planBodyData).text = bodyDataSummary(user)
        dialog.findViewById<View>(R.id.planBodyDataHint).isVisible = user?.heightCm == null || user.weightKg == null

        goalSpinner.adapter = ArrayAdapter(this, R.layout.item_spinner, PlanGoal.entries.map { getString(it.label) })
        goalSpinner.setSelection(PlanGoal.BUILD_MUSCLE.ordinal)
        levelGroup.check(R.id.levelBeginner)
        daysGroup.check(R.id.days3)
        minutesGroup.check(R.id.minutes60)

        create.setOnClickListener {
            val token = UserSession.token ?: return@setOnClickListener
            val request = PlanRequest(
                goal = PlanGoal.entries[goalSpinner.selectedItemPosition],
                level = when (levelGroup.checkedRadioButtonId) {
                    R.id.levelIntermediate -> PlanLevel.INTERMEDIATE
                    R.id.levelAdvanced -> PlanLevel.ADVANCED
                    else -> PlanLevel.BEGINNER
                },
                daysPerWeek = when (daysGroup.checkedRadioButtonId) {
                    R.id.days2 -> 2
                    R.id.days4 -> 4
                    R.id.days5 -> 5
                    R.id.days6 -> 6
                    else -> 3
                },
                minutesPerSession = when (minutesGroup.checkedRadioButtonId) {
                    R.id.minutes30 -> 30
                    R.id.minutes45 -> 45
                    R.id.minutes90 -> 90
                    else -> 60
                },
                preferences = preferences.text.toString().trim(),
                // the plan is written in the language of the phone
                language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
            )
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(create.windowToken, 0)

            // while the coach writes the plan the window cannot be closed
            dialog.setCancelable(false)
            create.isEnabled = false
            progress.isVisible = true
            error.isVisible = false
            lifecycleScope.launch {
                try {
                    val plan = ApiClient.createPlan(token, request)
                    dialog.dismiss()
                    PlanActivity.open(this@ProfileActivity, plan.id)
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    dialog.setCancelable(true)
                    create.isEnabled = true
                    progress.isVisible = false
                }
            }
        }
        dialog.show()
    }

    // Body data used by the coach for a plan, e.g. "180 cm · 72.5 kg · BMI 22.4 · 25 years · Male"
    private fun bodyDataSummary(user: User?): String {
        val number = DecimalFormat("0.#")
        val parts = listOfNotNull(
            user?.heightCm?.let { "${number.format(it)} cm" },
            user?.weightKg?.let { "${number.format(it)} kg" },
            BodyData.bmi(user?.heightCm, user?.weightKg)?.let { "BMI " + DecimalFormat("0.0").format(it) },
            user?.birthYear?.let { getString(R.string.age_years, Year.now().value - it) },
            user?.sex?.let {
                getString(when (it) {
                    Sex.MALE -> R.string.sex_male
                    Sex.FEMALE -> R.string.sex_female
                    Sex.OTHER -> R.string.sex_other
                })
            }
        )
        return if (parts.isEmpty()) getString(R.string.plan_no_body_data) else parts.joinToString(" · ")
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

    // Modal window with all the information of a post (see SessionDetails.kt):
    // - run: map with the whole path followed and the final statistics
    // - gym: training time and exercises
    // - manual: the result and how it is evaluated
    private fun showPostDetails(post: Post) {
        val title = post.displayTitle(this)
        val published = getString(R.string.published_on, formatDate(post.createdAt))
        // sessions: when the session was done
        val sessionDate = post.sessionStart?.let { formatDateTime(it.toEpochMilli()) } ?: published

        when (post.kind) {
            PostKind.RUN -> showRunDetails(this, this, title, sessionDate, post.toRunSummary())

            PostKind.GYM -> showGymDetails(
                this, this, title, sessionDate, (post.durationSeconds ?: 0L) * 1000, post.exercises.orEmpty()
            )

            PostKind.MANUAL -> {
                val (dialog, content) = detailsDialog(this, this, post.icon(), title, published)
                addDetailRow(content, R.string.detail_result, post.displayValue())
                addDetailRow(content, R.string.detail_measured_by,
                    getString(if (post.metric == Metric.TIME) R.string.metric_time else R.string.metric_weight))
                addDetailRow(content, R.string.detail_better,
                    getString(if (post.better == Better.LOWER) R.string.better_lower else R.string.better_higher))
                if (!post.notes.isNullOrBlank()) addDetailRow(content, R.string.detail_notes, post.notes)
                dialog.show()
            }
        }
    }
}
