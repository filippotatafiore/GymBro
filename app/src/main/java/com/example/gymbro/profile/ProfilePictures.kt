package com.example.gymbro.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ProfilePictures {

    private const val SIZE = 512  // pixels of the saved picture (square)

    // Image processing of the chosen photo: rotated upright, cut to a centered square,
    // scaled to SIZE x SIZE and compressed as JPEG, ready to be uploaded
    suspend fun prepare(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.Default) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val photo = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // decode a smaller version directly, if the photo is much bigger than needed
            val shortSide = minOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize(maxOf(1, shortSide / SIZE))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        val side = minOf(photo.width, photo.height)
        val square = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)

        ByteArrayOutputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
            it.toByteArray()
        }
    }
}
