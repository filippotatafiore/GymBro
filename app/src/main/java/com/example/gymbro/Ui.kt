package com.example.gymbro

import android.app.Dialog
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

// A color of the theme of the page, e.g. R.attr.accentColor: the favorite color of the user
// (res/values/attrs.xml). The context must be a page or a view of the page (not the application).
@ColorInt
fun Context.themeColor(@AttrRes attribute: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attribute, value, true)
    return value.data
}

// Creates a modal window: rounded card (the given layout), dimmed background and pop-in animation
// (see ModalDialogTheme in res/values/themes.xml). Call show() on the result to display it.
// The window is closed automatically when [owner] is destroyed (e.g. when the screen rotates).
fun createModal(
    context: Context,
    @LayoutRes layout: Int,
    owner: LifecycleOwner,
    onDismiss: () -> Unit = {}
): Dialog {
    val dialog = Dialog(context, R.style.ModalDialogTheme)
    dialog.setContentView(layout)
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    val closeOnDestroy = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
    }
    owner.lifecycle.addObserver(closeOnDestroy)
    dialog.setOnDismissListener {
        owner.lifecycle.removeObserver(closeOnDestroy)
        onDismiss()
    }
    return dialog
}

// Modal window asking to confirm an action that deletes something: onConfirm is called only if
// the user presses the red button
fun showConfirmModal(
    context: Context,
    owner: LifecycleOwner,
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes confirmText: Int,
    onConfirm: () -> Unit
) {
    val dialog = createModal(context, R.layout.dialog_confirm, owner)
    dialog.findViewById<TextView>(R.id.confirmTitle).setText(title)
    dialog.findViewById<TextView>(R.id.confirmMessage).setText(message)

    val confirm = dialog.findViewById<Button>(R.id.confirmButton)
    confirm.setText(confirmText)
    confirm.setOnClickListener {
        dialog.dismiss()
        onConfirm()
    }
    dialog.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
    dialog.show()
}

// Modal window with a question and two answers (res/layout/dialog_choice.xml): the first one is
// the blue button. The window closes, then onFirst / onSecond is called.
fun showChoiceModal(
    context: Context,
    owner: LifecycleOwner,
    title: String,
    message: String,
    first: String,
    second: String,
    onFirst: () -> Unit,
    onSecond: () -> Unit = {}
) {
    val dialog = createModal(context, R.layout.dialog_choice, owner)
    dialog.findViewById<TextView>(R.id.choiceTitle).text = title
    dialog.findViewById<TextView>(R.id.choiceMessage).text = message
    dialog.findViewById<Button>(R.id.choiceFirstButton).apply {
        text = first
        setOnClickListener {
            dialog.dismiss()
            onFirst()
        }
    }
    dialog.findViewById<Button>(R.id.choiceSecondButton).apply {
        text = second
        setOnClickListener {
            dialog.dismiss()
            onSecond()
        }
    }
    dialog.show()
}

// Shows an error message in a (normally hidden) TextView
fun TextView.showError(message: String) {
    text = message
    isVisible = true
}

// The page (root) ends above the keyboard while it is open, otherwise above the navigation bar of
// the phone. bottomNav: the bottom strip of the page (it already leaves room for the navigation
// bar), hidden while the keyboard is open. The pages are drawn under the system bars (ThemedActivity).
fun keepAboveKeyboard(root: View, bottomNav: View? = null) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
        bottomNav?.isVisible = !keyboardOpen
        val bottom = when {
            keyboardOpen -> insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            bottomNav != null -> 0
            else -> insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        view.updatePadding(bottom = bottom)
        insets
    }
}
