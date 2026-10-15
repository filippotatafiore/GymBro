package com.example.gymbro

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.edit

// Favorite color of the user, used as accent in the whole app. `key` = how it is saved on the server.
// overlay: the style with its colors (res/values/themes.xml); swatch / onSwatch: the color shown in
// "Customize account" and the color of a check on it.
enum class Accent(
    val key: String,
    @StyleRes val overlay: Int,
    @ColorRes val swatch: Int,
    @ColorRes val onSwatch: Int,
    @StringRes val label: Int
) {
    BLUE("blue", R.style.Accent_Blue, R.color.accent_blue, R.color.accent_blue_on, R.string.accent_blue),
    VIOLET("violet", R.style.Accent_Violet, R.color.accent_violet, R.color.accent_violet_on, R.string.accent_violet),
    PINK("pink", R.style.Accent_Pink, R.color.accent_pink, R.color.accent_pink_on, R.string.accent_pink),
    RED("red", R.style.Accent_Red, R.color.accent_red, R.color.accent_red_on, R.string.accent_red),
    ORANGE("orange", R.style.Accent_Orange, R.color.accent_orange, R.color.accent_orange_on, R.string.accent_orange),
    AMBER("amber", R.style.Accent_Amber, R.color.accent_amber, R.color.accent_amber_on, R.string.accent_amber),
    GREEN("green", R.style.Accent_Green, R.color.accent_green, R.color.accent_green_on, R.string.accent_green),
    TEAL("teal", R.style.Accent_Teal, R.color.accent_teal, R.color.accent_teal_on, R.string.accent_teal);

    companion object {
        // The color of the new accounts
        val DEFAULT = BLUE

        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

// Light or dark theme of the app
enum class AppTheme(val key: String) {
    DARK("dark"),
    LIGHT("light");

    companion object {
        val DEFAULT = DARK

        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

// The look of the app: theme (light / dark) and favorite color, chosen by the user in
// "Customize account" and saved in their account (the same on every phone). The last one used is
// also saved on this phone, so that the app starts with it, also before the login.
// - light / dark: Android itself applies it to the app (UiModeManager): the colors of
//   res/values-night are used in the dark theme, and the pages are recreated when it changes
//   (every page also applies it when it is created, see ThemedActivity)
// - favorite color: every page applies its style when it is created (ThemedActivity); after a
//   change the pages already open are recreated when they are shown again (version)
object Appearance {

    private const val KEY_ACCENT = "accent"
    private const val KEY_THEME = "theme"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    // Increased at every change of the favorite color or theme
    var version = 0
        private set

    val accent: Accent
        get() = Accent.of(prefs.getString(KEY_ACCENT, null))

    val theme: AppTheme
        get() = AppTheme.of(prefs.getString(KEY_THEME, null))

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        applyTheme()
    }

    // The look of the logged user (from their account): applied if it is different
    fun set(accent: Accent, theme: AppTheme) {
        if (accent == this.accent && theme == this.theme) return
        val themeChanged = theme != this.theme
        prefs.edit(commit = true) {
            putString(KEY_ACCENT, accent.key)
            putString(KEY_THEME, theme.key)
        }
        version++
        if (themeChanged) applyTheme()
    }

    // Light / dark for the whole app (saved by Android, also when the app is closed)
    private fun applyTheme() {
        val mode = if (theme == AppTheme.DARK) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        appContext.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    // The theme actually used by a page (its configuration)
    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
