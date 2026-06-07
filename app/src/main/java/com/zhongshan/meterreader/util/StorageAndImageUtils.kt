package com.zhongshan.meterreader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object StorageAndImageUtils {

    suspend fun loadAndFixExifMatrixSecurely(context: Context, imageUri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val originalBitmap = BitmapFactory.decodeStream(stream) ?: return@withContext null

                context.contentResolver.openInputStream(imageUri)?.use { exifStream ->
                    val exif = ExifInterface(exifStream)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )

                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        else -> return@withContext originalBitmap
                    }

                    val correctedBitmap = Bitmap.createBitmap(
                        originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
                    )
                    originalBitmap.recycle()
                    return@withContext correctedBitmap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun clearOldCacheFiles(cacheDir: File) = withContext(Dispatchers.IO) {
        try {
            val thresholdTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            cacheDir.listFiles()?.forEach { file ->
                if ((file.name.startsWith("IMG_") || file.name.startsWith("CROP_")) && file.lastModified() < thresholdTime) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}