package com.example.gymbro.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.gymbro.R
import com.example.gymbro.createModal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The profile picture: a photo taken now with the camera, or one chosen from the gallery (a window
// asks which). The photo is prepared for the upload (ProfilePictures.prepare: image processing) and
// passed to onPicture. Neither the camera nor the gallery needs a permission: the camera app of the
// phone takes the photo and saves it in a file of this app, shared with it only for that photo
// (FileProvider, res/xml/file_paths.xml); the photo is deleted once prepared.
// Created as a property of the page: it must register the answers before the page starts.
class PictureChooser(
    private val activity: ComponentActivity,
    private val onPicture: (ByteArray) -> Unit
) {

    // Where the camera app saves the photo (in the cache of the app)
    private val photoFile: File
        get() = File(activity.cacheDir, "camera").apply { mkdirs() }.resolve("profile_photo.jpg")

    private val photoUri: Uri
        get() = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", photoFile)

    // true: the photo was taken and saved (also after the page was recreated meanwhile: same file)
    private val takePhoto = activity.registerForActivityResult(FrontCameraPicture()) { saved ->
        if (saved) use(photoUri, fromCamera = true)
    }

    // The system photo picker
    private val pickPhoto = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) use(uri, fromCamera = false)
    }

    // Window "Take a photo" / "Choose from gallery" (res/layout/dialog_picture_choice.xml)
    fun show() {
        val dialog = createModal(activity, R.layout.dialog_picture_choice, activity)
        dialog.findViewById<View>(R.id.takePhotoOption).setOnClickListener {
            dialog.dismiss()
            openCamera()
        }
        dialog.findViewById<View>(R.id.choosePhotoOption).setOnClickListener {
            dialog.dismiss()
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        dialog.show()
    }

    private fun openCamera() {
        try {
            takePhoto.launch(photoUri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    // Image processing in background (rotated upright, square, 512 x 512, JPEG), then onPicture
    private fun use(uri: Uri, fromCamera: Boolean) {
        activity.lifecycleScope.launch {
            val jpeg = try {
                ProfilePictures.prepare(activity, uri)
            } catch (e: Exception) {
                Toast.makeText(activity, R.string.error_picture, Toast.LENGTH_LONG).show()
                null
            } finally {
                // the photo of the camera is not kept on the phone
                if (fromCamera) withContext(Dispatchers.IO) { photoFile.delete() }
            }
            jpeg?.let(onPicture)
        }
    }

    // The camera app, asked to start with the front camera (a selfie). These extras are not part of
    // Android: many camera apps follow them, the others start as usual (the user can switch camera).
    private class FrontCameraPicture : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri): Intent =
            super.createIntent(context, input)
                .putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
    }
}
