
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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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

            if (rawBitmap.width <= 0 || rawBitmap.height <= 0) return@withContext emptyMap()
            intermediates.add(rawBitmap)
            val rawW = rawBitmap.width
            val rawH = rawBitmap.height

            val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Throwable) { 0 }
            val rotatedBitmap: Bitmap = if (rotation != 0) {
                try {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rb = Bitmap.createBitmap(rawBitmap, 0, 0, rawW, rawH, matrix, true)
                    if (rb !== rawBitmap) intermediates.add(rb)
                    rb
                } catch (e: Throwable) { rawBitmap }
            } else { rawBitmap }

            if (rotatedBitmap.width <= 0 || rotatedBitmap.height <= 0) return@withContext emptyMap()

            val targetWidth = 1080
            val finalBitmap: Bitmap = if (rotatedBitmap.width < targetWidth) {
                try {
                    val scale = targetWidth.toFloat() / rotatedBitmap.width
                    val sb = Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, (rotatedBitmap.height * scale).toInt().coerceAtLeast(1), true)
                    if (sb !== rotatedBitmap) intermediates.add(sb)
                    sb
                } catch (e: Throwable) { rotatedBitmap }
            } else { rotatedBitmap }

            if (finalBitmap.width <= 0 || finalBitmap.height <= 0) return@withContext emptyMap()

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
                System.gc(); emptyMap()
            } catch (e: Throwable) {
                emptyMap()
            }

            return@withContext ocrResult
        } catch (e: Throwable) {
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
     * 约克机组 OCR 解析引擎 (重构版：基于坐标方位与模糊匹配)
     */
    private suspend fun extractYorkDataFromBitmap(bitmap: Bitmap, tag: String): Map<String, String> = withContext(Dispatchers.IO) {
        DebugLogger.log(tag, "开始约克机组原图识别，尺寸: ${bitmap.width}x${bitmap.height}")
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val visionResult = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = visionResult.textBlocks.flatMap { it.lines }
        DebugLogger.log(tag, "ML Kit(中文) 原始识别到 ${lines.size} 行文本")

        data class LineInfo(val y: Float, val x: Float, val text: String)
        val sortedLines = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
        }.sortedBy { it.y }

        // 【修复-可观测性】补回按 Y 排序后的逐行原始文本日志（此前重构时被去掉了）。
        // 没有这份逐行日志，"满载安培到底有没有被 ML Kit 识别到"这类问题只能靠猜；
        // 有了它，下次导出日志能直接看到具体是哪一行、识别成了什么文本。
        DebugLogger.log(tag, "--- 约克 按Y排序后文本行 ---")
        sortedLines.forEachIndexed { idx, l ->
            DebugLogger.log(tag, "约克行[$idx] Y=${l.y.toInt()} X=${l.x.toInt()} Text=${l.text}")
        }

        if (sortedLines.isEmpty()) {
            DebugLogger.log(tag, "约克：未识别到任何文本行")
            return@withContext emptyMap()
        }

        val centerX = bitmap.width / 2f
        data class NumInfo(val line: LineInfo, val nums: List<String>)
        val allNums = sortedLines.mapNotNull { line ->
            val nums = Regex("""-?\d{1,4}(\.\d{1,2})?""").findAll(line.text).map { it.value }.toList()
            if (nums.isNotEmpty()) NumInfo(line, nums) else null
        }

        val results = mutableMapOf<String, String>()
        val usedNums = mutableSetOf<NumInfo>()

        fun isLeft(x: Float) = x < centerX
        fun isRight(x: Float) = x >= centerX
        fun isTempText(text: String) = text.uppercase().contains("C") || text.contains("℃")
        fun isPressureText(text: String) = text.lowercase().contains("kpa")

        // 智能修复丢失的小数点 (例如 439C -> 43.9C， 78C -> 7.8C)
        // 【修复】原来用"数值>50"判断是否需要补小数点，导致蒸发器饱和温度这类本来就
        // 常年在个位数~十位数（如 3.0°C 被读成 30）的读数永远触发不了修复。本屏幕所有
        // 温度字段固定只显示1位小数，只要 OCR 把小数点整个漏读了，数字本身的每一位
        // 都还在、只是小数点消失，与数值大小无关，所以改成：只要没有小数点、且至少有
        // 2位数字，就统一在末位前补上小数点；不再按量级判断。
        fun fixTempNum(numStr: String?): String? {
            if (numStr == null) return null
            if (numStr.contains(".")) return numStr
            return try {
                numStr.toDouble() // 仅用于校验这确实是个合法数字，校验失败走 catch 原样返回
                if (numStr.replace("-", "").length >= 2) {
                    val insertAt = numStr.length - 1
                    numStr.substring(0, insertAt) + "." + numStr.substring(insertAt)
                } else {
                    numStr
                }
            } catch (e: Exception) {
                numStr
            }
        }

        fun getNumFromLine(line: LineInfo, mustHaveC: Boolean = false, mustHaveKpa: Boolean = false, mustBePureNum: Boolean = false): String? {
            if ((mustHaveC && !isTempText(line.text)) || (mustHaveKpa && !isPressureText(line.text))) return null
            if (mustBePureNum && (isTempText(line.text) || isPressureText(line.text))) return null
            val nums = Regex("""-?\d{1,4}(\.\d{1,2})?""").findAll(line.text).map { it.value }.toList()
            if (nums.isNotEmpty()) {
                allNums.find { it.line == line }?.let { usedNums.add(it) }
                return nums.firstOrNull()
            }
            return null
        }

        fun findNumBelow(labelY: Float, side: Char, mustHaveC: Boolean = false, mustHaveKpa: Boolean = false, mustBePureNum: Boolean = false): String? {
            val candidates = allNums.filter { it.line.y > labelY && (side == '?' || (side == 'L' && isLeft(it.line.x)) || (side == 'R' && isRight(it.line.x))) }
                .filter { !mustHaveC || isTempText(it.line.text) }
                .filter { !mustHaveKpa || isPressureText(it.line.text) }
                .filter { !mustBePureNum || (!isTempText(it.line.text) && !isPressureText(it.line.text)) }
            val found = candidates.minByOrNull { it.line.y } ?: return null
            usedNums.add(found)
            return found.nums.firstOrNull()
        }

        fun putResult(key: String, value: String?) {
            if (value != null) {
                results[key] = value
            }
        }

        // 1. 提取压力 (冷凝器左，蒸发器右，油压全屏)
        for (line in sortedLines) {
            if (line.text.contains("油") && isPressureText(line.text)) {
                putResult("compOilPressure|油压", getNumFromLine(line, mustHaveKpa = true) ?: findNumBelow(line.y, '?', mustHaveKpa = true))
            }
            if (line.text.contains("冷凝") && isPressureText(line.text)) {
                putResult("condRefPressure|冷凝器压力", getNumFromLine(line, mustHaveKpa = true) ?: findNumBelow(line.y, 'L', mustHaveKpa = true))
            }
            if ((line.text.contains("蒸发") || line.text.contains("发")) && isPressureText(line.text) && !line.text.contains("冷凝")) {
                putResult("evapRefPressure|蒸发器压力", getNumFromLine(line, mustHaveKpa = true) ?: findNumBelow(line.y, 'R', mustHaveKpa = true))
            }
        }

        // 2. 提取压缩机温度和饱和温度 (应用温度小数点修复)
        for (line in sortedLines) {
            if (line.text.contains("油") && isTempText(line.text)) {
                putResult("compOilTemp|油温", fixTempNum(getNumFromLine(line, mustHaveC = true) ?: findNumBelow(line.y, '?', mustHaveC = true)))
            }
            if (line.text.contains("压缩") || line.text.contains("出口")) {
                putResult("compDischargeTemp|压缩机出口温度", fixTempNum(getNumFromLine(line, mustHaveC = true) ?: findNumBelow(line.y, '?', mustHaveC = true)))
            }
            if (line.text.contains("冷凝") && line.text.contains("饱和")) {
                putResult("condTemp|冷凝器饱和温度", fixTempNum(getNumFromLine(line, mustHaveC = true) ?: findNumBelow(line.y, 'L', mustHaveC = true)))
            }
            if ((line.text.contains("蒸发") || line.text.contains("发")) && line.text.contains("饱和")) {
                putResult("evapTemp|蒸发器饱和温度", fixTempNum(getNumFromLine(line, mustHaveC = true) ?: findNumBelow(line.y, 'R', mustHaveC = true)))
            }
        }

        // 3. 提取水温 (基于剩余的带C的数字行，应用温度小数点修复)
        val remainingTempNums = allNums.filter { it !in usedNums && isTempText(it.line.text) }
        val leftTemps = remainingTempNums.filter { isLeft(it.line.x) }.sortedBy { it.line.y }
        val rightTemps = remainingTempNums.filter { isRight(it.line.x) }.sortedBy { it.line.y }

        if (leftTemps.isNotEmpty()) putResult("condOutTemp|冷却水温度出水", fixTempNum(leftTemps[0].nums.firstOrNull()))
        if (leftTemps.size > 1) putResult("condInTemp|冷却水温度返回", fixTempNum(leftTemps[1].nums.firstOrNull()))

        if (rightTemps.isNotEmpty()) putResult("evapOutTemp|冷冻水温度出水", fixTempNum(rightTemps[0].nums.firstOrNull()))
        if (rightTemps.size > 1) putResult("evapInTemp|冷冻水温度返回", fixTempNum(rightTemps[1].nums.firstOrNull()))

        // 4. 提取滑阀和满载安培
        for (line in sortedLines) {
            if (line.text.contains("滑阀") || line.text.contains("滑")) {
                putResult("compGuideOpening|滑阀位置", getNumFromLine(line, mustBePureNum = true) ?: findNumBelow(line.y, '?', mustBePureNum = true))
            }
            if (line.text.contains("安培") || line.text.contains("满载")) {
                putResult("motorCurrent|满载安培", getNumFromLine(line, mustBePureNum = true) ?: findNumBelow(line.y, '?', mustBePureNum = true))
            }
        }

        // 5. 百分比兜底策略
        // 【修复】原策略要求候选行必须含字面"%"，但实测 ML Kit 在本机型上经常把"%"
        // 读丢或读错（"电流限制设定值95%"被读成"95 2"，"滑阀位置82%"干脆读成裸数字
        // "82"、连"滑阀位置"四个字都没识别到），导致 percentNums 经常是空的，兜底形同虚设。
        // 改为不再要求"%"，只要求：不含小数点的纯整数、数值落在 0-100 合理区间、且在
        // 屏幕右侧（%满载安培/电流限制设定值/滑阀位置三者原本就同列于右上角，从上到下
        // 依次为 满载安培→电流限制设定值→滑阀位置）。
        // 同时修正原代码里 percentNums[1] 的下标错位：按 Y 降序时 [0]=最下面=滑阀位置没错，
        // 但 [1] 实际是中间的"电流限制设定值"而不是满载安培，会把 95 误填进电流字段。
        // 现在改为排序后取"首尾"（最上面给满载安培、最下面给滑阀位置），不用假设候选
        // 数量正好是 2 或 3 个，中间那条"电流限制设定值"有没有被识别到都不影响取值。
        if (results["compGuideOpening|滑阀位置"] == null || results["motorCurrent|满载安培"] == null) {
            val percentCandidates = allNums.filter { info ->
                info !in usedNums &&
                    isRight(info.line.x) &&
                    !isTempText(info.line.text) &&
                    !isPressureText(info.line.text) &&
                    info.nums.firstOrNull()?.let { n ->
                        !n.contains(".") && (n.toIntOrNull() ?: -1) in 0..100
                    } == true
            }.sortedBy { it.line.y }

            if (results["compGuideOpening|滑阀位置"] == null) {
                percentCandidates.lastOrNull { it !in usedNums }?.let {
                    putResult("compGuideOpening|滑阀位置", it.nums.firstOrNull())
                    usedNums.add(it)
                }
            }
            if (results["motorCurrent|满载安培"] == null) {
                percentCandidates.firstOrNull { it !in usedNums }?.let {
                    putResult("motorCurrent|满载安培", it.nums.firstOrNull())
                    usedNums.add(it)
                }
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
