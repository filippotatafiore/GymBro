package com.example.gymbro

import android.app.Dialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.profile.ProfilePictures
import com.example.gymbro.profile.ResultAdapter
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
    private lateinit var loggedOutView: View
    private lateinit var loggedInView: View
    private lateinit var profilePicture: ImageView
    private lateinit var usernameText: TextView
    private lateinit var emailText: TextView
    private lateinit var emptyResultsText: TextView
    private val resultAdapter = ResultAdapter()

    // Registration window, while it is open, and the profile picture chosen in it
    // (uploaded right after the account is created)
    private var registerDialog: Dialog? = null
    private var registerPicture: ByteArray? = null

    // Opens the system photo picker (no permission needed); the lambda receives the chosen photo
    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicturePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()

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


        // --------------------------------------------------------- NOT LOGGED IN

        loggedOutView = findViewById(R.id.loggedOutView)
        findViewById<Button>(R.id.register_Button).setOnClickListener { showRegisterDialog() }
        findViewById<Button>(R.id.loginButton).setOnClickListener { showLoginDialog() }


        // --------------------------------------------------------- PROFILE

        loggedInView = findViewById(R.id.loggedInView)
        profilePicture = findViewById(R.id.profilePicture)
        usernameText = findViewById(R.id.profileUsername)
        emailText = findViewById(R.id.profileEmail)
        emptyResultsText = findViewById(R.id.emptyResultsText)

        val resultList = findViewById<RecyclerView>(R.id.resultList)
        resultList.layoutManager = LinearLayoutManager(this)
        resultList.adapter = resultAdapter

        // Tap the profile picture to change it
        profilePicture.setOnClickListener { choosePicture() }
        findViewById<Button>(R.id.publishResultButton).setOnClickListener { showPublishDialog() }


        // --------------------------------------------------------- NAVBAR

        bottomNav = BottomNavBar(this, BottomNavBar.Page.PROFILE)
    }

    override fun onResume() {
        super.onResume()
        bottomNav.refresh()
        showLoginState()
        if (UserSession.isLoggedIn) refreshProfile()
    }

    override fun onPause() {
        super.onPause()
        bottomNav.stopGlow()
    }


    // --------------------------------------------------------- LOGIN STATE

    // Shows the profile if logged in, the invitation to register / log in otherwise
    private fun showLoginState() {
        val loggedIn = UserSession.isLoggedIn
        loggedInView.isVisible = loggedIn
        loggedOutView.isVisible = !loggedIn

        // The sliding menu can be used only once the user has accessed the profile
        menuButton.isVisible = loggedIn
        drawerLayout.setDrawerLockMode(
            if (loggedIn) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        )

        UserSession.user?.let { showUser(it) }
    }

    private fun showUser(user: User) {
        usernameText.text = user.username
        emailText.text = user.email
    }

    // Downloads profile data, picture and results from the server (in background)
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

                showResults(user.id)
            } catch (e: ApiException) {
                if (e.code == 401) {
                    // the login is no longer valid (e.g. logged out from the server)
                    UserSession.clear()
                    showLoginState()
                }
                Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun showResults(userId: Long) {
        val results = ApiClient.getResults(userId)
        resultAdapter.submitList(results)
        emptyResultsText.isVisible = results.isEmpty()
    }

    private fun logout() {
        val token = UserSession.token
        UserSession.clear()
        showLoginState()
        profilePicture.setImageResource(R.drawable.default_avatar)
        resultAdapter.submitList(emptyList())

        // Invalidate the token on the server too (if the server is not reachable there is nothing to do)
        if (token != null) {
            lifecycleScope.launch {
                try {
                    ApiClient.logout(token)
                } catch (_: ApiException) {
                }
            }
        }
    }


    // --------------------------------------------------------- REGISTRATION / LOGIN

    private fun showRegisterDialog() {
        val dialog = createModal(this, R.layout.dialog_register, this, onDismiss = {
            registerDialog = null
            registerPicture = null
        })
        val username = dialog.findViewById<EditText>(R.id.registerUsername)
        val email = dialog.findViewById<EditText>(R.id.registerEmail)
        val password = dialog.findViewById<EditText>(R.id.registerPassword)
        val error = dialog.findViewById<TextView>(R.id.registerError)
        val confirm = dialog.findViewById<Button>(R.id.registerConfirmButton)

        dialog.findViewById<ImageView>(R.id.registerPicture).setOnClickListener { choosePicture() }

        confirm.setOnClickListener {
            val usernameText = username.text.toString().trim()
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString()

            when {
                usernameText.isEmpty() || emailText.isEmpty() || passwordText.isEmpty() ->
                    return@setOnClickListener error.showError(getString(R.string.error_fill_all))
                !Patterns.EMAIL_ADDRESS.matcher(emailText).matches() ->
                    return@setOnClickListener error.showError(getString(R.string.error_invalid_email))
                passwordText.length < 6 ->
                    return@setOnClickListener error.showError(getString(R.string.error_short_password))
            }

            lifecycleScope.launch {
                confirm.isEnabled = false
                try {
                    val auth = ApiClient.register(usernameText, emailText, passwordText)
                    UserSession.save(auth.token, auth.user)

                    registerPicture?.let { picture ->
                        try {
                            ApiClient.uploadPicture(auth.token, picture)
                        } catch (e: ApiException) {
                            // the account exists anyway: the picture can be changed from the profile
                            Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                        }
                    }

                    dialog.dismiss()
                    showLoginState()
                    refreshProfile()
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    confirm.isEnabled = true
                }
            }
        }

        registerDialog = dialog
        dialog.show()
    }

    private fun showLoginDialog() {
        val dialog = createModal(this, R.layout.dialog_login, this)
        val email = dialog.findViewById<EditText>(R.id.loginEmail)
        val password = dialog.findViewById<EditText>(R.id.loginPassword)
        val error = dialog.findViewById<TextView>(R.id.loginError)
        val confirm = dialog.findViewById<Button>(R.id.loginConfirmButton)

        confirm.setOnClickListener {
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString()
            if (emailText.isEmpty() || passwordText.isEmpty()) {
                return@setOnClickListener error.showError(getString(R.string.error_fill_all))
            }

            lifecycleScope.launch {
                confirm.isEnabled = false
                try {
                    val auth = ApiClient.login(emailText, passwordText)
                    UserSession.save(auth.token, auth.user)
                    dialog.dismiss()
                    showLoginState()
                    refreshProfile()
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    confirm.isEnabled = true
                }
            }
        }
        dialog.show()
    }


    // --------------------------------------------------------- PROFILE PICTURE

    private fun choosePicture() {
        pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun onPicturePicked(uri: Uri) {
        lifecycleScope.launch {
            val jpeg = try {
                ProfilePictures.prepare(this@ProfileActivity, uri)
            } catch (e: Exception) {
                Toast.makeText(this@ProfileActivity, R.string.error_picture, Toast.LENGTH_LONG).show()
                return@launch
            }
            val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

            val dialog = registerDialog
            if (dialog != null) {
                // registering: the picture is uploaded once the account is created
                registerPicture = jpeg
                dialog.findViewById<ImageView>(R.id.registerPicture).setImageBitmap(preview)
            } else {
                // logged in: change the profile picture now
                val token = UserSession.token ?: return@launch
                try {
                    ApiClient.uploadPicture(token, jpeg)
                    profilePicture.setImageBitmap(preview)
                } catch (e: ApiException) {
                    Toast.makeText(this@ProfileActivity, e.userMessage(this@ProfileActivity), Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    // --------------------------------------------------------- RESULTS

    // Modal window to publish a new result (e.g. Deadlift - 150 kg)
    private fun showPublishDialog() {
        val dialog = createModal(this, R.layout.dialog_publish_result, this)
        val title = dialog.findViewById<EditText>(R.id.resultTitleInput)
        val value = dialog.findViewById<EditText>(R.id.resultValueInput)
        val error = dialog.findViewById<TextView>(R.id.publishError)
        val confirm = dialog.findViewById<Button>(R.id.publishConfirmButton)

        confirm.setOnClickListener {
            val titleText = title.text.toString().trim()
            val valueText = value.text.toString().trim()
            if (titleText.isEmpty() || valueText.isEmpty()) {
                return@setOnClickListener error.showError(getString(R.string.error_fill_all))
            }
            val token = UserSession.token ?: return@setOnClickListener

            lifecycleScope.launch {
                confirm.isEnabled = false
                try {
                    ApiClient.publishResult(token, titleText, valueText)
                    dialog.dismiss()
                    UserSession.user?.let { showResults(it.id) }
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@ProfileActivity))
                } finally {
                    confirm.isEnabled = true
                }
            }
        }
        dialog.show()
    }
}
