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
import java.util.Stack

class ProfileActivity : ComponentActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var menuContainer: FrameLayout
    private val menuHistory = Stack<Int>() // Stack to store previous menus

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

        // Function to load profile menu and store previous state
        fun loadProfileMenu(layoutId: Int, storePrevious: Boolean = true) {
            if (storePrevious) menuHistory.push(layoutId) // Store previous menu

            menuContainer.removeAllViews()
            layoutInflater.inflate(layoutId, menuContainer, true)


            // Handle the settings button (if present in the current layout)
            menuContainer.findViewById<Button>(R.id.settingsButton)?.setOnClickListener {
                loadProfileMenu(R.layout.settings) // Open settings menu
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