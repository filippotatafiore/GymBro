package com.example.gymbro

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GymActivity : ComponentActivity() {

    // Placeholder exercises: they will be loaded from the database later on
    private val exercises = listOf(
        Exercise("Bench Press", "Chest", 4, 8),
        Exercise("Incline Dumbbell Press", "Chest", 3, 10),
        Exercise("Cable Crossover", "Chest", 3, 12),
        Exercise("Dips", "Chest - Triceps", 3, 12),
        Exercise("Triceps Pushdown", "Triceps", 3, 12),
        Exercise("Skull Crushers", "Triceps", 3, 10),
        Exercise("Overhead Triceps Extension", "Triceps", 3, 12),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // link to the layout
        setContentView(R.layout.activity_gym)

        // Back button - return to the home page
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }


        // --------------------------------------------------------- EXERCISE LIST

        val exerciseList = findViewById<RecyclerView>(R.id.exerciseList)
        exerciseList.layoutManager = LinearLayoutManager(this)
        exerciseList.adapter = ExerciseAdapter(exercises)


        // --------------------------------------------------------- NAVBAR

        // Navbar button references
        val homeButton = findViewById<ImageButton>(R.id.homeButton)
        val profileButton = findViewById<ImageButton>(R.id.profileButton)

        // Home Button - return to the home page
        homeButton.setOnClickListener {
            finish()
        }

        // Profile Button - Switch to ProfileActivity
        profileButton.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            startActivity(intent)
        }
    }
}
