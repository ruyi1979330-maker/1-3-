// 文件名: UltimateLcdBinarizer.kt
package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Arrays

object UltimateLcdBinarizer {

    /**
     * 处理 CameraX 视频帧 (YUV_420_888)
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

            val bitmap = resourcePool.acquireBitmap(maxWidth, totalHeight)
            val byteBuffer = ByteBuffer.wrap(canvasArray, 0, canvasSize)
            bitmap.copyPixelsFromBuffer(byteBuffer)

            return@withContext InputImage.fromBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * 处理单张 Bitmap（适配当前拍照/相册流程）
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

            val outBitmap = resourcePool.acquireBitmap(maxWidth, totalHeight)
            val byteBuffer = ByteBuffer.wrap(canvasArray, 0, canvasSize)
            outBitmap.copyPixelsFromBuffer(byteBuffer)

            return@withContext InputImage.fromBitmap(outBitmap, 0)
        } finally {
            // bitmap 由调用者管理
        }
    }
}
