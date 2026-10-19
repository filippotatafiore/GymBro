package com.example.gymbro

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.example.gymbro.network.userMessage
import com.example.gymbro.profile.PictureChooser
import com.example.gymbro.viewmodel.WelcomeViewModel

// First page of the app when nobody is logged in: register or log in (WelcomeViewModel).
// It has no bottom strip: the rest of the app can be used only after the login.
class WelcomeActivity : ThemedActivity() {

    private val viewModel: WelcomeViewModel by viewModels { WelcomeViewModel.Factory }

    // The registration or login window open now: its error text and its confirm button
    private var dialog: Dialog? = null
    private var dialogError: TextView? = null
    private var dialogConfirm: Button? = null

    // The profile picture of the registration: taken with the camera or chosen from the gallery
    private val pictureChooser = PictureChooser(this) { jpeg -> onPicturePicked(jpeg) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already logged in (e.g. page reopened from the recent apps): straight into the app
        if (viewModel.isLoggedIn) {
            openApp()
            return
        }

        // link to the layout
        setContentView(R.layout.activity_welcome)

        findViewById<Button>(R.id.register_Button).setOnClickListener { showRegisterDialog() }
        findViewById<Button>(R.id.loginButton).setOnClickListener { showLoginDialog() }

        observe(viewModel.busy) { busy -> dialogConfirm?.isEnabled = !busy }
        onEvents(viewModel.events) { event ->
            when (event) {
                WelcomeViewModel.Event.LoggedIn -> {
                    dialog?.dismiss()
                    openApp()
                }
                is WelcomeViewModel.Event.Failed -> dialogError?.showError(event.error.userMessage(this))
                is WelcomeViewModel.Event.PictureNotUploaded ->
                    Toast.makeText(this, event.error.userMessage(this), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Logged in: opens the home page and closes this page (the back button does not return here)
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }


    // --------------------------------------------------------- REGISTRATION / LOGIN

    // A window of this page: its error text and confirm button receive the answers of the server
    private fun showDialog(layout: Int, errorId: Int, confirmId: Int, onDismiss: () -> Unit = {}): Dialog {
        lateinit var shown: Dialog
        shown = createModal(this, layout, this, onDismiss = {
            if (dialog === shown) {
                dialog = null
                dialogError = null
                dialogConfirm = null
            }
            onDismiss()
        })
        dialog = shown
        dialogError = shown.findViewById(errorId)
        dialogConfirm = shown.findViewById<Button>(confirmId).apply { isEnabled = !viewModel.busy.value }
        return shown
    }

    private fun showRegisterDialog() {
        val dialog = showDialog(R.layout.dialog_register, R.id.registerError, R.id.registerConfirmButton) {
            viewModel.registerPicture = null
        }
        val username = dialog.findViewById<EditText>(R.id.registerUsername)
        val email = dialog.findViewById<EditText>(R.id.registerEmail)
        val password = dialog.findViewById<EditText>(R.id.registerPassword)
        val error = dialog.findViewById<TextView>(R.id.registerError)

        // Tap the picture to take a photo or choose one (optional)
        dialog.findViewById<ImageView>(R.id.registerPicture).setOnClickListener { pictureChooser.show() }

        dialog.findViewById<Button>(R.id.registerConfirmButton).setOnClickListener {
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
            viewModel.register(usernameText, emailText, passwordText)
        }
        dialog.show()
    }

    private fun showLoginDialog() {
        val dialog = showDialog(R.layout.dialog_login, R.id.loginError, R.id.loginConfirmButton)
        val email = dialog.findViewById<EditText>(R.id.loginEmail)
        val password = dialog.findViewById<EditText>(R.id.loginPassword)
        val error = dialog.findViewById<TextView>(R.id.loginError)

        dialog.findViewById<Button>(R.id.loginConfirmButton).setOnClickListener {
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString()
            if (emailText.isEmpty() || passwordText.isEmpty()) {
                return@setOnClickListener error.showError(getString(R.string.error_fill_all))
            }
            viewModel.login(emailText, passwordText)
        }
        dialog.show()
    }

    // The photo taken or chosen in the registration window (already prepared, see PictureChooser):
    // shown in the window, uploaded after registering
    private fun onPicturePicked(jpeg: ByteArray) {
        val dialog = dialog ?: return
        val picture = dialog.findViewById<ImageView>(R.id.registerPicture) ?: return
        viewModel.registerPicture = jpeg
        picture.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
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
