package com.example.gymbro

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

// Draws the page under the status/navigation bars, with light (white) system icons,
// readable on the dark pages of the app
fun ComponentActivity.enableDarkEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
}

// Creates a modal window: rounded card (the given layout), dimmed background and pop-in animation
// (see ModalDialogTheme in styles.xml). Call show() on the result to display it.
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

// Shows an error message in a (normally hidden) TextView
fun TextView.showError(message: String) {
    text = message
    isVisible = true
}
