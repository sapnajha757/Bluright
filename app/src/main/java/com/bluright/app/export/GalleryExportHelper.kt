package com.bluright.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * Utility helper to register exported privacy videos directly with Android's MediaStore
 * so that they immediately appear in the user's phone Gallery (under Movies/Bluright),
 * and provide convenient Play and Share actions.
 */
object GalleryExportHelper {
    private const val TAG = "GalleryExportHelper"

    /**
     * Copies the recorded privacy MP4 video to the device's public Gallery (Movies/Bluright).
     * On Android 10+ (API 29+), uses Scoped Storage (zero runtime permissions needed).
     */
    fun saveToGallery(context: Context, videoFile: File): Uri? {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            Log.e(TAG, "Cannot save video to gallery: source file missing or empty")
            return null
        }

        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Bluright")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collectionUri, contentValues) ?: return null

            resolver.openOutputStream(itemUri)?.use { outStream ->
                FileInputStream(videoFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            Log.i(TAG, "Successfully exported video to phone Gallery: $itemUri (${videoFile.name})")
            return itemUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to gallery: ${e.message}", e)
            return null
        }
    }

    /**
     * Plays the video in the default system video player.
     */
    fun playVideo(context: Context, videoFile: File, mediaStoreUri: Uri? = null) {
        val uri = mediaStoreUri ?: getSharableUri(context, videoFile) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open video player: ${e.message}")
        }
    }

    /**
     * Shares the video file via the Android system share sheet (WhatsApp, Drive, etc.).
     */
    fun shareVideo(context: Context, videoFile: File, mediaStoreUri: Uri? = null) {
        val uri = mediaStoreUri ?: getSharableUri(context, videoFile) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Bluright Privacy Video: ${videoFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Privacy Video").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch share chooser: ${e.message}")
        }
    }

    fun getSharableUri(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Uri.fromFile(file)
        }
    }
}
