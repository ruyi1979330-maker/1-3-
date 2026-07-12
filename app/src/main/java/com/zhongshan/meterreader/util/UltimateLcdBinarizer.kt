// 文件名: UltimateLcdBinarizer.kt
package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.zhongshan.meterreader.DebugLogger

object UltimateLcdBinarizer {

    data class BinarizeResult(
        val inputImage: InputImage,
        val roiYRanges: List<Pair<Int, Int>>
    )

    /**
     * 阶段二：极限图像预处理引擎 (处理相机流 ImageProxy)
     */
    fun processImageProxy(
        imageProxy: ImageProxy,
        rois: List<Rect>,
        resourcePool: BinarizeResourcePool
    ): BinarizeResult? {
        val yPlane = imageProxy.planes[0]
        val yRowStride = yPlane.rowStride
        val yBuffer = yPlane.buffer

        val imgWidth = imageProxy.width
        val imgHeight = imageProxy.height

        val ySize = yBuffer.remaining()
        val yArray = resourcePool.acquireYBuffer(ySize)
        yBuffer.get(yArray, 0, ySize)

        var totalHeight = 0
        var maxWidth = 0
        for (rect in rois) {
            val w = rect.width()
            val h = rect.height()
            if (w > maxWidth) maxWidth = w
            totalHeight += h + 4
        }
        if (totalHeight == 0 || maxWidth == 0) return null

        val canvasWidth = maxWidth
        val canvasHeight = totalHeight
        val canvasSize = canvasWidth * canvasHeight
        val canvasArray = resourcePool.acquireCanvasBuffer(canvasSize)
        
        // 修改：初始化为白色背景 (0xFFFFFFFF)
        java.util.Arrays.fill(canvasArray, 0, canvasSize, -1)

        val roiYRanges = mutableListOf<Pair<Int, Int>>()
        var currentY = 0

        for ((index, rect) in rois.withIndex()) {
            val roiW = rect.width()
            val roiH = rect.height()
            val startY = currentY

            val blockW = roiW / 3
            val blockH = roiH / 3
            if (blockW == 0 || blockH == 0) continue

            val thresholds = Array(3) { IntArray(3) }
            for (by in 0 until 3) {
                for (bx in 0 until 3) {
                    var sum = 0
                    var count = 0
                    val startCol = rect.left + bx * blockW
                    val startRow = rect.top + by * blockH
                    val endCol = if (bx == 2) rect.right else startCol + blockW
                    val endRow = if (by == 2) rect.bottom else startRow + blockH
                    for (y in startRow until endRow) {
                        for (x in startCol until endCol) {
                            if (x < imgWidth && y < imgHeight) {
                                sum += (yArray[y * yRowStride + x].toInt() and 0xFF)
                                count++
                            }
                        }
                    }
                    thresholds[by][bx] = if (count > 0) sum / count else 128
                }
            }

            for (y in 0 until roiH) {
                val srcY = rect.top + y
                if (srcY >= imgHeight) continue
                for (x in 0 until roiW) {
                    val srcX = rect.left + x
                    if (srcX >= imgWidth) continue
                    
                    val pixelVal = yArray[srcY * yRowStride + srcX].toInt() and 0xFF
                    val bx = if (x < blockW) 0 else if (x < blockW * 2) 1 else 2
                    val by = if (y < blockH) 0 else if (y < blockH * 2) 1 else 2
                    val threshold = thresholds[by][bx]
                    
                    // 修改：黑字 0xFF000000 (-16777216), 白底 0xFFFFFFFF (-1)
                    canvasArray[startY * canvasWidth + x] = if (pixelVal < threshold) -16777216 else -1
                }
            }
            roiYRanges.add(Pair(startY, startY + roiH))
            currentY += roiH + 4
        }

        val bitmap = resourcePool.acquireBitmap(canvasWidth, canvasHeight)
        bitmap.setPixels(canvasArray, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)

        return BinarizeResult(InputImage.fromBitmap(bitmap, 0), roiYRanges)
    }

    /**
     * 兼容相册模式的 Bitmap 处理逻辑
     */
    fun processBitmap(
        bitmap: Bitmap,
        rois: List<Rect>,
        resourcePool: BinarizeResourcePool
    ): BinarizeResult? {
        val imgWidth = bitmap.width
        val imgHeight = bitmap.height
        
        val pixels = IntArray(imgWidth * imgHeight)
        bitmap.getPixels(pixels, 0, imgWidth, 0, 0, imgWidth, imgHeight)

        var totalHeight = 0
        var maxWidth = 0
        for (rect in rois) {
            val w = rect.width()
            val h = rect.height()
            if (w > maxWidth) maxWidth = w
            totalHeight += h + 4
        }
        if (totalHeight == 0 || maxWidth == 0) return null

        val canvasWidth = maxWidth
        val canvasHeight = totalHeight
        val canvasSize = canvasWidth * canvasHeight
        val canvasArray = resourcePool.acquireCanvasBuffer(canvasSize)
        java.util.Arrays.fill(canvasArray, 0, canvasSize, -1)

        val roiYRanges = mutableListOf<Pair<Int, Int>>()
        var currentY = 0

        for (rect in rois) {
            val roiW = rect.width()
            val roiH = rect.height()
            val startY = currentY

            for (y in 0 until roiH) {
                val srcY = rect.top + y
                if (srcY >= imgHeight) continue
                for (x in 0 until roiW) {
                    val srcX = rect.left + x
                    if (srcX >= imgWidth) continue
                    
                    val pixel = pixels[srcY * imgWidth + srcX]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val gray = (r + g + b) / 3
                    
                    canvasArray[startY * canvasWidth + x] = if (gray < 128) -16777216 else -1
                }
            }
            roiYRanges.add(Pair(startY, startY + roiH))
            currentY += roiH + 4
        }

        val resultBitmap = resourcePool.acquireBitmap(canvasWidth, canvasHeight)
        resultBitmap.setPixels(canvasArray, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)

        return BinarizeResult(InputImage.fromBitmap(resultBitmap, 0), roiYRanges)
    }
}
