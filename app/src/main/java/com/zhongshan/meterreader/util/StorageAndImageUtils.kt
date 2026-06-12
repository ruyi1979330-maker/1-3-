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
import java.io.FileOutputStream

object StorageAndImageUtils {

    /**
     * 加载图片并自动纠正 EXIF 旋转。
     *
     * Bug Fix（本轮，日志证据）：
     *   日志显示用户通过微信(com.tencent.mm)相册分享图片给 APP，
     *   而非通过 APP 内置的 GetMultipleContents 选图器。
     *   微信生成的 content:// URI 属于微信进程的临时授权 URI，
     *   当微信进程被系统调度到后台或内存回收后，
     *   APP 再次 openInputStream(uri) 时可能失败（返回 null 或抛异常），
     *   导致 Bitmap 解码失败 → OCRFacade 返回空 Map → "未识别到有效数据"。
     *
     *   同样的问题也出现在：
     *   - 荣耀相册(PhotoPicker)：日志中 16276:PhotoPicker 进程被 lmkd 杀死
     *   - 任何通过 Intent.ACTION_SEND 分享过来的图片 URI
     *
     *   修复方案：
     *   在第一次 openInputStream 成功后，立即将图片内容复制到 APP 私有缓存目录。
     *   后续所有操作（EXIF 读取、Bitmap 解码）都从本地缓存文件进行，
     *   与原始 URI 的进程完全解耦，不受跨进程 URI 权限影响。
     */
    suspend fun loadAndFixExifMatrixSecurely(context: Context, imageUri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // 第一步：将 URI 内容复制到 APP 私有缓存（解决跨进程 URI 权限问题）
                val cacheFile = copyUriToCache(context, imageUri)
                    ?: return@withContext null   // 复制失败说明 URI 本身无效

                // 第二步：从本地缓存文件解码 Bitmap（稳定，不依赖原 URI 的进程）
                val originalBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    ?: return@withContext null

                // 第三步：读取 EXIF 方向（从本地文件读，稳定）
                val orientation = try {
                    ExifInterface(cacheFile.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (e: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }

                // 临时缓存用完即删（减少存储占用）
                cacheFile.delete()

                // 第四步：按需旋转
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> return@withContext originalBitmap
                }

                val matrix = Matrix().apply { postRotate(degrees) }
                val correctedBitmap = Bitmap.createBitmap(
                    originalBitmap, 0, 0,
                    originalBitmap.width, originalBitmap.height,
                    matrix, true
                )
                originalBitmap.recycle()
                correctedBitmap

            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * 将任意 content:// URI 的内容复制到 APP 私有缓存目录。
     * 返回缓存文件，失败返回 null。
     *
     * 这是解决"微信/相册分享 URI 跨进程失效"的核心方法。
     */
    private fun copyUriToCache(context: Context, uri: Uri): File? {
        return try {
            val cacheFile = File(context.cacheDir, "ocr_tmp_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(cacheFile).use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 65536)
                }
            } ?: return null  // openInputStream 返回 null 说明 URI 无效
            if (cacheFile.length() > 0) cacheFile else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun clearOldCacheFiles(cacheDir: File) = withContext(Dispatchers.IO) {
        try {
            val thresholdTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            cacheDir.listFiles()?.forEach { file ->
                if ((file.name.startsWith("IMG_") ||
                     file.name.startsWith("CROP_") ||
                     file.name.startsWith("ocr_tmp_")) &&
                    file.lastModified() < thresholdTime) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
