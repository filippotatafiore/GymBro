package com.example.gymbro.social

import android.widget.ImageView
import com.example.gymbro.R
import com.example.gymbro.gymBroApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Small profile pictures of the users in the lists of the social page (feed, search, followers,
// messages), like an image loader: the pictures are downloaded once and kept in memory by
// SocialRepository. Everything runs on the main thread.
object Avatars {

    // Shows the picture of the user in the image (the default one while it is downloaded, or if the
    // user has none). scope: of the page; the image may be reused by another row in the meantime.
    fun show(image: ImageView, userId: Long, hasPicture: Boolean, scope: CoroutineScope) {
        image.tag = userId
        val pictures = image.context.gymBroApp.container.social
        val picture = pictures.cachedSmallPicture(userId)
        when {
            !hasPicture -> image.setImageResource(R.drawable.default_avatar)
            picture != null -> image.setImageBitmap(picture)
            else -> {
                image.setImageResource(R.drawable.default_avatar)
                scope.launch {
                    val downloaded = pictures.smallPicture(userId) ?: return@launch
                    if (image.tag == userId) image.setImageBitmap(downloaded)
                }
            }
        }
    }
}
