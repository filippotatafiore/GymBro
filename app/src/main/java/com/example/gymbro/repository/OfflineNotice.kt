package com.example.gymbro.repository

import android.content.Context
import android.widget.Toast
import com.example.gymbro.R

// Message shown when a change is saved only on this phone because the server is not reachable:
// it will be sent at the next sync. At most once a minute, so that many changes made offline
// (e.g. a whole workout plan) do not pile up messages.
object OfflineNotice {

    private const val MIN_INTERVAL_MS = 60_000L
    private var lastShown = 0L

    fun show(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastShown < MIN_INTERVAL_MS) return
        lastShown = now
        Toast.makeText(context.applicationContext, R.string.saved_offline, Toast.LENGTH_LONG).show()
    }
}
