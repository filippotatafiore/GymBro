package com.example.gymbro.repository

import androidx.annotation.StringRes
import com.example.gymbro.R
import com.example.gymbro.network.ApiException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

// Messages for the user about the work done in background, which completes also if the page that
// started it is closed meanwhile (e.g. a session saved and published, a change saved only on this
// phone): GymBroApp shows them as toasts, whatever page is open.
class Notices {

    // text: a message of the app; error: the message of the error (with a text too, the text
    // contains it, e.g. "The plan was not updated: ..."); long: shown for longer
    class Notice(@StringRes val text: Int?, val error: ApiException? = null, val long: Boolean = false)

    private val notices = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    val shown: SharedFlow<Notice> = notices

    // The last "saved only on this phone" message (see savedOffline)
    private var lastSavedOffline = 0L

    fun show(@StringRes text: Int, long: Boolean = false) {
        notices.tryEmit(Notice(text, long = long))
    }

    fun showError(error: ApiException, @StringRes text: Int? = null) {
        notices.tryEmit(Notice(text, error, long = true))
    }

    // A change saved only on this phone because the server is not reachable: it will be sent at
    // the next sync. At most once a minute, so that many changes made offline (e.g. a whole
    // workout plan) do not pile up messages.
    fun savedOffline() {
        val now = System.currentTimeMillis()
        if (now - lastSavedOffline < MIN_SAVED_OFFLINE_INTERVAL_MS) return
        lastSavedOffline = now
        show(R.string.saved_offline, long = true)
    }

    companion object {
        private const val MIN_SAVED_OFFLINE_INTERVAL_MS = 60_000L
    }
}
