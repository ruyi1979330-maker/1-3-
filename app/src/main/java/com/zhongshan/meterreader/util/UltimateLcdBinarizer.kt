// 文件名: UltimateLcdBinarizer.kt
package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.zhongshan.meterreader.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Arrays

data class BinarizedImageResult(
    val inputImage: InputImage,
    val roiYRanges: List<Pair<Int, Int>>
)

object UltimateLcdBinarizer {

    suspend fun process(
        imageProxy: ImageProxy,
        rois: List<Rect>,
        resourcePool: BinarizeResourcePool,
        contrastOffset: Int = 15
    ): BinarizedImageResult? = withContext(Dispatchers.IO) {
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

            val canvasSize = maxWidth * totalHeight * 4
            val canvasArray = resourcePool.acquireCanvasBuffer(canvasSize)
            Arrays.fill(canvasArray, 0, canvasSize, 255.toByte())

            val roiYRanges = mutableListOf<Pair<Int, Int>>()
            var currentYOffset = 0

            for (roi in rois) {
                val startX = roi.left.coerceIn(0, width - 1)
                val startY = roi.top.coerceIn(0, height - 1)
                val endX = roi.right.coerceIn(0, width)
                val endY = roi.bottom.coerceIn(0, height)
                val boxWidth = endX - startX
                val boxHeight = endY - startY
                if (boxWidth <= 0 || boxHeight <= 0) { roiYRanges.add(Pair(-1, -1)); continue }

                roiYRanges.add(Pair(currentYOffset, currentYOffset + boxHeight))

                for (y in 0 until boxHeight) {
                    val imgY = startY + y
                    for (x in 0 until boxWidth) {
                        val imgX = startX + x
                        var sum = 0; var count = 0
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val ny = (imgY + dy).coerceIn(0, height - 1)
                                val nx = (imgX + dx).coerceIn(0, width - 1)
                                val pixelIndex = ny * rowStride + nx * pixelStride
                                if (pixelIndex in 0 until ySize) { sum += yArray[pixelIndex].toInt() and 0xFF; count++ }
                            }
                        }
                        val avg = if (count > 0) sum / count else 128
                        val threshold = avg - contrastOffset
                        val centerPixelIndex = imgY * rowStride + imgX * pixelStride
                        val centerPixelValue = if (centerPixelIndex in 0 until ySize) yArray[centerPixelIndex].toInt() and 0xFF else 255
                        val canvasIndex = ((currentYOffset + y) * maxWidth + x) * 4
                        if (canvasIndex in 0 until canvasSize - 3) {
                            if (centerPixelValue < threshold) {
                                canvasArray[canvasIndex] = 0; canvasArray[canvasIndex+1] = 0; canvasArray[canvasIndex+2] = 0; canvasArray[canvasIndex+3] = 255.toByte()
                            } else {
                                canvasArray[canvasIndex] = 255.toByte(); canvasArray[canvasIndex+1] = 255.toByte(); canvasArray[canvasIndex+2] = 255.toByte(); canvasArray[canvasIndex+3] = 255.toByte()
                            }
                        }
                    }
                }
                currentYOffset += boxHeight + 4
            }

            val bitmap = resourcePool.acquireBitmap(maxWidth, totalHeight)
            val byteBuffer = ByteBuffer.wrap(canvasArray, 0, canvasSize)
            bitmap.copyPixelsFromBuffer(byteBuffer)

            return@withContext BinarizedImageResult(InputImage.fromBitmap(bitmap, imageProxy.imageInfo.rotationDegrees), roiYRanges)
        } finally {
            imageProxy.close()
        }
    }

    suspend fun processBitmap(
        bitmap: Bitmap,
        rois: List<Rect>,
        resourcePool: BinarizeResourcePool,
        contrastOffset: Int = 40
    ): BinarizedImageResult? = withContext(Dispatchers.IO) {
        try {
            if (rois.isEmpty()) return@withContext null

            val width = bitmap.width; val height = bitmap.height

            var totalHeight = 0; var maxWidth = 0
            for (roi in rois) {
                val cropW = roi.width().coerceAtLeast(0); val cropH = roi.height().coerceAtLeast(0)
                if (cropW > maxWidth) maxWidth = cropW
                totalHeight += cropH + 4
            }

            if (maxWidth == 0 || totalHeight == 0) return@withContext null

            val canvasSize = maxWidth * totalHeight * 4
            val canvasArray = resourcePool.acquireCanvasBuffer(canvasSize)
            Arrays.fill(canvasArray, 0, canvasSize, 255.toByte())

            val roiYRanges = mutableListOf<Pair<Int, Int>>()
            var currentYOffset = 0

            for (roiIndex in rois.indices) {
                val roi = rois[roiIndex]
                val startX = roi.left.coerceIn(0, width - 1); val startY = roi.top.coerceIn(0, height - 1)
                val endX = roi.right.coerceIn(0, width); val endY = roi.bottom.coerceIn(0, height)
                val boxWidth = endX - startX; val boxHeight = endY - startY
                if (boxWidth <= 0 || boxHeight <= 0) {
                    roiYRanges.add(Pair(-1, -1))
                    DebugLogger.log("Binarizer", "ROI[$roiIndex] 无效尺寸，跳过")
                    continue
                }

                roiYRanges.add(Pair(currentYOffset, currentYOffset + boxHeight))

                // 提取当前 ROI 区域像素
                val roiPixels = IntArray(boxWidth * boxHeight)
                bitmap.getPixels(roiPixels, 0, boxWidth, startX, startY, boxWidth, boxHeight)

                // 计算平均灰度
                var totalGray = 0L
                for (pixel in roiPixels) {
                    val r = (pixel shr 16) and 0xFF; val g = (pixel shr 8) and 0xFF; val b = pixel and 0xFF
                    totalGray += (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                }
                val avgGray = (totalGray / roiPixels.size).toInt()
                val threshold = avgGray - contrastOffset

                DebugLogger.log("Binarizer", "ROI[$roiIndex] box=${boxWidth}x${boxHeight}, 平均灰度=$avgGray, 阈值=$threshold")

                var blackCount = 0
                for (y in 0 until boxHeight) {
                    for (x in 0 until boxWidth) {
                        val pixel = roiPixels[y * boxWidth + x]
                        val r = (pixel shr 16) and 0xFF; val g = (pixel shr 8) and 0xFF; val b = pixel and 0xFF
                        val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        val canvasIndex = ((currentYOffset + y) * maxWidth + x) * 4
                        if (canvasIndex in 0 until canvasSize - 3) {
                            if (gray < threshold) {
                                canvasArray[canvasIndex] = 0; canvasArray[canvasIndex+1] = 0; canvasArray[canvasIndex+2] = 0; canvasArray[canvasIndex+3] = 255.toByte()
                                blackCount++
                            } else {
                                canvasArray[canvasIndex] = 255.toByte(); canvasArray[canvasIndex+1] = 255.toByte(); canvasArray[canvasIndex+2] = 255.toByte(); canvasArray[canvasIndex+3] = 255.toByte()
                            }
                        }
                    }
                }
                val blackPercent = (blackCount * 100f) / (boxWidth * boxHeight)
                DebugLogger.log("Binarizer", "ROI[$roiIndex] 黑色像素占比: ${"%.1f".format(blackPercent)}%")

                currentYOffset += boxHeight + 4
            }

            val outBitmap = resourcePool.acquireBitmap(maxWidth, totalHeight)
            val byteBuffer = ByteBuffer.wrap(canvasArray, 0, canvasSize)
            outBitmap.copyPixelsFromBuffer(byteBuffer)

            // 保存调试图片
            try {
                val debugDir = File("/sdcard/Download/ocr_debug")
                if (!debugDir.exists()) debugDir.mkdirs()
                val debugFile = File(debugDir, "spritesheet_${System.currentTimeMillis()}.png")
                FileOutputStream(debugFile).use { fos -> outBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                DebugLogger.log("Binarizer", "拼版图已保存: ${debugFile.absolutePath}")
            } catch (e: Exception) {
                DebugLogger.log("Binarizer", "保存拼版图失败: ${e.message}")
            }

            return@withContext BinarizedImageResult(InputImage.fromBitmap(outBitmap, 0), roiYRanges)
        }
    }
}
