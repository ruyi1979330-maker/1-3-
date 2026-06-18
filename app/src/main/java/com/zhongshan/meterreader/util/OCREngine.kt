package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object OCREngine {

    // 注意：这里不再使用中文识别，而是使用标准拉丁字母/数字识别（识别效率极高）
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 专门针对“裁剪后的带单位数字小图”提取纯数字
     */
    suspend fun extractPureNumber(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 图片增强：去除反光与点阵屏干扰
            val enhancedBitmap = enhanceBitmapForOcr(bitmap)

            // 2. 裁剪小图识别
            val image = InputImage.fromBitmap(enhancedBitmap, 0)
            val result = recognizer.process(image).await()
            val rawText = result.text.trim() // 例: "7.6 C" 或 "264.2 kPag"

            // 3. 【核心】强制清洗：只留下数字和小数点，过滤所有单位和英文字母
            var cleaned = rawText.replace(Regex("[^0-9.]"), "")
            
            // 4. 边界保护：防止出现 7.8.5 这种双小数点情况
            val parts = cleaned.split(".")
            return@withContext if (parts.size > 2) {
                // 如果是双小数点，丢弃最后面的
                "${parts[0]}.${parts[1]}"
            } else if (cleaned.isNotEmpty() && cleaned != ".") {
                cleaned
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    // 对比度增强（对付特灵黄屏的反光很管用）
    private fun enhanceBitmapForOcr(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, src.config)
        val canvas = android.graphics.Canvas(bmp)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f) // 转灰度
        val contrast = ColorMatrix()
        contrast.setScale(1.5f, 1.5f, 1.5f, 1.5f) // 增加对比度让像素点更清晰
        cm.postConcat(contrast)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }
}
