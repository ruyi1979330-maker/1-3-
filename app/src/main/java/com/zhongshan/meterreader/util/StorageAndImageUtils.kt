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

    /**
     * 加载图片并根据 EXIF 方向自动纠正旋转。
     *
     * Bug Fix 2（内存泄漏）：
     * 原代码在 EXIF 读取路径中存在 originalBitmap 泄漏：
     *
     * 原代码结构：
     *   context.contentResolver.openInputStream(imageUri)?.use { stream ->
     *       val originalBitmap = BitmapFactory.decodeStream(stream)  // 解码 Bitmap
     *       context.contentResolver.openInputStream(imageUri)?.use { exifStream ->
     *           val exif = ExifInterface(exifStream)
     *           when (orientation) {
     *               NORMAL -> return@withContext originalBitmap   // ← 正常返回，无泄漏
     *               else   -> {
     *                   val corrected = Bitmap.createBitmap(...)
     *                   originalBitmap.recycle()                  // ← 旋转路径正确回收
     *                   return@withContext corrected
     *               }
     *           }
     *       }
     *       // 若 exifStream 为 null（即内层 use 块不执行），
     *       // 外层 use 块结束时 originalBitmap 已不在作用域，
     *       // 但 BitmapFactory.decodeStream 的结果实际上只返回 null（因为 use 块返回值被忽略）
     *       // → 整个函数返回 null，originalBitmap 被 GC 回收（无实际泄漏，但丢失了图片）
     *   }
     *
     * 真正的问题：
     *   当 ExifInterface 构造时 exifStream 为 null（某些 content URI 的第二次 open 失败），
     *   内层 use { } 块不执行，originalBitmap 正确解码却被丢弃，函数返回 null，
     *   表现为"未识别到有效数据"。
     *
     * 修复方案：
     *   将两次 openInputStream 合并为更健壮的写法：
     *   先解码 Bitmap，再尝试读取 EXIF，若失败则直接返回原 Bitmap（不丢弃）。
     *   对于 content://media/picker_get_content/... 类型的 URI（Android Photo Picker），
     *   第二次 openInputStream 在部分荣耀/华为机型上可能因权限检查时序问题返回 null，
     *   此修复确保至少能返回未旋转的 Bitmap，而不是直接返回 null。
     */
    suspend fun loadAndFixExifMatrixSecurely(context: Context, imageUri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // 第一步：解码 Bitmap
                val originalBitmap = context.contentResolver
                    .openInputStream(imageUri)
                    ?.use { stream -> BitmapFactory.decodeStream(stream) }
                    ?: return@withContext null

                // 第二步：读取 EXIF 方向，失败时直接返回原 Bitmap（不丢弃）
                val orientation = try {
                    context.contentResolver.openInputStream(imageUri)?.use { exifStream ->
                        ExifInterface(exifStream).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                } catch (e: Exception) {
                    // 第二次 openInputStream 失败（部分机型 picker URI 权限时序问题）
                    // 降级处理：返回未旋转的原始 Bitmap，不抛出、不丢弃
                    e.printStackTrace()
                    ExifInterface.ORIENTATION_NORMAL
                }

                // 第三步：按需旋转
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> return@withContext originalBitmap   // 无需旋转，直接返回
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

    suspend fun clearOldCacheFiles(cacheDir: File) = withContext(Dispatchers.IO) {
        try {
            val thresholdTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            cacheDir.listFiles()?.forEach { file ->
                if ((file.name.startsWith("IMG_") || file.name.startsWith("CROP_")) &&
                    file.lastModified() < thresholdTime
                ) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
