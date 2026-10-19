package com.example.gymbro

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.ImageButton
import com.example.gymbro.session.SessionStatus

// Bottom strip shared by all the pages (res/layout/bottom_nav.xml):
// - moves between the pages
// - shows the page where the user is: icon in the favorite color, with a short bar under it
// - makes the home icon shine (in the favorite color) while a training session is in progress
class BottomNavBar(private val activity: Activity, private val currentPage: Page) {

    enum class Page { HOME, CHAT, SOCIAL, PROFILE }

    private val homeButton: ImageButton = activity.findViewById(R.id.homeButton)
    private var glowAnimation: ObjectAnimator? = null

    init {
        val buttons = mapOf(
            Page.HOME to homeButton,
            Page.CHAT to activity.findViewById(R.id.chatButton),
            Page.SOCIAL to activity.findViewById(R.id.socialButton),
            Page.PROFILE to activity.findViewById<ImageButton>(R.id.profileButton)
        )
        buttons.forEach { (page, button) -> button.setOnClickListener { open(page) } }

        // the page shown
        buttons.getValue(currentPage).apply {
            isSelected = true
            foreground = activity.getDrawable(R.drawable.nav_selected_indicator)
            imageTintList = ColorStateList.valueOf(activity.themeColor(R.attr.accentTextColor))
        }
    }

    private fun open(page: Page) {
        if (page == currentPage) return

        val target = when (page) {
            Page.HOME -> MainActivity::class.java
            Page.CHAT -> ChatActivity::class.java
            Page.SOCIAL -> SocialActivity::class.java
            Page.PROFILE -> ProfileActivity::class.java
        }
        // REORDER_TO_FRONT: if the page is already open it is brought back,
        // instead of piling up a new copy of it every time
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val fade = ActivityOptions.makeCustomAnimation(activity, android.R.anim.fade_in, android.R.anim.fade_out)
        activity.startActivity(intent, fade.toBundle())
    }

    // Updates the home icon: shining (pulsing while running, steady while paused) if a session
    // is in progress, normal otherwise. Call it in onResume() and when the session changes, with
    // the session in progress given by the ViewModel of the page.
    fun refresh(session: SessionStatus) {
        stopGlow()

        if (!session.isActive) {
            homeButton.background = null
            // in the favorite color if the home is the page shown
            homeButton.imageTintList = ColorStateList.valueOf(
                if (currentPage == Page.HOME) activity.themeColor(R.attr.accentTextColor) else activity.getColor(R.color.icon)
            )
            return
        }

        val glow = glow()
        homeButton.background = glow
        homeButton.imageTintList = ColorStateList.valueOf(activity.themeColor(R.attr.accentTextColor))

        if (!session.paused) {
            glowAnimation = ObjectAnimator.ofInt(glow, "alpha", 255, 60).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    // Light behind the home icon: a round glow of the favorite color (50 dp, in the middle of the
    // button), fading towards the border
    private fun glow(): LayerDrawable {
        val color = activity.themeColor(R.attr.accentColor)
        fun withAlpha(alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
        val size = (50 * activity.resources.displayMetrics.density).toInt()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = size / 2f
            colors = intArrayOf(withAlpha(0xA0), withAlpha(0x40), withAlpha(0x00))
        }
        return LayerDrawable(arrayOf(circle)).apply {
            setLayerSize(0, size, size)
            setLayerGravity(0, Gravity.CENTER)
        }
    }

    // Stops the pulsing animation: call it in onPause(), when the page is not visible
    fun stopGlow() {
        glowAnimation?.cancel()
        glowAnimation = null
    }
}
