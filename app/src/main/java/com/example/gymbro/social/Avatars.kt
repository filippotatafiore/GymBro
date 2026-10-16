package com.example.gymbro.social

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import com.example.gymbro.R
import com.example.gymbro.gymBroApp
import com.example.gymbro.network.ApiClient
import com.example.gymbro.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

// Small profile pictures of the users in the lists of the social page (feed, search, followers,
// messages): downloaded once, a quarter of their side (128 px), and kept in memory while the app is
// open. Everything runs on the main thread: no locks needed.
object Avatars {

    private const val SAMPLE_SIZE = 4

    private val pictures = LruCache<Long, Bitmap>(120)
    // downloads in progress: two rows of the same user wait for the same download
    private val downloads = mutableMapOf<Long, Deferred<Bitmap?>>()

    // Shows the picture of the user in the image (the default one while it is downloaded, or if the
    // user has none). scope: of the page; the image may be reused by another row in the meantime.
    fun show(image: ImageView, userId: Long, hasPicture: Boolean, scope: CoroutineScope) {
        image.tag = userId
        val picture = pictures.get(userId)
        when {
            !hasPicture -> image.setImageResource(R.drawable.default_avatar)
            picture != null -> image.setImageBitmap(picture)
            else -> {
                image.setImageResource(R.drawable.default_avatar)
                scope.launch {
                    val downloaded = download(image, userId) ?: return@launch
                    if (image.tag == userId) image.setImageBitmap(downloaded)
                }
            }
        }
    }

    // The download continues also if the page is closed (applicationScope): the picture is ready
    // for the next page
    private suspend fun download(image: ImageView, userId: Long): Bitmap? {
        val download = downloads.getOrPut(userId) {
            image.context.gymBroApp.applicationScope.async {
                try {
                    ApiClient.getPicture(userId, SAMPLE_SIZE)?.also { pictures.put(userId, it) }
                } catch (e: ApiException) {
                    null
                } finally {
                    downloads.remove(userId)
                }
            }
        }
        return download.await()
    }

    // The user changed their picture: the old one is not shown anymore
    fun forget(userId: Long) {
        pictures.remove(userId)
    }
}
