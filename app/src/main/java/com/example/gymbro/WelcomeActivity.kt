package com.example.gymbro

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import com.example.gymbro.network.UserSession
import com.example.gymbro.network.userMessage
import com.example.gymbro.profile.ProfilePictures
import kotlinx.coroutines.launch

// First page of the app when nobody is logged in: register or log in.
// It has no bottom strip: the rest of the app can be used only after the login.
class WelcomeActivity : ComponentActivity() {

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

        // Already logged in (e.g. page reopened from the recent apps): straight into the app
        if (UserSession.isLoggedIn) {
            openApp()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_welcome)

        findViewById<Button>(R.id.register_Button).setOnClickListener { showRegisterDialog() }
        findViewById<Button>(R.id.loginButton).setOnClickListener { showLoginDialog() }
    }

    // Logged in: opens the home page and closes this page (the back button does not return here)
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
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

        // Tap the picture to choose a photo (optional)
        dialog.findViewById<ImageView>(R.id.registerPicture).setOnClickListener {
            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

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
                            Toast.makeText(this@WelcomeActivity, e.userMessage(this@WelcomeActivity), Toast.LENGTH_LONG).show()
                        }
                    }

                    dialog.dismiss()
                    openApp()
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@WelcomeActivity))
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
                    openApp()
                } catch (e: ApiException) {
                    error.showError(e.userMessage(this@WelcomeActivity))
                } finally {
                    confirm.isEnabled = true
                }
            }
        }
        dialog.show()
    }

    // The photo chosen in the registration window: shown in the window, uploaded after registering
    private fun onPicturePicked(uri: Uri) {
        lifecycleScope.launch {
            val jpeg = try {
                ProfilePictures.prepare(this@WelcomeActivity, uri)
            } catch (e: Exception) {
                Toast.makeText(this@WelcomeActivity, R.string.error_picture, Toast.LENGTH_LONG).show()
                return@launch
            }
            val dialog = registerDialog ?: return@launch
            registerPicture = jpeg
            dialog.findViewById<ImageView>(R.id.registerPicture)
                .setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        }
    }

    companion object {
        // Opens the welcome page, closing all the other pages of the app (e.g. after the logout)
        fun open(activity: Activity) {
            val intent = Intent(activity, WelcomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(intent)
        }
    }
}
