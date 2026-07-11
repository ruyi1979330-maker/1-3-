// 文件名: UltimateLcdBinarizer.kt
package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Arrays

object UltimateLcdBinarizer {

    /**
     * 处理 CameraX 视频帧 (YUV_420_888) —— 保留备用
     */
    suspend fun process(
        imageProxy: ImageProxy,
        rois: List<Rect>,
        resourcePool: BinarizeResourcePool,
        contrastOffset: Int = 15
    ): InputImage? = withContext(Dispatchers.IO) {
        try {
            if (rois.isEmpty()) return@withContext null

            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride

            val ySize = yBuffer.remaining()
            val yArray = resourcePool.acquireYuvBuffer(ySize)
            yBuffer.get(yArray, 0, ySize)

            var totalHeight = 0
            var maxWidth = 0
            for (roi in rois) {
                val cropW = roi.width().coerceAtLeast(0)
                val cropH = roi.height().coerceAtLeast(0)
                if (cropW > maxWidth) maxWidth = cropW
                totalHeight += cropH + 4
            }

            if (maxWidth == 0 || totalHeight == 0) return@withContext null

            val canvasSize = maxWidth * totalHeight
            val canvasArray = resourcePool.acquireCanvasBuffer(canvasSize)
            Arrays.fill(canvasArray, 0, canvasSize, 255.toByte())

            var currentYOffset = 0
            for (roi in rois) {
                val startX = roi.left.coerceIn(0, width - 1)
                val startY = roi.top.coerceIn(0, height - 1)
                val endX = roi.right.coerceIn(0, width)
                val endY = roi.bottom.coerceIn(0, height)
                val boxWidth = endX - startX
                val boxHeight = endY - startY
                if (boxWidth <= 0 || boxHeight <= 0) continue

                for (y in 0 until boxHeight) {
                    val imgY = startY + y
                    for (x in 0 until boxWidth) {
                        val imgX = startX + x
                        var sum = 0
                        var count = 0
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val ny = (imgY + dy).coerceIn(0, height - 1)
                                val nx = (imgX + dx).coerceIn(0, width - 1)
                                val pixelIndex = ny * rowStride + nx * pixelStride
                                if (pixelIndex in 0 until ySize) {
                                    sum += yArray[pixelIndex].toInt() and 0xFF
                                    count++
                                }
                            }
                        }
                        val avg = if (count > 0) sum / count else 128
                        val threshold = avg - contrastOffset
                        val centerPixelIndex = imgY * rowStride + imgX * pixelStride
                        val centerPixelValue = if (centerPixelIndex in 0 until ySize) {
                            yArray[centerPixelIndex].toInt() and 0xFF
                        } else {
                            255
                        }
                        val outPixel = if (centerPixelValue < threshold) 0.toByte() else 255.toByte()
                        val canvasIndex = (currentYOffset + y) * maxWidth + x
                        if (canvasIndex in 0 until canvasSize) {
                            canvasArray[canvasIndex] = outPixel
                        }
                    }
                }
                currentYOffset += boxHeight + 4
            }

            // 将 byte 数组转为 int 像素数组，适配 ARGB_8888
            val pixels = IntArray(canvasSize)
            for (i in 0 until canvasSize) {
                val gray = canvasArray[i].toInt() and 0xFF
                pixels[i] = if (gray == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }

            val bitmap = resourcePool.acquireBitmap(maxWidth, totalHeight)
            bitmap.setPixels(pixels, 0, maxWidth, 0, 0, maxWidth, totalHeight)

            return@withContext InputImage.fromBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * 处理单张 Bitmap（拍照/相册） —— 当前主要使用
     */
    suspend fun processBitmap(
        bitmap: Bitmap,
        rois: List<Rect>,
        resourcePool: BinarizeResourcePool,
        contrastOffset: Int = 15
    ): InputImage? = withContext(Dispatchers.IO) {
        try {
            if (rois.isEmpty()) return@withContext null

            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val graySize = pixels.size
            val grayArray = resourcePool.acquireYuvBuffer(graySize)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                grayArray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
            }

            var totalHeight = 0
            var maxWidth = 0
            for (roi in rois) {
                val cropW = roi.width().coerceAtLeast(0)
                val cropH = roi.height().coerceAtLeast(0)
                if (cropW > maxWidth) maxWidth = cropW
                totalHeight += cropH + 4
            }

            if (maxWidth == 0 || totalHeight == 0) return@withContext null

            val canvasSize = maxWidth * totalHeight
            val canvasArray = resourcePool.acquireCanvasBuffer(canvasSize)
            Arrays.fill(canvasArray, 0, canvasSize, 255.toByte())

            var currentYOffset = 0
            for (roi in rois) {
                val startX = roi.left.coerceIn(0, width - 1)
                val startY = roi.top.coerceIn(0, height - 1)
                val endX = roi.right.coerceIn(0, width)
                val endY = roi.bottom.coerceIn(0, height)
                val boxWidth = endX - startX
                val boxHeight = endY - startY
                if (boxWidth <= 0 || boxHeight <= 0) continue

                for (y in 0 until boxHeight) {
                    val imgY = startY + y
                    for (x in 0 until boxWidth) {
                        val imgX = startX + x
                        var sum = 0
                        var count = 0
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val ny = (imgY + dy).coerceIn(0, height - 1)
                                val nx = (imgX + dx).coerceIn(0, width - 1)
                                val index = ny * width + nx
                                sum += grayArray[index].toInt() and 0xFF
                                count++
                            }
                        }
                        val avg = if (count > 0) sum / count else 128
                        val threshold = avg - contrastOffset
                        val centerIndex = imgY * width + imgX
                        val centerValue = grayArray[centerIndex].toInt() and 0xFF
                        val outPixel = if (centerValue < threshold) 0.toByte() else 255.toByte()
                        val canvasIndex = (currentYOffset + y) * maxWidth + x
                        if (canvasIndex in 0 until canvasSize) {
                            canvasArray[canvasIndex] = outPixel
                        }
                    }
                }
                currentYOffset += boxHeight + 4
            }

            // 转换为 ARGB_8888 兼容格式
            val argbPixels = IntArray(canvasSize)
            for (i in 0 until canvasSize) {
                val gray = canvasArray[i].toInt() and 0xFF
                argbPixels[i] = if (gray == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }

            val outBitmap = resourcePool.acquireBitmap(maxWidth, totalHeight)
            outBitmap.setPixels(argbPixels, 0, maxWidth, 0, 0, maxWidth, totalHeight)

            // ---------- 调试：保存拼版图到文件 ----------
            try {
                val debugDir = File("/sdcard/Download/ocr_debug")
                if (!debugDir.exists()) debugDir.mkdirs()
                val debugFile = File(debugDir, "spritesheet_${System.currentTimeMillis()}.png")
                FileOutputStream(debugFile).use { fos ->
                    outBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                android.util.Log.d("OCR_Debug", "拼版图已保存至: ${debugFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("OCR_Debug", "保存拼版图失败", e)
            }

            return@withContext InputImage.fromBitmap(outBitmap, 0)
        } finally {
            // bitmap 由调用者管理
        }
    }
}
