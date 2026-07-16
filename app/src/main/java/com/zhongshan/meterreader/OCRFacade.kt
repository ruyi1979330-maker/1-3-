// 文件名: OCRFacade.kt
package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.camera.core.ImageProxy
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

    // ========== 约克机组字段定义枚举（从方法内移到对象级别） ==========
    private enum class FieldType { PRESSURE, TEMPERATURE, PERCENTAGE }

    private data class YorkFieldDef(
        val key: String,
        val type: FieldType,
        val keywords: List<String>,
        val side: Char  // 'L'=左侧, 'R'=右侧, '?'=全屏
    )

    private val yorkFieldDefs = listOf(
        YorkFieldDef("evapRefPressure", FieldType.PRESSURE, listOf("蒸发器蒸发压力", "蒸发压力", "蒸发器压力"), 'L'),
        YorkFieldDef("evapTemp", FieldType.TEMPERATURE, listOf("蒸发器饱和温度", "饱和温度"), 'L'),
        YorkFieldDef("evapInTemp", FieldType.TEMPERATURE, listOf("冷冻水温度返回", "冷冻水返回", "冷冻水温度返"), 'L'),
        YorkFieldDef("evapOutTemp", FieldType.TEMPERATURE, listOf("冷冻水温度出水", "冷冻水出水", "冷冻水温度出"), 'L'),
        YorkFieldDef("condRefPressure", FieldType.PRESSURE, listOf("冷凝器冷凝压力", "冷凝压力", "冷凝器压力"), 'R'),
        YorkFieldDef("condTemp", FieldType.TEMPERATURE, listOf("冷凝器饱和温度", "饱和温度"), 'R'),
        YorkFieldDef("condInTemp", FieldType.TEMPERATURE, listOf("冷却水温度返回", "冷却水返回", "冷却水温度返"), 'R'),
        YorkFieldDef("condOutTemp", FieldType.TEMPERATURE, listOf("冷却水温度出水", "冷却水出水", "冷却水温度出"), 'R'),
        YorkFieldDef("compOilPressure", FieldType.PRESSURE, listOf("油压差", "油压"), '?'),
        YorkFieldDef("compOilTemp", FieldType.TEMPERATURE, listOf("油温", "油箱温度"), '?'),
        YorkFieldDef("compDischargeTemp", FieldType.TEMPERATURE, listOf("压缩机出口温度", "出口温度", "排口温度"), '?'),
        YorkFieldDef("compGuideOpening", FieldType.PERCENTAGE, listOf("滑阀位置", "滑阀"), '?'),
        YorkFieldDef("motorCurrent", FieldType.PERCENTAGE, listOf("%满载安培", "满载安培"), '?')
    )

    /**
     * 阶段二/四：无感视频流识别接口
     */
    suspend fun performStreamOcr(
        imageProxy: ImageProxy,
        template: DeviceTemplate,
        screenIndex: Int,
        resourcePool: BinarizeResourcePool
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val startTs = System.currentTimeMillis()
        val intermediates = ArrayList<Bitmap>()

        try {
            val rawBitmap: Bitmap = try {
                imageProxy.toBitmap()
            } catch (e: Throwable) {
                DebugLogger.log("StreamOCR", "toBitmap 失败: ${e.javaClass.simpleName} ${e.message}")
                return@withContext emptyMap()
            } ?: return@withContext emptyMap()

            if (rawBitmap.width <= 0 || rawBitmap.height <= 0) {
                DebugLogger.log("StreamOCR", "toBitmap 得到无效 Bitmap，跳过")
                return@withContext emptyMap()
            }
            intermediates.add(rawBitmap)
            val rawW = rawBitmap.width
            val rawH = rawBitmap.height

            val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Throwable) { 0 }
            DebugLogger.log("StreamOCR", "原始帧尺寸: ${rawW}x${rawH} 旋转角度: $rotation")
            val rotatedBitmap: Bitmap = if (rotation != 0) {
                try {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rb = Bitmap.createBitmap(rawBitmap, 0, 0, rawW, rawH, matrix, true)
                    if (rb !== rawBitmap) intermediates.add(rb)
                    rb
                } catch (e: Throwable) {
                    DebugLogger.log("StreamOCR", "旋转 Bitmap 失败，使用原图: ${e.message}")
                    rawBitmap
                }
            } else {
                rawBitmap
            }
            if (rotatedBitmap.width <= 0 || rotatedBitmap.height <= 0) {
                return@withContext emptyMap()
            }

            val targetWidth = 1080
            val finalBitmap: Bitmap = if (rotatedBitmap.width < targetWidth) {
                try {
                    val scale = targetWidth.toFloat() / rotatedBitmap.width
                    val sb = Bitmap.createScaledBitmap(
                        rotatedBitmap, targetWidth,
                        (rotatedBitmap.height * scale).toInt().coerceAtLeast(1), true
                    )
                    if (sb !== rotatedBitmap) intermediates.add(sb)
                    sb
                } catch (e: Throwable) {
                    DebugLogger.log("StreamOCR", "放大 Bitmap 失败，使用原图: ${e.message}")
                    rotatedBitmap
                }
            } else {
                rotatedBitmap
            }
            if (finalBitmap.width <= 0 || finalBitmap.height <= 0) {
                return@withContext emptyMap()
            }
            DebugLogger.log("StreamOCR", "最终送识别尺寸: ${finalBitmap.width}x${finalBitmap.height}")

            val ocrStartTs = System.currentTimeMillis()
            val ocrResult: Map<String, String> = try {
                if (template.machineId.startsWith("york")) {
                    extractYorkDataFromBitmap(finalBitmap, "StreamOCR")
                } else if (template.isHeatExchanger) {
                    val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                    OCREngine.extractPlateData(finalBitmap, template.roomId == 1, plateKeywordMap)
                } else {
                    extractScrewDataFromBitmap(finalBitmap, template, screenIndex, "StreamOCR")
                }
            } catch (oom: OutOfMemoryError) {
                DebugLogger.log("StreamOCR", "识别过程 OOM: ${oom.message}")
                System.gc()
                emptyMap()
            } catch (e: Throwable) {
                DebugLogger.log("StreamOCR", "识别过程异常: ${e.javaClass.simpleName} ${e.message}")
                emptyMap()
            }

            val ocrElapsed = System.currentTimeMillis() - ocrStartTs
            val totalElapsed = System.currentTimeMillis() - startTs
            DebugLogger.log("StreamOCR", "识别完成 OCR耗时=${ocrElapsed}ms 总耗时=${totalElapsed}ms 字段数=${ocrResult.size}")
            if (ocrResult.isNotEmpty()) {
                val fieldsDump = ocrResult.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(500)
                DebugLogger.log("StreamOCR", "识别字段: $fieldsDump")
            }
            return@withContext ocrResult
        } catch (e: Throwable) {
            DebugLogger.log("StreamOCR", "performStreamOcr 顶层异常: ${e.javaClass.simpleName} ${e.message}")
            return@withContext emptyMap()
        } finally {
            for (b in intermediates) {
                try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 兼容原相册模式的单张图片识别接口
     */
    suspend fun performSmartOcr(
        context: Context,
        imageUri: Uri,
        template: DeviceTemplate,
        screenIndex: Int,
        source: ImageSource,
        resourcePool: BinarizeResourcePool
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
        if (bitmap == null) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "图片加载失败", Toast.LENGTH_LONG).show() }
            return@withContext emptyMap()
        }
        try {
            if (template.machineId.startsWith("york")) {
                return@withContext extractYorkDataFromBitmap(bitmap, "SmartOCR")
            } else if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
            }
            return@withContext extractScrewDataFromBitmap(bitmap, template, screenIndex, "SmartOCR")
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 约克机组 OCR 解析引擎
     */
    private suspend fun extractYorkDataFromBitmap(bitmap: Bitmap, tag: String): Map<String, String> = withContext(Dispatchers.IO) {
        DebugLogger.log(tag, "开始约克机组原图识别，尺寸: ${bitmap.width}x${bitmap.height}")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val visionResult = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = visionResult.textBlocks.flatMap { it.lines }
        DebugLogger.log(tag, "ML Kit 原始识别到 ${lines.size} 行文本")

        data class LineInfo(val y: Float, val x: Float, val text: String)
        val sortedLines = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
        }.sortedBy { it.y }

        DebugLogger.log(tag, "--- 约克 按Y排序后文本行 ---")
        sortedLines.forEachIndexed { index, lineInfo ->
            DebugLogger.log(tag, "约克行[$index] Y=${lineInfo.y.toInt()} X=${lineInfo.x.toInt()} Text='${lineInfo.text}'")
        }

        if (sortedLines.isEmpty()) {
            DebugLogger.log(tag, "约克：未识别到任何文本行")
            return@withContext emptyMap()
        }

        val centerX = bitmap.width / 2f
        val results = mutableMapOf<String, String>()

        fun cleanNum(raw: String): String? {
            val m = Regex("""-?\d{1,4}(\.\d{1,2})?""").find(raw) ?: return null
            return m.value
        }

        fun isSideMatch(side: Char, x: Float): Boolean = when (side) {
            'L' -> x < centerX
            'R' -> x >= centerX
            else -> true
        }

        for (def in yorkFieldDefs) {
            var matched = false
            for (line in sortedLines) {
                if (!isSideMatch(def.side, line.x)) continue
                for (kw in def.keywords) {
                    if (line.text.contains(kw)) {
                        cleanNum(line.text)?.let { results[def.key] = it }
                        DebugLogger.log(tag, "约克匹配[命中同行] 关键词=$kw side=${def.side} 值=${results[def.key]} 原文=${line.text}")
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    // 邻近行匹配
                    val idx = sortedLines.indexOf(line)
                    val nearby = sortedLines.getOrNull(idx + 1)
                    if (nearby != null && isSideMatch(def.side, nearby.x)) {
                        for (kw in def.keywords) {
                            if (line.text.contains(kw)) {
                                cleanNum(nearby.text)?.let { results[def.key] = it }
                                DebugLogger.log(tag, "约克匹配[命中邻近行] 关键词=$kw side=${def.side} 值=${results[def.key]}")
                                matched = true
                                break
                            }
                        }
                    }
                }
                if (matched) break
            }
            if (!matched) {
                DebugLogger.log(tag, "约克匹配[未命中] keywords=${def.keywords} side=${def.side}")
            }
        }

        DebugLogger.log(tag, "约克最终提取结果: $results")
        return@withContext results
    }

    /**
     * 特灵机组识别引擎（完全不变）
     */
    private suspend fun extractScrewDataFromBitmap(
        bitmap: Bitmap,
        template: DeviceTemplate,
        screenIndex: Int,
        tag: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        DebugLogger.log(tag, "开始螺杆机原图直接识别，尺寸: ${bitmap.width}x${bitmap.height}")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionResult = recognizer.process(image).await()
        val lines = visionResult.textBlocks.flatMap { it.lines }
        DebugLogger.log(tag, "ML Kit 原始识别到 ${lines.size} 行文本")
        data class LineInfo(val y: Float, val x: Float, val text: String)
        val sortedLines = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
        }.sortedBy { it.y }
        DebugLogger.log(tag, "--- 按Y坐标排序后的文本行 ---")
        sortedLines.forEachIndexed { index, lineInfo ->
            DebugLogger.log(tag, "行[$index] Y=${lineInfo.y.toInt()} X=${lineInfo.x.toInt()} Text='${lineInfo.text}'")
        }
        data class NumLineInfo(val y: Float, val text: String, val nums: List<String>)
        val numLines = sortedLines.mapNotNull { lineInfo ->
            val matches = Regex("""\d{1,4}\.\d{1,2}|\d{1,4}""").findAll(lineInfo.text).map { it.value }.toList()
            if (matches.isNotEmpty()) {
                NumLineInfo(lineInfo.y, lineInfo.text, matches)
            } else null
        }
        DebugLogger.log(tag, "--- 提取到的含数字行 ---")
        numLines.forEachIndexed { index, numLine ->
            DebugLogger.log(tag, "数字行[$index] Y=${numLine.y.toInt()} Text='${numLine.text}' Nums=${numLine.nums}")
        }
        if (numLines.isEmpty()) {
            DebugLogger.log(tag, "未提取到任何数字，匹配终止")
            return@withContext emptyMap()
        }
        val results = mutableMapOf<String, String>()
        val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
        if (relativeRois.isEmpty()) {
            DebugLogger.log(tag, "未找到当前屏幕的相对坐标配置，无法获取字段列表")
            return@withContext emptyMap()
        }
        when (screenIndex) {
            0, 1 -> {
                val tempNums = mutableListOf<NumLineInfo>()
                var pressureNum: NumLineInfo? = null
                for (numLine in numLines) {
                    val lowerText = numLine.text.lowercase()
                    when {
                        lowerText.contains("kpag") || lowerText.contains("kpa") || lowerText.contains("mpa") -> {
                            pressureNum = numLine
                        }
                        lowerText.contains("c") || lowerText.contains("℃") -> {
                            tempNums.add(numLine)
                        }
                    }
                }
                DebugLogger.log(tag, "分类结果 - 温度行数量: ${tempNums.size}, 压力行: ${pressureNum?.text ?: "无"}")
                for (roi in relativeRois) {
                    val label = roi.label
                    when {
                        label.contains("冷媒压力") || label.contains("制冷剂压力") -> {
                            pressureNum?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
                            }
                        }
                        label.contains("进水温度") || label.contains("回水温度") -> {
                            tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
                            }
                        }
                        label.contains("出水温度") -> {
                            tempNums.getOrNull(1)?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
                            }
                        }
                        label.contains("饱和温度") || label.contains("蒸发温度") || label.contains("冷凝温度") -> {
                            tempNums.getOrNull(2)?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
                            }
                        }
                    }
                }
            }
            2 -> {
                var pressureNum: NumLineInfo? = null
                var currentNum: NumLineInfo? = null
                val tempNums = mutableListOf<NumLineInfo>()
                for (numLine in numLines) {
                    val lowerText = numLine.text.lowercase()
                    when {
                        lowerText.contains("kpag") || lowerText.contains("kpa") || lowerText.contains("mpa") -> pressureNum = numLine
                        lowerText.contains("amps") || lowerText.contains("amp") -> currentNum = numLine
                        lowerText.contains("c") || lowerText.contains("℃") -> tempNums.add(numLine)
                        else -> {
                            // 纯数字行用于匹配 %RLA
                        }
                    }
                }
                DebugLogger.log(tag, "分类结果 - 温度行: ${tempNums.size}, 压力行: ${pressureNum?.text ?: "无"}, 电流行: ${currentNum?.text ?: "无"}")
                for (roi in relativeRois) {
                    val label = roi.label
                    when {
                        label.contains("油压") -> {
                            pressureNum?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
                            }
                        }
                        label.contains("电流") -> {
                            currentNum?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it (取L1)")
                            }
                        }
                        label.contains("排出") || label.contains("排气") -> {
                            tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
                            }
                        }
                        label.contains("负载") || label.contains("RLA") -> {
                            val rlaLine = numLines.firstOrNull {
                                it.text.matches(Regex("""[\d\.\s]+""")) && it.nums.isNotEmpty()
                            }
                            rlaLine?.nums?.firstOrNull()?.let {
                                results[roi.fieldId] = it
                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
                            }
                        }
                    }
                }
            }
        }
        DebugLogger.log(tag, "最终提取结果: $results")
        return@withContext results
    }
}
