	// 文件名: UltimateLcdBinarizer.kt
	package com.zhongshan.meterreader.util
	import android.graphics.Bitmap
	import android.graphics.Rect
	import androidx.camera.core.ImageProxy
	import com.google.mlkit.vision.common.InputImage
	import java.nio.ByteBuffer
	object UltimateLcdBinarizer {
	    data class BinarizeResult(
	        val inputImage: InputImage,
	        val roiYRanges: List<Pair<Int, Int>>
	    )
	    /**
	     * 阶段二：极限图像预处理引擎 (处理相机流 ImageProxy)
	     * Sprite Sheet 空间组装法，专为点阵屏优化，零 GC 内存分配。
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
	        // 1. 一次性 Bulk Copy 到堆内存
	        val ySize = yBuffer.remaining()
	        val yArray = resourcePool.acquireYBuffer(ySize)
	        yBuffer.get(yArray, 0, ySize)
	        // 2. 计算拼接画布尺寸
	        var totalHeight = 0
	        var maxWidth = 0
	        for (rect in rois) {
	            val w = rect.width()
	            val h = rect.height()
	            if (w > maxWidth) maxWidth = w
	            totalHeight += h + 4 // 中间留 4px 间隔
	        }
	        if (totalHeight == 0 || maxWidth == 0) return null
	        val canvasWidth = maxWidth
	        val canvasHeight = totalHeight
	        val canvasSize = canvasWidth * canvasHeight
	        val canvasArray = resourcePool.acquireCanvasBuffer(canvasSize)
	        // ALPHA_8: 0 为透明, 255 为不透明黑
	        java.util.Arrays.fill(canvasArray, 0, canvasSize, 0.toByte())
	        val roiYRanges = mutableListOf<Pair<Int, Int>>()
	        var currentY = 0
	        // 3. 遍历 ROI 进行局部动态阈值计算与紧凑拼接
	        for (rect in rois) {
	            val roiW = rect.width()
	            val roiH = rect.height()
	            val startY = currentY
	            // 3x3 采样局部动态阈值计算
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
	            // 像素拷贝至画布 (白底黑字: 像素 < 阈值 为黑字 255)
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
	                    canvasArray[startY * canvasWidth + x] = if (pixelVal < threshold) 255.toByte() else 0.toByte()
	                }
	            }
	            roiYRanges.add(Pair(startY, startY + roiH))
	            currentY += roiH + 4
	        }
	        // 4. 从对象池获取 Bitmap 并灌入数据
	        val bitmap = resourcePool.acquireBitmap(canvasWidth, canvasHeight)
	        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(canvasArray, 0, canvasSize))
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
	        java.util.Arrays.fill(canvasArray, 0, canvasSize, 0.toByte())
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
	                    canvasArray[startY * canvasWidth + x] = if (gray < 128) 255.toByte() else 0.toByte()
	                }
	            }
	            roiYRanges.add(Pair(startY, startY + roiH))
	            currentY += roiH + 4
	        }
	        val resultBitmap = resourcePool.acquireBitmap(canvasWidth, canvasHeight)
	        resultBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(canvasArray, 0, canvasSize))
	        return BinarizeResult(InputImage.fromBitmap(resultBitmap, 0), roiYRanges)
	    }
	}
