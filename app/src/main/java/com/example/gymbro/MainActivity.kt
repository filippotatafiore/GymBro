package com.example.gymbro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.gymbro.ui.theme.GymBroTheme


import android.widget.ImageButton
import android.content.Intent
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button


class MainActivity : ComponentActivity() {

    // Modal window to choose the type of training (null when not shown)
    private var trainingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // link to the layout
        setContentView(R.layout.activity_main)


        /*
        setContent {
            GymBroTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        */


        // --------------------------------------------------------- START TRAINING

        val startTrainingButton = findViewById<Button>(R.id.new_session_Button)
        startTrainingButton.setOnClickListener {
            showTrainingTypeDialog()
        }


        // --------------------------------------------------------- NAVBAR

        // Navbar button references
        val homeButton = findViewById<ImageButton>(R.id.homeButton)
        val profileButton = findViewById<ImageButton>(R.id.profileButton)

        homeButton.setOnClickListener {
            // Do nothing or refresh current activity
        }

        // Profile Button - Switch to ProfileActivity
        profileButton.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            startActivity(intent)
            //overridePendingTransition(0, 0) // Removes animation for seamless transition
        }

    }

    // Shows the modal window with the "Gym" and "Run" buttons.
    // Its look is in res/layout/dialog_training_type.xml, its animation in res/anim/modal_*.xml
    private fun showTrainingTypeDialog() {
        val dialog = Dialog(this, R.style.ModalDialogTheme)
        dialog.setContentView(R.layout.dialog_training_type)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Gym button - Switch to GymActivity (exercise list)
        dialog.findViewById<View>(R.id.gymOption).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, GymActivity::class.java))
        }

        // Run button - Switch to RunActivity (map)
        dialog.findViewById<View>(R.id.runOption).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, RunActivity::class.java))
        }

        dialog.setOnDismissListener { trainingDialog = null }
        trainingDialog = dialog
        dialog.show()
    }

    override fun onDestroy() {
        // Close the modal window if the activity is destroyed (e.g. screen rotation) to avoid leaking it
        trainingDialog?.dismiss()
        super.onDestroy()
    }
}

/*
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Ciao $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    GymBroTheme {
        Greeting("Android")
    }
}
*/
