package com.example.gymbro

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import com.example.gymbro.data.ProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.home.SessionHistoryAdapter
import com.example.gymbro.network.Better
import com.example.gymbro.network.Metric
import com.example.gymbro.network.PlanRequest
import com.example.gymbro.network.Post
import com.example.gymbro.network.Sex
import com.example.gymbro.network.User
import com.example.gymbro.network.userMessage
import com.example.gymbro.plan.PlanAdapter
import com.example.gymbro.plan.PlanGoal
import com.example.gymbro.plan.PlanLevel
import com.example.gymbro.plan.PlanPicker
import com.example.gymbro.profile.BodyData
import com.example.gymbro.profile.ManualExercises
import com.example.gymbro.profile.PersonalBestAdapter
import com.example.gymbro.profile.PersonalInfoForm
import com.example.gymbro.profile.PostAdapter
import com.example.gymbro.profile.ProfileHeader
import com.example.gymbro.profile.PictureChooser
import com.example.gymbro.viewmodel.FollowListViewModel
import com.example.gymbro.viewmodel.ProfileViewModel
import com.example.gymbro.viewmodel.ProfileViewModel.Event

import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import java.text.DecimalFormat
import java.time.Year
import java.util.Locale
import java.util.Stack

// Profile of the logged user: posts and personal bests, workout plans, followers and followed
// users, picture, publishing, and the sliding menu (personal information, look, account, logout).
// Data and actions: ProfileViewModel.
class ProfileActivity : ThemedActivity() {

    companion object {
        // saved state: the menu open, and which page of the menu
        private const val STATE_MENU_OPEN = "menu_open"
        private const val STATE_MENU = "menu"
    }

    private val viewModel: ProfileViewModel by viewModels { ProfileViewModel.Factory }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var menuContainer: FrameLayout
    private val menuHistory = Stack<Int>() // Stack to store previous menus
    // Menu shown now: saved in menuHistory when another one is opened, to come back to it
    private var currentMenu = R.layout.profile_menu
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
    private val pictureChooser = PictureChooser(this) { jpeg -> viewModel.changePicture(jpeg) }

    // The forms and windows waiting for the server (the last one opened of each kind): their
    // buttons wait, and they show the answer (e.g. an error)
    private var accountError: TextView? = null
    private var accountSave: Button? = null
    private var deleteDialog: Dialog? = null
    private var deleteError: TextView? = null
    private var deleteConfirm: Button? = null
    private var planPicker: PlanPicker? = null
    private var sessionPicker: Dialog? = null
    private var sessionPickerAdapter: SessionHistoryAdapter? = null
    private var manualDialog: Dialog? = null
    private var manualError: TextView? = null
    private var manualConfirm: Button? = null
    private var createPlanDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nobody logged in (e.g. app restored after the logout): back to the welcome page
        if (!viewModel.isLoggedIn) {
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
                viewModel.logout()
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
            onFollowers = { openFollowList(FollowListViewModel.Tab.FOLLOWERS) },
            onFollowing = { openFollowList(FollowListViewModel.Tab.FOLLOWING) }
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
        // bests, workout plans, followers, followed users and picture (see ProfileViewModel)
        observe(viewModel.posts) { posts -> posts?.let { showPosts(it) } }
        observe(viewModel.profile) { profile -> showSavedProfile(profile) }
        observe(viewModel.workoutPlans) { plans -> plans?.let { showPlans(it) } }
        observe(viewModel.user) { user -> user?.let { showUser(it) } }
        observe(viewModel.busy) { busy -> showBusy(busy) }
        onEvents(viewModel.events) { event -> handle(event) }


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
        bottomNav.refresh(viewModel.sessionStatus())
        // the profile downloaded again (e.g. a plan made active in its page)
        viewModel.onPageShown()
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }

    // The answers of the server to the actions of the user
    private fun handle(event: Event) {
        when (event) {
            is Event.Error -> showLongToast(event.error.userMessage(this))
            Event.LoggedOut -> WelcomeActivity.open(this)
            Event.AccountUpdated -> {
                Toast.makeText(this, R.string.account_updated, Toast.LENGTH_SHORT).show()
                drawerLayout.closeDrawer(GravityCompat.END)
            }
            is Event.AccountNotUpdated -> accountError?.showError(event.error.userMessage(this))
            Event.AccountDeleted -> {
                deleteDialog?.dismiss()
                Toast.makeText(this, R.string.account_deleted, Toast.LENGTH_LONG).show()
                WelcomeActivity.open(this)
            }
            is Event.AccountNotDeleted -> deleteError?.showError(event.error.userMessage(this))
            is Event.PlansToPublish -> showPlanToPublishPicker(event)
            Event.PlanPublished -> {
                planPicker?.dialog?.dismiss()
                Toast.makeText(this, R.string.plan_published, Toast.LENGTH_SHORT).show()
            }
            is Event.PlanNotPublished -> planPicker?.showError(event.error.userMessage(this))
            is Event.SessionsToPublish -> showSessionsToPublish(event)
            Event.SessionPublished -> {
                sessionPicker?.dismiss()
                Toast.makeText(this, R.string.session_published, Toast.LENGTH_SHORT).show()
            }
            Event.ManualPublished -> manualDialog?.dismiss()
            is Event.ManualNotPublished -> {
                scrollToNewPost = false
                manualError?.showError(event.error.userMessage(this))
            }
            is Event.PlanCreated -> {
                createPlanDialog?.dismiss()
                PlanActivity.open(this, event.planId)
            }
            is Event.PlanNotCreated ->
                createPlanDialog?.findViewById<TextView>(R.id.createPlanError)?.showError(event.error.userMessage(this))
        }
    }

    private fun showLongToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // The buttons of the forms and windows waiting for the server
    private fun showBusy(busy: ProfileViewModel.Busy) {
        accountSave?.isEnabled = !busy.savingAccount
        deleteConfirm?.isEnabled = !busy.deletingAccount
        planPicker?.busy = busy.publishingPlan
        manualConfirm?.isEnabled = !busy.publishingManual
        createPlanDialog?.let { dialog ->
            // while the coach writes the plan the window cannot be closed
            dialog.setCancelable(!busy.creatingPlan)
            dialog.findViewById<Button>(R.id.createPlanButton)?.isEnabled = !busy.creatingPlan
            dialog.findViewById<View>(R.id.createPlanProgress)?.isVisible = busy.creatingPlan
            if (busy.creatingPlan) dialog.findViewById<View>(R.id.createPlanError)?.isVisible = false
        }
    }


    // --------------------------------------------------------- PROFILE

    private fun showUser(user: User) {
        header.username.text = user.username
        header.subtitle.text = user.email
    }

    // Followers or followed users of the logged user
    private fun openFollowList(tab: FollowListViewModel.Tab) {
        val user = viewModel.user.value ?: return
        FollowListActivity.open(this, user.id, user.username, tab)
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
    private fun showPosts(posts: ProfileViewModel.Posts) {
        val bests = posts.personalBests
        val scrollToTop = scrollToNewPost
        scrollToNewPost = false
        postAdapter.show(posts.posts, bests.map { it.id }.toSet()) {
            if (scrollToTop) postList.scrollToPosition(0)
        }
        emptyResultsText.isVisible = posts.posts.isEmpty()
        header.showPostsCount(posts.posts.size)

        personalBestAdapter.submitList(bests)
        emptyPersonalBestsText.isVisible = bests.isEmpty()
    }

    // Workout plans of the user, the active one first (with "ACTIVE"), then the last changed
    private fun showPlans(plans: ProfileViewModel.Plans) {
        val activeChanged = planAdapter.activePlanId != plans.activePlanId
        planAdapter.activePlanId = plans.activePlanId
        planAdapter.submitList(plans.plans) {
            @Suppress("NotifyDataSetChanged")  // the badge of every card may change
            if (activeChanged) planAdapter.notifyDataSetChanged()
        }
        emptyPlansText.isVisible = plans.plans.isEmpty()
    }


    // --------------------------------------------------------- PERSONAL INFORMATION

    // "Personal information" page of the menu: body data of the user (see PersonalInfoForm), saved
    // also offline
    private fun showPersonalInfoForm() {
        PersonalInfoForm(menuContainer, viewModel.user.value) { heightCm, weightKg, birthYear, sex ->
            viewModel.saveBodyData(heightCm, weightKg, birthYear, sex)
            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawer(GravityCompat.END)
        }
    }


    // --------------------------------------------------------- CUSTOMIZE ACCOUNT

    // "Customize account" page of the menu: theme (dark / light) and favorite color. A choice is
    // applied at once to the whole app (this page is recreated with the menu still open, the other
    // pages when they are shown again) and saved in the account, also offline (see ProfileViewModel).
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
        viewModel.changeAppearance(accent, theme)
        if (!themeChanged) recreate()
    }


    // --------------------------------------------------------- MODIFY ACCOUNT, DELETE ACCOUNT

    // "Modify account" page of the settings: username, email and password. The current password is
    // always needed; after a new password, the other phones must log in again.
    private fun showModifyAccountForm() {
        val user = viewModel.user.value ?: return
        val usernameInput = menuContainer.findViewById<EditText>(R.id.accountUsernameInput)
        val emailInput = menuContainer.findViewById<EditText>(R.id.accountEmailInput)
        val newPasswordInput = menuContainer.findViewById<EditText>(R.id.accountNewPasswordInput)
        val confirmPasswordInput = menuContainer.findViewById<EditText>(R.id.accountConfirmPasswordInput)
        val currentPasswordInput = menuContainer.findViewById<EditText>(R.id.accountCurrentPasswordInput)
        val error = menuContainer.findViewById<TextView>(R.id.accountError)
        val save = menuContainer.findViewById<Button>(R.id.saveAccountButton)
        usernameInput.setText(user.username)
        emailInput.setText(user.email)
        accountError = error
        accountSave = save.apply { isEnabled = !viewModel.busy.value.savingAccount }

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
            error.isVisible = false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(save.windowToken, 0)
            viewModel.updateAccount(username, email, newPassword, currentPassword)
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
        deleteDialog = dialog
        deleteError = error
        deleteConfirm = confirm.apply { isEnabled = !viewModel.busy.value.deletingAccount }

        confirm.setOnClickListener {
            if (password.text.isEmpty()) return@setOnClickListener error.showError(getString(R.string.error_current_password))
            viewModel.deleteAccount(password.text.toString())
        }
        dialog.show()
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
            viewModel.choosePlanToPublish()
        }
        dialog.show()
    }

    // The private workout plans: the one tapped becomes public (everyone sees it on the profile and
    // can add it to their plans; it can be made private again in its page). Only online.
    private fun showPlanToPublishPicker(event: Event.PlansToPublish) {
        planPicker = PlanPicker.show(
            this, this,
            getString(R.string.publish_plan_title), getString(R.string.publish_plan_message),
            getString(R.string.no_private_plans), event.plans
        ) { plan, _ -> viewModel.publishPlan(plan.id) }
    }

    // List of the finished sessions not published yet: tap one to publish it.
    // The session in progress is not in the list: it can be published only once it is finished.
    private fun showSessionPicker() {
        val dialog = createModal(this, R.layout.dialog_publish_session, this)
        val list = dialog.findViewById<RecyclerView>(R.id.sessionPickerList)
        dialog.findViewById<View>(R.id.activeSessionHint).isVisible = viewModel.sessionInProgress
        dialog.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }

        val adapter = SessionHistoryAdapter(onClick = { session ->
            scrollToNewPost = true
            viewModel.publishSession(session)
        })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        sessionPicker = dialog
        sessionPickerAdapter = adapter

        // The sessions of LAST SESSIONS not published yet (synchronized first with the server, if it
        // is reachable: publishing needs it)
        viewModel.chooseSessionToPublish()
        dialog.show()
    }

    private fun showSessionsToPublish(event: Event.SessionsToPublish) {
        val dialog = sessionPicker ?: return
        val noSessionsText = dialog.findViewById<TextView>(R.id.noSessionsText)
        event.problem?.let { noSessionsText.text = it.userMessage(this) }
        sessionPickerAdapter?.submitList(event.sessions)
        noSessionsText.isVisible = event.sessions.isEmpty()
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
        manualDialog = dialog
        manualError = error
        manualConfirm = confirm.apply { isEnabled = !viewModel.busy.value.publishingManual }

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
            // the new post appears at the top of the list
            scrollToNewPost = true
            viewModel.publishManual(exercise.name, metric, better, amount, notesInput.text.toString().trim())
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
        val create = dialog.findViewById<Button>(R.id.createPlanButton)

        val user = viewModel.user.value
        dialog.findViewById<TextView>(R.id.planBodyData).text = bodyDataSummary(user)
        dialog.findViewById<View>(R.id.planBodyDataHint).isVisible = user?.heightCm == null || user.weightKg == null

        goalSpinner.adapter = ArrayAdapter(this, R.layout.item_spinner, PlanGoal.entries.map { getString(it.label) })
        goalSpinner.setSelection(PlanGoal.BUILD_MUSCLE.ordinal)
        levelGroup.check(R.id.levelBeginner)
        daysGroup.check(R.id.days3)
        minutesGroup.check(R.id.minutes60)

        create.setOnClickListener {
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
            viewModel.createPlan(request)
        }
        createPlanDialog = dialog
        showBusy(viewModel.busy.value)
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
    // updated through ProfileViewModel
    private fun confirmDeletePost(post: Post) {
        showConfirmModal(
            this, this,
            R.string.delete_post_title, R.string.delete_post_message, R.string.delete
        ) {
            viewModel.deletePost(post)
        }
    }
}
