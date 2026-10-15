package com.example.gymbro

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity

// Base of all the pages of the app: the favorite color of the user (accent) and the light / dark
// theme (see Appearance). The page is drawn under the status and navigation bars, with their icons
// readable on the theme (white on the dark one, dark on the light one).
open class ThemedActivity : FragmentActivity() {

    // The look the page was created with: if it changes, the page is recreated when shown again
    private var appearanceVersion = 0

    // Light / dark chosen by the user, also for a page created while Android is still applying it
    // to the app (e.g. the first page after the start, see Appearance.init)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val night = if (Appearance.theme == AppTheme.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val uiMode = newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
        applyOverrideConfiguration(Configuration().apply { this.uiMode = uiMode })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // before the views (and the fragments restored by super.onCreate) are created
        theme.applyStyle(Appearance.accent.overlay, true)
        appearanceVersion = Appearance.version
        super.onCreate(savedInstanceState)

        val barStyle =
            if (Appearance.isDark(this)) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)

        // The pages place their views around the bars themselves: the root of the window never
        // moves them. Needed by the first versions of Android 15, that forget it when a page is
        // recreated (e.g. after a change of the look) and push the whole page inside the bars.
        (findViewById<View>(android.R.id.content).parent as? View)
            ?.setOnApplyWindowInsetsListener { _, insets -> insets }
    }

    override fun onResume() {
        super.onResume()
        if (appearanceVersion != Appearance.version) recreate()
    }
}
