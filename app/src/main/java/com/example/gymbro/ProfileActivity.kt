package com.example.gymbro

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var menuContainer: FrameLayout

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


        // --------------------------------------------------------- SLIDING MENU

        // Initialize DrawerLayout and NavigationView
        drawerLayout = findViewById(R.id.drawer_layout)
        menuButton = findViewById(R.id.menuButton)
        menuContainer = findViewById(R.id.menu_container)

        // Open the menu when the button is clicked
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

        // Function to load profile menu and set up its buttons
        fun loadProfileMenu() {
            menuContainer.removeAllViews()
            layoutInflater.inflate(R.layout.profile_menu, menuContainer, true)

            // bind the settings button and its click listener
            val settingsButton = menuContainer.findViewById<Button>(R.id.settingsButton)
            settingsButton.setOnClickListener {
                val settingsView = layoutInflater.inflate(R.layout.settings, menuContainer, false)
                menuContainer.removeAllViews()
                menuContainer.addView(settingsView)
            }
        }

        // Load the initial profile menu
        loadProfileMenu()

        // Listen for drawer close event
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {}

            override fun onDrawerClosed(drawerView: View) {
                loadProfileMenu()
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })




        // --------------------------------------------------------- NAVBAR

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