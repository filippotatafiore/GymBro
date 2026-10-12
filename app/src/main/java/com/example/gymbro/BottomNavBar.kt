package com.example.gymbro

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.ImageButton
import com.example.gymbro.session.ActiveSession

// Bottom strip shared by all the pages (res/layout/bottom_nav.xml):
// - moves between the pages
// - makes the home icon shine while a training session is in progress
class BottomNavBar(private val activity: Activity, private val currentPage: Page) {

    enum class Page { HOME, PROFILE }

    private val homeButton: ImageButton = activity.findViewById(R.id.homeButton)
    private var glowAnimation: ObjectAnimator? = null

    init {
        homeButton.setOnClickListener { open(Page.HOME) }
        activity.findViewById<ImageButton>(R.id.profileButton).setOnClickListener { open(Page.PROFILE) }
    }

    private fun open(page: Page) {
        if (page == currentPage) return

        val target = when (page) {
            Page.HOME -> MainActivity::class.java
            Page.PROFILE -> ProfileActivity::class.java
        }
        // REORDER_TO_FRONT: if the page is already open it is brought back,
        // instead of piling up a new copy of it every time
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val fade = ActivityOptions.makeCustomAnimation(activity, android.R.anim.fade_in, android.R.anim.fade_out)
        activity.startActivity(intent, fade.toBundle())
    }

    // Updates the home icon: shining (pulsing while running, steady while paused) if a session
    // is in progress, normal otherwise. Call it in onResume() and when the session changes.
    fun refresh() {
        stopGlow()

        if (!ActiveSession.isActive) {
            homeButton.background = null
            homeButton.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.white))
            return
        }

        val glow = activity.getDrawable(R.drawable.nav_glow)!!.mutate()
        homeButton.background = glow
        homeButton.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.glowBlue))

        if (!ActiveSession.isPaused) {
            glowAnimation = ObjectAnimator.ofInt(glow, "alpha", 255, 60).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    // Stops the pulsing animation: call it in onPause(), when the page is not visible
    fun stopGlow() {
        glowAnimation?.cancel()
        glowAnimation = null
    }
}
