// 文件名: OCRFacade.kt
package com.zhongshan.meterreader
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.util.BinarizeResourcePool
import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class ImageSource { CAMERA, GALLERY }

object OCRFacade {

    // 1. 【性能绝杀】单例化 ML Kit 客户端，彻底消除帧循环内的 JNI 获取开销
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    
    private val defaultRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val cvEnhancePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val contrast = 1.5f
            val brightness = -15f
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )))
        }
    }

    // 2. 【底层绝杀】协程安全的 Bitmap 复用池，彻底抛弃危险的 ThreadLocal
    private val bitmapPoolMutex = Mutex()
    private var cachedEnhancedBitmap: Bitmap? = null

    /**
     * 阶段二/四：无感视频流识别接口
     */
    suspend fun performStreamOcr(
        imageProxy: ImageProxy,
        template: DeviceTemplate,
        screenIndex: Int,
        resourcePool: BinarizeResourcePool
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val intermediates = ArrayList<Bitmap>()
        try {
            val rawBitmap: Bitmap = try {
                imageProxy.toBitmap()
            } catch (e: Throwable) {
                DebugLogger.log("StreamOCR", "toBitmap 失败: ${e.javaClass.simpleName} ${e.message}")
                return@withContext emptyMap()
            } ?: return@withContext emptyMap()
            
            if (rawBitmap.width <= 0 || rawBitmap.height <= 0) return@withContext emptyMap()
            intermediates.add(rawBitmap)

            val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Throwable) { 0 }
            val rotatedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rb = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rb !== rawBitmap) intermediates.add(rb)
                rb
            } else rawBitmap

            val targetWidth = 1080
            val finalBitmap = if (rotatedBitmap.width < targetWidth) {
                val scale = targetWidth.toFloat() / rotatedBitmap.width
                val sb = Bitmap.createScaledBitmap(
                    rotatedBitmap, targetWidth,
                    (rotatedBitmap.height * scale).toInt().coerceAtLeast(1), true
                )
                if (sb !== rotatedBitmap) intermediates.add(sb)
                sb
            } else rotatedBitmap

            val ocrResult = try {
                when {
                    template.machineId.startsWith("york") ->
                        extractYorkDataFromBitmap(finalBitmap, "StreamOCR")
                    template.isHeatExchanger -> {
                        val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                        OCREngine.extractPlateData(finalBitmap, template.roomId == 1, plateKeywordMap)
                    }
                    else ->
                        extractScrewDataFromBitmap(finalBitmap, template, screenIndex, "StreamOCR")
                }
            } catch (oom: OutOfMemoryError) {
                System.gc()
                emptyMap()
            }
            return@withContext ocrResult
            
        // 3. 【架构绝杀】绝不吞噬 CancellationException，保证协程生命周期纯洁性
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext emptyMap()
        } finally {
            for (b in intermediates) {
                try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
            }
        }
    }

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
            when {
                template.machineId.startsWith("york") ->
                    return@withContext extractYorkDataFromBitmap(bitmap, "SmartOCR")
                template.isHeatExchanger -> {
                    val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                    return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
                }
                else ->
                    return@withContext extractScrewDataFromBitmap(bitmap, template, screenIndex, "SmartOCR")
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun extractScrewDataFromBitmap(
        bitmap: Bitmap,
        template: DeviceTemplate,
        screenIndex: Int,
        tag: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionResult = defaultRecognizer.process(image).await()
        val lines = visionResult.textBlocks.flatMap { it.lines }
        
        data class LineInfo(val y: Float, val x: Float, val text: String)
        val sortedLines = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
        }.sortedBy { it.y }
        
        data class NumLineInfo(val y: Float, val text: String, val nums: List<String>)
        val numLines = sortedLines.mapNotNull { lineInfo ->
            val matches = Regex("""\d{1,4}\.\d{1,2}|\d{1,4}""").findAll(lineInfo.text).map { it.value }.toList()
            if (matches.isNotEmpty()) { NumLineInfo(lineInfo.y, lineInfo.text, matches) } else null
        }
        
        if (numLines.isEmpty()) { return@withContext emptyMap() }
        
        val results = mutableMapOf<String, String>()
        val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
        if (relativeRois.isEmpty()) { return@withContext emptyMap() }
        
        when (screenIndex) {
            0, 1 -> {
                val tempNums = mutableListOf<NumLineInfo>()
                var pressureNum: NumLineInfo? = null
                for (numLine in numLines) {
                    val lowerText = numLine.text.lowercase()
                    when {
                        lowerText.contains("kpag") || lowerText.contains("kpa") || lowerText.contains("mpa") -> { pressureNum = numLine }
                        lowerText.contains("c") || lowerText.contains("℃") -> { tempNums.add(numLine) }
                    }
                }
                for (roi in relativeRois) {
                    val label = roi.label
                    when {
                        label.contains("冷媒压力") || label.contains("制冷剂压力") -> {
                            pressureNum?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
                        }
                        label.contains("进水温度") || label.contains("回水温度") -> {
                            tempNums.getOrNull(0)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
                        }
                        label.contains("出水温度") -> {
                            tempNums.getOrNull(1)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
                        }
                        label.contains("饱和温度") || label.contains("蒸发温度") || label.contains("冷凝温度") -> {
                            tempNums.getOrNull(2)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
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
                    }
                }
                for (roi in relativeRois) {
                    val label = roi.label
                    when {
                        label.contains("油压") -> { pressureNum?.nums?.firstOrNull()?.let { results[roi.fieldId] = it } }
                        label.contains("电流") -> { currentNum?.nums?.firstOrNull()?.let { results[roi.fieldId] = it } }
                        label.contains("排出") || label.contains("排气") -> { tempNums.getOrNull(0)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it } }
                        label.contains("负载") || label.contains("RLA") -> {
                            val rlaLine = numLines.firstOrNull { it.text.matches(Regex("""[\d\.\s]+""")) && it.nums.isNotEmpty() }
                            rlaLine?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
                        }
                    }
                }
            }
        }
        return@withContext results
    }

    // =====================================================================
    // 约克机组终极解析引擎 (V-Omniscience 全知全能版)
    // 修复：协程池竞态死锁、Cancellation 泄漏，保留业务与数学双重铁律
    // =====================================================================
    private suspend fun extractYorkDataFromBitmap(
        bitmap: Bitmap,
        tag: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        DebugLogger.log(tag, "开始约克机组超体原图识别，尺寸: ${bitmap.width}x${bitmap.height}")

        // 4. 【协程锁增强】在安全挂起域内获取、复用并绘制 Bitmap，保证独占绘制权！
        val targetForMLKit = bitmapPoolMutex.withLock {
            if (cachedEnhancedBitmap == null || cachedEnhancedBitmap!!.width != bitmap.width || cachedEnhancedBitmap!!.height != bitmap.height) {
                cachedEnhancedBitmap?.recycle()
                cachedEnhancedBitmap = try {
                    Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    null
                }
            }
            
            if (cachedEnhancedBitmap != null) {
                val canvas = Canvas(cachedEnhancedBitmap!!)
                canvas.drawBitmap(bitmap, 0f, 0f, cvEnhancePaint)
                cachedEnhancedBitmap!!
            } else {
                bitmap
            }
        }

        val image = InputImage.fromBitmap(targetForMLKit, 0)
        val visionResult = chineseRecognizer.process(image).await()

        val lines = visionResult.textBlocks.flatMap { it.lines }
        if (lines.isEmpty()) return@withContext emptyMap()

        val midX = bitmap.width / 2f
        val dirtyNumRegex = Regex("""-?[0-9OoIl]+[,。.]?[0-9OoIl]*""")
        
        // 防突变基因锁
        val cleanNumberText = { dirtyStr: String ->
            if (!dirtyStr.any { it.isDigit() }) null
            else dirtyStr.replace("O", "0").replace("o", "0")
                     .replace("I", "1").replace("l", "1")
                     .replace(",", ".").replace("。", ".")
        }

        // 【正则绝杀】过滤干扰：先去空格再判断，彻底杜绝 ML Kit 乱加空格导致断裂
        val validLines = lines.filter { line ->
            val cleaned = line.text.replace(" ", "").lowercase()
            val hasChinese = cleaned.any { it.code > 0x4E00 && it.code < 0x9FFF }
            val isKeyboardNoise = cleaned.matches(Regex("[A-Za-z0-9]+")) && cleaned.length < 5
            hasChinese && !isKeyboardNoise && !cleaned.contains("设定")
        }

        enum class ValueType { PRESSURE, TEMPERATURE, CURRENT, PERCENTAGE }
        data class YorkRule(val key: String, val label: String, val coreChars: List<String>, val unit: String, val runRange: ClosedFloatingPointRange<Float>, val type: ValueType, val preferredSide: Char)

        // 业务铁律防火墙
        val rules = listOf(
            YorkRule("condRefPressure", "冷凝器压力", listOf("冷凝"), "kpag", 800f..2000f, ValueType.PRESSURE, '?'),
            YorkRule("evapRefPressure", "蒸发器压力", listOf("蒸发", "素发", "发證"), "kpag", 300f..600f, ValueType.PRESSURE, '?'),
            YorkRule("compOilPressure", "油压差", listOf("油压"), "kpa", 300f..1000f, ValueType.PRESSURE, '?'),
            YorkRule("evapTemp", "蒸发器饱和温度", listOf("蒸发", "素发"), "c", 0f..15f, ValueType.TEMPERATURE, 'L'),
            YorkRule("condTemp", "冷凝器饱和温度", listOf("冷凝"), "c", 25f..55f, ValueType.TEMPERATURE, 'R'),
            YorkRule("compOilTemp", "油温", listOf("油温", "油箱温度", "油溫"), "c", 20f..80f, ValueType.TEMPERATURE, '?'),
            YorkRule("compDischargeTemp", "压缩机出口温度", listOf("压缩机", "出口", "排口"), "c", 40f..100f, ValueType.TEMPERATURE, '?'),
            YorkRule("compGuideOpening", "滑阀位置", listOf("滑阀"), "%", 1f..100f, ValueType.PERCENTAGE, '?'),
            YorkRule("motorCurrent", "满载安培", listOf("满载", "电流", "安培"), "%", 1f..120f, ValueType.PERCENTAGE, '?')
        )

        val results = mutableMapOf<String, String>()

        // 【数学绝杀】确定性候选排序器
        data class MatchCandidate(val value: Float, val distance: Float, val hasUnit: Boolean) : Comparable<MatchCandidate> {
            override fun compareTo(other: MatchCandidate): Int {
                if (this.hasUnit && !other.hasUnit) return -1
                if (!this.hasUnit && other.hasUnit) return 1
                return this.distance.compareTo(other.distance)
            }
        }

        for (rule in rules) {
            val keyLine = validLines.mapNotNull { line ->
                val cleaned = line.text.replace(" ", "").lowercase()
                if (!rule.coreChars.any { cleaned.contains(it) }) return@mapNotNull null
                var score = 100f
                val centerX = line.boundingBox?.exactCenterX() ?: 0f
                if (rule.preferredSide == 'L' && centerX >= midX) score -= 30f
                if (rule.preferredSide == 'R' && centerX < midX) score -= 30f
                score to line
            }.maxByOrNull { it.first }?.second ?: continue

            val keyBox = keyLine.boundingBox ?: continue

            val validateValue = { cleanNumStr: String, lineTextWithoutSpace: String ->
                val v = cleanNumStr.toFloatOrNull()
                if (v != null && v in rule.runRange) {
                    val isValidState = when (rule.type) {
                        ValueType.TEMPERATURE -> v >= 0f 
                        else -> v > 0f
                    }
                    val hasUnit = rule.unit.isEmpty() || lineTextWithoutSpace.contains(rule.unit)
                    if (isValidState) Pair(v, hasUnit) else null
                } else null
            }

            var finalNumber: Float? = null
            val keyLineTextClean = keyLine.text.replace(" ", "").lowercase()

            val inlineMatch = dirtyNumRegex.findAll(keyLineTextClean).mapNotNull {
                val cleanStr = cleanNumberText(it.value)
                if (cleanStr != null) validateValue(cleanStr, keyLineTextClean) else null
            }.firstOrNull()

            if (inlineMatch != null) {
                finalNumber = inlineMatch.first
            }

            if (finalNumber == null) {
                finalNumber = validLines
                    .filter { it != keyLine && it.boundingBox != null }
                    .mapNotNull { line ->
                        val lineBox = line.boundingBox!!
                        val isRight = lineBox.left >= keyBox.right && abs(lineBox.exactCenterY() - keyBox.exactCenterY()) < keyBox.height()
                        val isBelow = lineBox.top >= keyBox.bottom && (lineBox.top - keyBox.bottom) < keyBox.height() * 3.0

                        if (isRight || isBelow) {
                            val dx = lineBox.exactCenterX() - keyBox.exactCenterX()
                            val dy = lineBox.exactCenterY() - keyBox.exactCenterY()
                            val distance = dx * dx + dy * dy
                            
                            val neighborLineClean = line.text.replace(" ", "").lowercase()
                            val rawDirtyNum = dirtyNumRegex.find(neighborLineClean)?.value
                            val cleanStr = if (rawDirtyNum != null) cleanNumberText(rawDirtyNum) else null
                            val validationResult = if (cleanStr != null) validateValue(cleanStr, neighborLineClean) else null
                            
                            if (validationResult != null) {
                                MatchCandidate(validationResult.first, distance, validationResult.second)
                            } else null
                        } else null
                    }
                    .minOrNull()?.value
            }

            if (finalNumber != null) {
                results["${rule.key}|${rule.label}"] = finalNumber.toString()
            }
        }
        DebugLogger.log(tag, "约克最终提取结果: $results")
        return@withContext results
    }
}
