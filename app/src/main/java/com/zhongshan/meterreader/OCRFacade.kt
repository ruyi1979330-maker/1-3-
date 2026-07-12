// 文件名: OCRFacade.kt
package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.util.BinarizeResourcePool
import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class ImageSource { CAMERA, GALLERY }

object OCRFacade {

    // 目标标准尺寸 (仅针对相机原图进行归一化)
    private const val TARGET_WIDTH = 3000
    private const val TARGET_HEIGHT = 4000

    suspend fun performSmartOcr(
        context: Context,
        imageUri: Uri,
        template: DeviceTemplate,
        screenIndex: Int,
        imageSource: ImageSource,
        binarizePool: BinarizeResourcePool
    ): Map<String, String> = withContext(Dispatchers.IO) {
        
        var originalBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var stitchedBitmap: Bitmap? = null
        
        try {
            DebugLogger.log("OCR-Facade", "===== 开始处理图片 =====")
            DebugLogger.log("OCR-Facade", "来源: $imageSource, 屏幕索引: $screenIndex")
            
            // 修复点：使用项目中真实存在且安全的图片加载方法
            originalBitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
            if (originalBitmap == null) {
                DebugLogger.log("OCR-Facade-Error", "无法加载图片 Uri: $imageUri")
                return@withContext emptyMap()
            }

            // 修复点：恢复板交（换热器）的专属提交流程（铁律：不破坏已有稳定模块）
            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(originalBitmap, template.roomId == 1, plateKeywordMap)
            }

            val rois = mutableListOf<Rect>()
            val fieldMapping = mutableListOf<String>()
            val processBitmap: Bitmap

            // 铁律 3：分离坐标系处理
            if (imageSource == ImageSource.CAMERA) {
                // 相机原图：强制缩放到 3000x4000，应用绝对坐标
                scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, TARGET_WIDTH, TARGET_HEIGHT, true)
                processBitmap = scaledBitmap
                val configs = DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex)
                configs.forEach { config ->
                    rois.add(Rect(
                        (TARGET_WIDTH * config.xPercent).toInt(),
                        (TARGET_HEIGHT * config.yPercent).toInt(),
                        (TARGET_WIDTH * (config.xPercent + config.wPercent)).toInt(),
                        (TARGET_HEIGHT * (config.yPercent + config.hPercent)).toInt()
                    ))
                    fieldMapping.add(config.fieldId)
                }
                DebugLogger.log("OCR-Facade", "应用相机绝对坐标(按3000x4000缩放), 生成 ROI 数量: ${rois.size}")
            } else {
                // 相册裁剪图：保留原始比例，应用相对坐标
                processBitmap = originalBitmap
                val w = processBitmap.width
                val h = processBitmap.height
                val configs = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
                configs.forEach { config ->
                    rois.add(Rect(
                        (w * config.xStartPct).toInt(),
                        (h * config.yStartPct).toInt(),
                        (w * config.xEndPct).toInt(),
                        (h * config.yEndPct).toInt()
                    ))
                    fieldMapping.add(config.fieldId)
                }
                DebugLogger.log("OCR-Facade", "应用相册相对坐标(原图尺寸 ${w}x${h}), 生成 ROI 数量: ${rois.size}")
            }

            if (rois.isEmpty()) {
                DebugLogger.log("OCR-Facade-Error", "未获取到有效的 ROI 坐标配置")
                return@withContext emptyMap()
            }

            // 铁律 1 & 4：内聚进行 ARGB_8888 二值化与严密的垂直拼接，杜绝任何 Y 轴串联
            val stitchedHeight = rois.sumOf { it.height() }
            val maxWidth = rois.maxOfOrNull { it.width() } ?: 0
            
            stitchedBitmap = Bitmap.createBitmap(maxWidth, stitchedHeight, Bitmap.Config.ARGB_8888)
            val canvasArray = IntArray(maxWidth * stitchedHeight)
            val roiYRanges = mutableListOf<Pair<Int, Int>>()
            
            var currentY = 0
            for (i in rois.indices) {
                val roi = rois[i]
                // 防越界保护
                val safeLeft = roi.left.coerceAtLeast(0)
                val safeTop = roi.top.coerceAtLeast(0)
                val safeRight = roi.right.coerceAtMost(processBitmap.width)
                val safeBottom = roi.bottom.coerceAtMost(processBitmap.height)
                
                val cropW = safeRight - safeLeft
                val cropH = safeBottom - safeTop
                
                if (cropW <= 0 || cropH <= 0) {
                    roiYRanges.add(Pair(-1, -1))
                    continue
                }

                val pixels = IntArray(cropW * cropH)
                processBitmap.getPixels(pixels, 0, cropW, safeLeft, safeTop, cropW, cropH)
                
                // 二值化 (灰度阈值设为 120，遵循黑 0xFF000000，白 0xFFFFFFFF)
                for (pIdx in pixels.indices) {
                    val p = pixels[pIdx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    pixels[pIdx] = if (gray < 120) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
                
                // 绘制到拼接画布
                for (y in 0 until cropH) {
                    for (x in 0 until cropW) {
                        canvasArray[(currentY + y) * maxWidth + x] = pixels[y * cropW + x]
                    }
                    // 宽度不足最大宽度的部分，补齐白底防止噪点
                    for (x in cropW until maxWidth) {
                        canvasArray[(currentY + y) * maxWidth + x] = 0xFFFFFFFF.toInt()
                    }
                }
                
                // 严密记录这段内容在拼图上的确切 Y 轴起止范围
                roiYRanges.add(Pair(currentY, currentY + cropH))
                currentY += cropH
            }

            stitchedBitmap.setPixels(canvasArray, 0, maxWidth, 0, 0, maxWidth, stitchedHeight)
            DebugLogger.log("OCR-Facade", "二值化拼接完成，拼图尺寸: ${maxWidth}x${stitchedHeight}")

            // 投喂给 ML Kit
            val inputImage = InputImage.fromBitmap(stitchedBitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionResult = recognizer.process(inputImage).await()
            
            val lines = visionResult.textBlocks.flatMap { block -> block.lines }
            DebugLogger.log("OCR-Facade", "ML Kit 识别成功，共读取 ${lines.size} 行文本")

            val results = mutableMapOf<String, String>()
            for (line in lines) {
                val box = line.boundingBox ?: continue
                val centerY = box.top + box.height() / 2
                var matchedIndex = -1
                
                for (i in roiYRanges.indices) {
                    val (top, bottom) = roiYRanges[i]
                    if (top == -1) continue
                    if (centerY in top..bottom) { 
                        matchedIndex = i
                        break 
                    }
                }
                
                if (matchedIndex in fieldMapping.indices) {
                    // 数据清洗：容错逗号、冒号误判
                    val text = line.text.trim().replace(",", ".").replace(":", ".")
                    val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(text)
                    if (match != null) {
                        results[fieldMapping[matchedIndex]] = match.value
                        DebugLogger.log("OCR-Facade-Match", "命中字段 [${fieldMapping[matchedIndex]}] -> 值: ${match.value}")
                    }
                }
            }

            // 最终按当前屏所需字段进行结果过滤，防止带入脏数据
            val finalResults = mutableMapOf<String, String>()
            for ((index, fieldId) in fieldMapping.withIndex()) {
                if (fieldId in results) {
                    finalResults[fieldId] = results[fieldId]!!
                }
            }

            DebugLogger.log("OCR-Facade", "===== 最终提取结果 =====")
            DebugLogger.log("OCR-Facade", finalResults.toString())
            return@withContext finalResults

        } catch (e: Exception) {
            DebugLogger.log("OCR-Facade-Error", "识别流程发生严重异常: ${Log.getStackTraceString(e)}")
            e.printStackTrace()
            return@withContext emptyMap()
        } finally {
            originalBitmap?.recycle()
            scaledBitmap?.recycle()
            stitchedBitmap?.recycle()
        }
    }
}
