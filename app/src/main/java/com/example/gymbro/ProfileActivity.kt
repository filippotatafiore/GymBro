package com.example.gymbro

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import com.example.gymbro.data.ProfileEntity
import com.example.gymbro.plan.Plan
import com.example.gymbro.repository.PlanRepository
import com.example.gymbro.repository.ProfileRepository
import com.example.gymbro.repository.SessionRepository
import com.example.gymbro.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
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
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
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
import com.example.gymbro.network.Sex
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.PlanAdapter
import com.example.gymbro.plan.PlanGoal
import com.example.gymbro.plan.PlanLevel
import com.example.gymbro.plan.PlanPicker
import com.example.gymbro.profile.BodyData
import com.example.gymbro.profile.ManualExercises
import com.example.gymbro.profile.PersonalBestAdapter
import com.example.gymbro.profile.PersonalBests
import com.example.gymbro.profile.PersonalInfoForm
import com.example.gymbro.profile.PostAdapter
import com.example.gymbro.profile.ProfileHeader
import com.example.gymbro.profile.PictureChooser
import com.example.gymbro.session.ActiveSession
import com.example.gymbro.session.RunTrackingService
import com.example.gymbro.session.publishSession
import kotlinx.coroutines.launch

import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import java.text.DecimalFormat
import java.time.Year
import java.util.Locale
import java.util.Stack

class ProfileActivity : ThemedActivity() {

    companion object {
        // saved state: the menu open, and which page of the menu
        private const val STATE_MENU_OPEN = "menu_open"
        private const val STATE_MENU = "menu"
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var menuContainer: FrameLayout
    private val menuHistory = Stack<Int>() // Stack to store previous menus
    // Menu shown now: saved in menuHistory when another one is opened, to come back to it
    private var currentMenu = R.layout.profile_menu
    // The plans saved on this phone (the active one is shown first)
    private var plans = emptyList<Plan>()
    // A post was just published: the list shows its top, where the new post appears
    private var scrollToNewPost = false

    private lateinit var bottomNav: BottomNavBar
    private lateinit var header: ProfileHeader
    private lateinit var emptyResultsText: TextView
    private lateinit var emptyPersonalBestsText: TextView
    private lateinit var emptyPlansText: TextView
    private lateinit var postList: RecyclerView

    private val postAdapter = PostAdapter(
        onClick = { post -> showPostDetails(this, this, post) },
        onDelete = { post -> confirmDeletePost(post) }
    )
    private val personalBestAdapter = PersonalBestAdapter(onClick = { post -> showPostDetails(this, this, post) })
    private val planAdapter = PlanAdapter(onClick = { plan -> PlanActivity.open(this, plan.id) })

    // A new profile picture: taken with the camera or chosen from the gallery
    private val pictureChooser = PictureChooser(this) { jpeg -> changePicture(jpeg) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            if (storePrevious) menuHistory.push(currentMenu) // Store previous menu
            currentMenu = layoutId

            menuContainer.removeAllViews()
            layoutInflater.inflate(layoutId, menuContainer, true)

            // Personal information: body data of the user (height, weight, year of birth, sex)
            menuContainer.findViewById<Button>(R.id.personalInfoButton)?.setOnClickListener {
                loadProfileMenu(R.layout.personal_info_menu)
            }
            if (layoutId == R.layout.personal_info_menu) showPersonalInfoForm()

            // Customize account: theme and favorite color
            menuContainer.findViewById<Button>(R.id.customizeAccountButton)?.setOnClickListener {
                loadProfileMenu(R.layout.customize_account_menu)
            }
            if (layoutId == R.layout.customize_account_menu) showCustomizeForm()

            // Manage account > Modify account: username, email, password
            menuContainer.findViewById<Button>(R.id.modifyAccountButton)?.setOnClickListener {
                loadProfileMenu(R.layout.modify_account_menu)
            }
            if (layoutId == R.layout.modify_account_menu) showModifyAccountForm()

            // Manage account > Delete account (after a confirmation)
            menuContainer.findViewById<Button>(R.id.deleteAccountButton)?.setOnClickListener {
                confirmDeleteAccount()
            }


            // Manage account: modify or delete the account
            menuContainer.findViewById<Button>(R.id.manageAccountButton)?.setOnClickListener {
                loadProfileMenu(R.layout.manage_account_menu)
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

        // Page recreated with a new look (favorite color, theme) chosen in the menu: the menu is
        // open again on the same page
        if (savedInstanceState?.getBoolean(STATE_MENU_OPEN) == true) {
            loadProfileMenu(savedInstanceState.getInt(STATE_MENU, R.layout.profile_menu), storePrevious = false)
            drawerLayout.openDrawer(GravityCompat.END, false)
            closeMenuOnBack.isEnabled = true
        }


        // --------------------------------------------------------- PROFILE

        // picture, posts, followers and followed users (tap them to see the lists), username, email
        val scroll = findViewById<NestedScrollView>(R.id.profileScroll)
        header = ProfileHeader(
            this,
            onPosts = { scroll.smoothScrollTo(0, findViewById<View>(R.id.postsSection).top) },
            onFollowers = { openFollowList(FollowListActivity.Tab.FOLLOWERS) },
            onFollowing = { openFollowList(FollowListActivity.Tab.FOLLOWING) }
        )
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

        // Tap the profile picture to change it: a new photo or one of the gallery
        header.picture.foreground = getDrawable(R.drawable.menu_item_background)
        header.picture.setOnClickListener { pictureChooser.show() }
        findViewById<Button>(R.id.publishResultButton).setOnClickListener { showPublishChoice() }
        findViewById<Button>(R.id.newPlanButton).setOnClickListener { showCreatePlanDialog() }

        // The profile saved on this phone (also offline), updated at every change: posts and personal
        // bests, workout plans, followers, followed users and picture (see the repositories)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ProfileRepository.posts().collect { showPosts(it) } }
                launch { ProfileRepository.profile().collect { showSavedProfile(it) } }
                launch {
                    PlanRepository.plans().collect {
                        plans = it
                        showPlans()
                    }
                }
            }
        }


        // --------------------------------------------------------- NAVBAR

        bottomNav = BottomNavBar(this, BottomNavBar.Page.PROFILE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MENU_OPEN, drawerLayout.isDrawerOpen(GravityCompat.END))
        outState.putInt(STATE_MENU, currentMenu)
    }

    override fun onResume() {
        super.onResume()
        bottomNav.refresh()
        UserSession.user?.let { showUser(it) }
        // e.g. a plan made active in its page
        showPlans()
        refreshProfile()
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }


    // --------------------------------------------------------- PROFILE

    private fun showUser(user: User) {
        header.username.text = user.username
        header.subtitle.text = user.email
    }

    // Followers or followed users of the logged user
    private fun openFollowList(tab: FollowListActivity.Tab) {
        val user = UserSession.user ?: return
        FollowListActivity.open(this, user.id, user.username, tab)
    }

    // Downloads the profile from the server and saves it on this phone (in background): the pages
    // show it through the repositories. The changes made offline are sent first (personal
    // information, look, workout plans). Offline the profile saved on this phone stays.
    private fun refreshProfile() {
        lifecycleScope.launch {
            try {
                showUser(ProfileRepository.refresh())
            } catch (e: ApiException) {
                when (e.code) {
                    0 -> {}
                    // the login is no longer valid (e.g. logged out from the server)
                    401 -> {
                        Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                        logout()
                        return@launch
                    }
                    else -> Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                }
            }
            PlanRepository.trySync()
        }
    }

    // Followers, followed users and picture saved on this phone (null: never downloaded)
    private suspend fun showSavedProfile(profile: ProfileEntity?) {
        profile ?: return
        header.showFollows(profile.followersCount, profile.followingCount)
        val jpeg = profile.picture
        val picture = jpeg?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        if (picture != null) header.picture.setImageBitmap(picture) else header.picture.setImageResource(R.drawable.default_avatar)
    }

    // Shows the posts of the user and the personal bests chosen among them ("PERSONAL BEST" also on
    // their posts). After a new post: the top of the list, where it appears.
    private fun showPosts(posts: List<Post>) {
        val bests = PersonalBests.from(posts)
        val scrollToTop = scrollToNewPost
        scrollToNewPost = false
        postAdapter.show(posts, bests.map { it.id }.toSet()) {
            if (scrollToTop) postList.scrollToPosition(0)
        }
        emptyResultsText.isVisible = posts.isEmpty()
        header.showPostsCount(posts.size)

        personalBestAdapter.submitList(bests)
        emptyPersonalBestsText.isVisible = bests.isEmpty()
    }

    // Workout plans of the user, the last changed first
    private fun showPlans() {
        // the active plan first, with "ACTIVE"
        val activePlanId = UserSession.user?.activePlanId
        val sorted = plans.sortedByDescending { it.id == activePlanId }
        val activeChanged = planAdapter.activePlanId != activePlanId
        planAdapter.activePlanId = activePlanId
        planAdapter.submitList(sorted) {
            @Suppress("NotifyDataSetChanged")  // the badge of every card may change
            if (activeChanged) planAdapter.notifyDataSetChanged()
        }
        emptyPlansText.isVisible = sorted.isEmpty()
    }

    // Back to the welcome page. The session in progress (if any) is kept for when this user logs in
    // again; the GPS of a run in progress stops until then.
    private fun logout() {
        RunTrackingService.stop(this)
        val token = UserSession.token
        UserSession.clear()
        SessionRepository.clear()

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

    // The new picture (already prepared, see PictureChooser) is uploaded to the server right away
    // (only online), then saved on this phone
    private fun changePicture(jpeg: ByteArray) {
        lifecycleScope.launch {
            try {
                ProfileRepository.changePicture(jpeg)
            } catch (e: ApiException) {
                Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
            }
        }
    }


    // --------------------------------------------------------- PERSONAL INFORMATION

    // "Personal information" page of the menu: body data of the user (see PersonalInfoForm), saved
    // also offline
    private fun showPersonalInfoForm() {
        PersonalInfoForm(menuContainer, lifecycleScope) {
            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawer(GravityCompat.END)
        }
    }


    // --------------------------------------------------------- CUSTOMIZE ACCOUNT

    // "Customize account" page of the menu: theme (dark / light) and favorite color. A choice is
    // applied at once to the whole app (this page is recreated with the menu still open, the other
    // pages when they are shown again) and saved in the account, also offline (see UserRepository).
    private fun showCustomizeForm() {
        val themeGroup = menuContainer.findViewById<RadioGroup>(R.id.themeGroup)
        themeGroup.check(if (Appearance.theme == AppTheme.DARK) R.id.themeDark else R.id.themeLight)
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            changeAppearance(Appearance.accent, if (checkedId == R.id.themeDark) AppTheme.DARK else AppTheme.LIGHT)
        }

        // the favorite colors, 4 for each row
        val grid = menuContainer.findViewById<LinearLayout>(R.id.accentGrid)
        val swatches = mutableMapOf<Accent, View>()
        Accent.entries.chunked(4).forEach { accents ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            accents.forEach { accent ->
                val swatch = layoutInflater.inflate(R.layout.item_accent_swatch, row, false)
                swatch.findViewById<View>(R.id.swatchColor).backgroundTintList =
                    ColorStateList.valueOf(getColor(accent.swatch))
                swatch.findViewById<ImageView>(R.id.swatchCheck).imageTintList =
                    ColorStateList.valueOf(getColor(accent.onSwatch))
                swatch.contentDescription = getString(accent.label)
                swatch.setOnClickListener {
                    // the choice is shown at once, then saved and applied
                    swatches.forEach { (other, view) -> showSwatchChosen(view, other == accent) }
                    menuContainer.findViewById<TextView>(R.id.accentName).setText(accent.label)
                    changeAppearance(accent, Appearance.theme)
                }
                showSwatchChosen(swatch, accent == Appearance.accent)
                swatches[accent] = swatch
                row.addView(swatch)
            }
        }
        menuContainer.findViewById<TextView>(R.id.accentName).setText(Appearance.accent.label)
    }

    private fun showSwatchChosen(swatch: View, chosen: Boolean) {
        swatch.isSelected = chosen
        swatch.findViewById<View>(R.id.swatchCheck).isVisible = chosen
    }

    // Applies the new look and saves it in the account (in background, also offline): a new theme
    // recreates all the pages (Android), a new color this page (and the others when shown again,
    // see ThemedActivity)
    private fun changeAppearance(accent: Accent, theme: AppTheme) {
        if (accent == Appearance.accent && theme == Appearance.theme) return
        val themeChanged = theme != Appearance.theme
        UserRepository.setAppearance(accent, theme)
        if (!themeChanged) recreate()
    }


    // --------------------------------------------------------- MODIFY ACCOUNT, DELETE ACCOUNT

    // "Modify account" page of the settings: username, email and password. The current password is
    // always needed; after a new password, the other phones must log in again.
    private fun showModifyAccountForm() {
        val user = UserSession.user ?: return
        val usernameInput = menuContainer.findViewById<EditText>(R.id.accountUsernameInput)
        val emailInput = menuContainer.findViewById<EditText>(R.id.accountEmailInput)
        val newPasswordInput = menuContainer.findViewById<EditText>(R.id.accountNewPasswordInput)
        val confirmPasswordInput = menuContainer.findViewById<EditText>(R.id.accountConfirmPasswordInput)
        val currentPasswordInput = menuContainer.findViewById<EditText>(R.id.accountCurrentPasswordInput)
        val error = menuContainer.findViewById<TextView>(R.id.accountError)
        val save = menuContainer.findViewById<Button>(R.id.saveAccountButton)
        usernameInput.setText(user.username)
        emailInput.setText(user.email)

        save.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()
            val newPassword = newPasswordInput.text.toString()
            val currentPassword = currentPasswordInput.text.toString()
            val problem = when {
                username.isEmpty() || email.isEmpty() -> R.string.error_fill_all
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> R.string.error_invalid_email
                newPassword.isNotEmpty() && newPassword.length < 6 -> R.string.error_short_password
                newPassword != confirmPasswordInput.text.toString() -> R.string.error_passwords_differ
                currentPassword.isEmpty() -> R.string.error_current_password
                else -> null
            }
            if (problem != null) return@setOnClickListener error.showError(getString(problem))
            val token = UserSession.token ?: return@setOnClickListener
            error.isVisible = false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(save.windowToken, 0)

            lifecycleScope.launch {
                save.isEnabled = false
                try {
                    val updated = ApiClient.updateAccount(
                        token, currentPassword,
                        username.takeIf { it != user.username },
                        email.takeIf { it != user.email },
                        newPassword.ifEmpty { null }
                    )
                    UserSession.updateUser(updated)
                    showUser(updated)
                    Toast.makeText(this@ProfileActivity, R.string.account_updated, Toast.LENGTH_SHORT).show()
                    drawerLayout.closeDrawer(GravityCompat.END)
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    save.isEnabled = true
                }
            }
        }
    }

    // Window asking to confirm with the password; then the account and all its data are deleted
    // from the server (profile, results, sessions, workout plans, picture) and from this phone.
    // The user must register again to use the app.
    private fun confirmDeleteAccount() {
        val dialog = createModal(this, R.layout.dialog_delete_account, this)
        val password = dialog.findViewById<EditText>(R.id.deletePasswordInput)
        val error = dialog.findViewById<TextView>(R.id.deleteAccountError)
        val confirm = dialog.findViewById<Button>(R.id.confirmDeleteAccountButton)
        dialog.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }

        confirm.setOnClickListener {
            if (password.text.isEmpty()) return@setOnClickListener error.showError(getString(R.string.error_current_password))
            val token = UserSession.token ?: return@setOnClickListener
            val userId = UserSession.userId ?: return@setOnClickListener
            lifecycleScope.launch {
                confirm.isEnabled = false
                try {
                    ApiClient.deleteAccount(token, password.text.toString())
                    dialog.dismiss()
                    deleteLocalData(userId)
                    Toast.makeText(this@ProfileActivity, R.string.account_deleted, Toast.LENGTH_LONG).show()
                    WelcomeActivity.open(this@ProfileActivity)
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    confirm.isEnabled = true
                }
            }
        }
        dialog.show()
    }

    // The data of the deleted account on this phone: session in progress, sessions, workout plans,
    // posts, profile, chats with the coach, login
    private fun deleteLocalData(userId: Long) {
        RunTrackingService.stop(this)
        SessionRepository.clear()
        UserSession.clear()
        // session in progress of the user (ActiveSession, swim and its settings) and plan saved by the
        // older versions of the app: emptied right now (commit), otherwise a write in background
        // would create the file again
        for (name in listOf("active_session_$userId", "swim_$userId", "swim_settings_$userId", "active_plan_$userId")) {
            getSharedPreferences(name, MODE_PRIVATE).edit(commit = true) { clear() }
            deleteSharedPreferences(name)
        }
        val database = gymBroApp.database
        gymBroApp.applicationScope.launch {
            database.chatMessageDao().deleteAllOfUser(userId)
            SessionRepository.deleteAll(userId)
            PlanRepository.deleteAll(userId)
            ProfileRepository.deleteAll(userId)
        }
    }


    // --------------------------------------------------------- PUBLISH

    // Modal window of the "Publish" button: a finished session, a result added manually, or a
    // workout plan (it becomes public)
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
        dialog.findViewById<View>(R.id.publishPlanOption).setOnClickListener {
            dialog.dismiss()
            showPlanToPublishPicker()
        }
        dialog.show()
    }

    // The private workout plans: the one tapped becomes public (everyone sees it on the profile and
    // can add it to their plans; it can be made private again in its page). Only online.
    private fun showPlanToPublishPicker() {
        lifecycleScope.launch {
            try {
                PlanRepository.sync()
                val privatePlans = PlanRepository.all().filter { !it.isPublic }
                PlanPicker.show(
                    this@ProfileActivity, this@ProfileActivity,
                    getString(R.string.publish_plan_title), getString(R.string.publish_plan_message),
                    getString(R.string.no_private_plans), privatePlans
                ) { plan, picker ->
                    picker.busy = true
                    lifecycleScope.launch {
                        try {
                            PlanRepository.setPublic(plan.id, true)
                            picker.dialog.dismiss()
                            Toast.makeText(this@ProfileActivity, R.string.plan_published, Toast.LENGTH_SHORT).show()
                        } catch (e: ApiException) {
                            picker.showError(e.userMessage(this@ProfileActivity))
                        } finally {
                            picker.busy = false
                        }
                    }
                }
            } catch (e: ApiException) {
                Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
            }
        }
    }

    // List of the finished sessions not published yet: tap one to publish it.
    // The session in progress is not in the list: it can be published only once it is finished.
    private fun showSessionPicker() {
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
                        scrollToNewPost = true
                        publishSession(this@ProfileActivity, session)
                        dialog.dismiss()
                        Toast.makeText(this@ProfileActivity, R.string.session_published, Toast.LENGTH_SHORT).show()
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

        // The sessions of LAST SESSIONS not published yet (synchronized first with the server, if it
        // is reachable: publishing needs it)
        lifecycleScope.launch {
            try {
                SessionRepository.sync()
            } catch (e: ApiException) {
                noSessionsText.text = e.userMessage(this@ProfileActivity)
            }
            val sessions = SessionRepository.all().filter { it.publishedPostId == null }
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
                    val post = ApiClient.publishManual(token, exercise.name, metric, better, amount, notesInput.text.toString().trim())
                    dialog.dismiss()
                    scrollToNewPost = true
                    ProfileRepository.addPost(post)
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
                    // also on this phone: it can be seen, followed and changed offline
                    PlanRepository.store(plan)
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

    // Asks for confirmation, then deletes the post (only online): posts and personal bests are
    // updated through ProfileRepository
    private fun confirmDeletePost(post: Post) {
        showConfirmModal(
            this, this,
            R.string.delete_post_title, R.string.delete_post_message, R.string.delete
        ) {
            lifecycleScope.launch {
                try {
                    ProfileRepository.deletePost(post)
                } catch (e: ApiException) {
                    Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
