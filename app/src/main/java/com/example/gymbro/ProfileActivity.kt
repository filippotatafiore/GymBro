package com.example.gymbro

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import com.google.android.material.navigation.NavigationView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat

class ProfileActivity : ComponentActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // link to the layout
        setContentView(R.layout.activity_profile)


        /*
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        */



        // Initialize DrawerLayout and NavigationView
        drawerLayout = findViewById(R.id.drawer_layout)
        menuButton = findViewById(R.id.menuButton)

        // Open the menu when the button is clicked
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }



        // Navbar button references
        val homeButton = findViewById<ImageButton>(R.id.homeButton)
        val profileButton = findViewById<ImageButton>(R.id.profileButton)

        homeButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            //overridePendingTransition(0, 0) // Removes animation for seamless transition
        }

        // Profile Button - Switch to ProfileActivity
        profileButton.setOnClickListener {
            // Do nothing or refresh current activity
        }


    }
}