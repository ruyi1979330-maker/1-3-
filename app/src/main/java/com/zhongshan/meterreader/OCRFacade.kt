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



阶段二 / 四：无感视频流识别接口

*/

suspend fun performStreamOcr (

imageProxy: ImageProxy,

template: DeviceTemplate,

screenIndex: Int,

resourcePool: BinarizeResourcePool

): Map<String, String> = withContext (Dispatchers.IO) {

val startTs = System.currentTimeMillis ()

val intermediates = ArrayList<Bitmap>()</bitmap>

try {

val rawBitmap: Bitmap = try {

imageProxy.toBitmap ()

} catch (e: Throwable) {

DebugLogger.log ("StreamOCR", "toBitmap 失败: ${e.javaClass.simpleName} ${e.message}")

return@withContext emptyMap ()

} ?: return@withContext emptyMap ()

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


兼容原相册模式的单张图片识别接口

*/

suspend fun performSmartOcr (

context: Context,

imageUri: Uri,

template: DeviceTemplate,

screenIndex: Int,

source: ImageSource,

resourcePool: BinarizeResourcePool

): Map<String, String> = withContext (Dispatchers.IO) {

val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely (context, imageUri)

if (bitmap == null) {

withContext (Dispatchers.Main) { Toast.makeText (context, "图片加载失败", Toast.LENGTH_LONG).show () }

return@withContext emptyMap ()

}

// 【修复】彻底删除缩放逻辑，原图直出，避免 ML Kit 拆行导致大面积识别缺失

try {

if (template.machineId.startsWith ("york")) {

return@withContext extractYorkDataFromBitmap (bitmap, "SmartOCR")

} else if (template.isHeatExchanger) {

val plateKeywordMap = TemplateManager.getPlateKeywordMap (template.roomId)

return@withContext OCREngine.extractPlateData (bitmap, template.roomId == 1, plateKeywordMap)

}

return@withContext extractScrewDataFromBitmap (bitmap, template, screenIndex, "SmartOCR")

} finally {

bitmap.recycle ()

}

}


/**



约克机组 OCR 解析引擎 (重构版：基于坐标方位与模糊匹配)

*/

private suspend fun extractYorkDataFromBitmap (bitmap: Bitmap, tag: String): Map<String, String> = withContext (Dispatchers.IO) {

DebugLogger.log (tag, " 开始约克机组原图识别，尺寸: 
bitmap.widthx
{bitmap.height}")

val recognizer = TextRecognition.getClient (ChineseTextRecognizerOptions.Builder ().build ())

val visionResult = recognizer.process (InputImage.fromBitmap (bitmap, 0)).await ()

val lines = visionResult.textBlocks.flatMap { it.lines }

DebugLogger.log (tag, "ML Kit (中文) 原始识别到 ${lines.size} 行文本")

data class LineInfo(val y: Float, val x: Float, val text: String)

val sortedLines = lines.mapNotNull { line ->

val box = line.boundingBox ?: return@mapNotNull null

LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())

}.sortedBy { it.y }

DebugLogger.log (tag, "--- 约克 按 Y 排序后文本行 ---")

sortedLines.forEachIndexed { idx, l ->

DebugLogger.log (tag, " 约克行 [
idx]Y=
{l.y.toInt()} X=
l.x.toInt()Text=
{l.text}")

}

if (sortedLines.isEmpty ()) {

DebugLogger.log (tag, "约克：未识别到任何文本行")

return@withContext emptyMap ()

}

val centerX = bitmap.width / 2f

data class NumInfo(val line: LineInfo, val nums: List<String>)

val allNums = sortedLines.mapNotNull { line ->

val nums = Regex("""-?\d{1,4}(.\d{1,2})?""").findAll(line.text).map { it.value }.toList()

if (nums.isNotEmpty()) NumInfo(line, nums) else null

}</string>

val results = mutableMapOf<String, String>()

val usedNums = mutableSetOf<NumInfo>()</numinfo>

fun isLeft(x: Float) = x < centerX

fun isRight(x: Float) = x >= centerX

fun isTempText(text: String) = text.uppercase().contains("C") || text.contains("℃")

fun isPressureText(text: String) = text.lowercase().contains("kpa")

fun fixTempNum(numStr: String?): String? {

if (numStr == null) return null

if (numStr.contains(".")) return numStr

return try {

numStr.toDouble()

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

val nums = Regex("""-?\d{1,4}(.\d{1,2})?""").findAll(line.text).map { it.value }.toList()

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

if (line.text.contains ("油") && isPressureText (line.text)) {

putResult ("compOilPressure | 油压", getNumFromLine (line, mustHaveKpa = true) ?: findNumBelow (line.y, '?', mustHaveKpa = true))

}

if (line.text.contains ("冷凝") && isPressureText (line.text)) {

putResult ("condRefPressure | 冷凝器压力", getNumFromLine (line, mustHaveKpa = true) ?: findNumBelow (line.y, 'L', mustHaveKpa = true))

}

if ((line.text.contains ("蒸发") || line.text.contains ("发")) && isPressureText (line.text) && !line.text.contains ("冷凝")) {

putResult ("evapRefPressure | 蒸发器压力", getNumFromLine (line, mustHaveKpa = true) ?: findNumBelow (line.y, 'R', mustHaveKpa = true))

}

}

// 2. 提取压缩机温度和饱和温度 (应用温度小数点修复)

for (line in sortedLines) {

if (line.text.contains ("油") && isTempText (line.text)) {

putResult ("compOilTemp | 油温", fixTempNum (getNumFromLine (line, mustHaveC = true) ?: findNumBelow (line.y, '?', mustHaveC = true)))

}

if (line.text.contains ("压缩") || line.text.contains ("出口")) {

putResult ("compDischargeTemp | 压缩机出口温度", fixTempNum (getNumFromLine (line, mustHaveC = true) ?: findNumBelow (line.y, '?', mustHaveC = true)))

}

if (line.text.contains ("冷凝") && line.text.contains ("饱和")) {

putResult ("condTemp | 冷凝器饱和温度", fixTempNum (getNumFromLine (line, mustHaveC = true) ?: findNumBelow (line.y, 'L', mustHaveC = true)))

}

if ((line.text.contains ("蒸发") || line.text.contains ("发")) && line.text.contains ("饱和")) {

putResult ("evapTemp | 蒸发器饱和温度", fixTempNum (getNumFromLine (line, mustHaveC = true) ?: findNumBelow (line.y, 'R', mustHaveC = true)))

}

}

// 3. 提取水温 (基于剩余的带 C 的数字行，应用温度小数点修复)

val remainingTempNums = allNums.filter { it !in usedNums && isTempText (it.line.text) }

val leftTemps = remainingTempNums.filter { isLeft (it.line.x) }.sortedBy { it.line.y }

val rightTemps = remainingTempNums.filter { isRight (it.line.x) }.sortedBy { it.line.y }

if (leftTemps.isNotEmpty ()) putResult ("condOutTemp | 冷却水温度出水", fixTempNum (leftTemps [0].nums.firstOrNull ()))

if (leftTemps.size > 1) putResult ("condInTemp | 冷却水温度返回", fixTempNum (leftTemps [1].nums.firstOrNull ()))

if (rightTemps.isNotEmpty ()) putResult ("evapOutTemp | 冷冻水温度出水", fixTempNum (rightTemps [0].nums.firstOrNull ()))

if (rightTemps.size > 1) putResult ("evapInTemp | 冷冻水温度返回", fixTempNum (rightTemps [1].nums.firstOrNull ()))

// 4. 提取滑阀和满载安培 (【关键修复】移除 findNumBelow，防止误抓下方的设定值 95)

for (line in sortedLines) {

if (line.text.contains ("滑阀") || line.text.contains ("滑")) {

putResult ("compGuideOpening | 滑阀位置", getNumFromLine (line, mustBePureNum = true))

}

if (line.text.contains ("安培") || line.text.contains ("满载")) {

putResult ("motorCurrent | 满载安培", getNumFromLine (line, mustBePureNum = true))

}

}

// 5. 百分比兜底策略 (利用设定值作为锚点，上下精准分割)

fun String.containsAny (keywords: List<String>): Boolean = keywords.any {this.contains (it) }

if (results ["compGuideOpening | 滑阀位置"] == null || results ["motorCurrent | 满载安培"] == null) {

val excludeKeywords = listOf ("设定", "限制")

// 找出包含设定 / 限制文字的行 Y 坐标

val settingLabelYs = sortedLines.filter { it.text.containsAny (excludeKeywords) }.map { it.y }

DebugLogger.log (tag, "百分比兜底 - 寻找锚点 (设定 / 限制): Y 坐标列表 =${settingLabelYs}")</string>

val percentCandidates = allNums.filter {info ->

val pass = info !in usedNums &&

isRight (info.line.x) &&

!isTempText (info.line.text) &&

!isPressureText (info.line.text) &&

!info.line.text.containsAny (excludeKeywords) &&

// 排除设定值本身的数据 (如 95)

!settingLabelYs.any { labelY -> info.line.y in labelY..(labelY + 30) } &&

info.nums.firstOrNull ()?.let { n ->

!n.contains (".") && (n.toIntOrNull () ?: -1) in 0..100

} == true

if (!pass && isRight (info.line.x) && !isTempText (info.line.text) && !isPressureText (info.line.text)) {

DebugLogger.log (tag, " 百分比兜底 - 过滤候选: Y=
info.line.y.toInt()X=
{info.line.x.toInt()} Text='
info.line.text 
′
 Nums=
{info.nums}")

}

pass

}.sortedBy { it.line.y }

percentCandidates.forEach {cand ->

DebugLogger.log (tag, " 百分比兜底 - 有效候选: Y=
cand.line.y.toInt()X=
{cand.line.x.toInt()} Text='
cand.line.text 
′
 Nums=
{cand.nums}")

}

if (settingLabelYs.isNotEmpty ()) {

val anchorY = settingLabelYs.first ()

DebugLogger.log (tag, " 百分比兜底 - 使用锚点分割: anchorY=




$anchorY")

// 锚点上方给满载安培

if (results ["motorCurrent | 满载安培"] == null) {

val currentCand = percentCandidates.lastOrNull { it.line.y < anchorY }

DebugLogger.log (tag, "百分比兜底 - 满载安培 (锚点上方): ${if (currentCand != null)" 命中 Y=
值
{currentCand.nums.firstOrNull ()}"else" 未命中 "}")

currentCand?.let {

putResult ("motorCurrent | 满载安培", it.nums.firstOrNull ())

usedNums.add (it)

}

}

// 锚点下方给滑阀位置

if (results ["compGuideOpening | 滑阀位置"] == null) {

val guideCand = percentCandidates.firstOrNull { it.line.y > anchorY }

DebugLogger.log (tag, " 百分比兜底 - 滑阀位置 (锚点下方): {if (guideCand != null) "命中 Y={guideCand.line.y.toInt ()} 值 =











${guideCand.nums.firstOrNull ()}"else" 未命中 "}")

guideCand?.let {

putResult ("compGuideOpening | 滑阀位置", it.nums.firstOrNull ())

usedNums.add (it)

}

}

} else {

// 无锚点退回原逻辑

DebugLogger.log (tag, "百分比兜底 - 无锚点，退回原逻辑")

if (results ["compGuideOpening | 滑阀位置"] == null) {

val guideCand = percentCandidates.lastOrNull { it !in usedNums }

DebugLogger.log (tag, "百分比兜底 - 滑阀位置 (无锚点): ${if (guideCand != null)" 命中 Y=
值
{guideCand.nums.firstOrNull ()}"else" 未命中 "}")

guideCand?.let {

putResult ("compGuideOpening | 滑阀位置", it.nums.firstOrNull ())

usedNums.add (it)

}

}

if (results ["motorCurrent | 满载安培"] == null) {

val currentCand = percentCandidates.firstOrNull { it !in usedNums }

DebugLogger.log (tag, " 百分比兜底 - 满载安培 (无锚点): {if (currentCand != null) "命中 Y={currentCand.line.y.toInt ()} 值 =${currentCand.nums.firstOrNull ()}"else" 未命中 "}")

currentCand?.let {

putResult ("motorCurrent | 满载安培", it.nums.firstOrNull ())

usedNums.add (it)

}

}

}

}

// 6.【新增 - 二次探测】满载安培主流程仍未命中时的兜底

// 走到这里说明：不是过滤条件挑剔 —— 上面 "百分比兜底" 那段日志能看到，

// 锚点上方的候选池里除了日期时间那一行（被小数点规则正确排除）就再没有

// 别的数字了，也就是 ML Kit 在整图这个分辨率下压根没把 "% 满载安培" 这几个

// 字识别成文本。整图层面的匹配规则不可能把没识别到的字变出来，只能在

// "提高这一小块区域的有效分辨率" 上想办法：以已经定位到的 "设定 / 限制"

// 锚点为基准，往上裁一条较窄的区域，放大后单独再送一次 ML Kit 识别；

// 找到 "满载 / 安培" 字样所在行后，再围绕这一行单独裁一次、多放大一级，

// 多一次机会把完整的两位数读全。

// 【安全阀】只接受两位及以上的数字：实测第一轮曾经只抓到过残缺的单个数字

// "6"（真实值是 "68"，丢了一位），这种半个数字如果直接采信、乘以 2.5 倍系数后

// 会变成一个 "看着还算合理但其实完全错误" 的数（15.0 而非真实的 170.0），

// 比留空更危险 —— 留空至少会被人工核对时注意到。

// 只在图库 (SmartOCR) 这一次性识别时做，不放进实时相机流，避免拖慢取景响应。

if (tag == "SmartOCR" && results ["motorCurrent | 满载安培"] == null) {

val anchorYForRetry = sortedLines.firstOrNull { it.text.containsAny (listOf ("设定", "限制")) }?.y

if (anchorYForRetry == null) {

DebugLogger.log (tag, "满载安培 - 二次探测跳过：未找到设定 / 限制锚点，无法定位裁剪区域")

} else {

var level1Crop: Bitmap? = null

var level1Zoom: Bitmap? = null

var level2Crop: Bitmap? = null

var level2Zoom: Bitmap? = null

try {

// 第一级：锚点上方 12% 图高～锚点下方 2% 图高

val top = (anchorYForRetry - bitmap.height * 0.12f).coerceAtLeast (0f).toInt ()

val bottom = (anchorYForRetry + bitmap.height * 0.02f).coerceAtMost (bitmap.height.toFloat ()).toInt ()

val left = centerX.toInt ().coerceIn (0, bitmap.width - 1)

val cropW = (bitmap.width - left).coerceAtLeast (1)

val cropH = (bottom - top).coerceAtLeast (1)

if (cropW > 4 && cropH > 4) {

level1Crop = Bitmap.createBitmap (bitmap, left, top, cropW, cropH)

val scale1 = 4f

level1Zoom = Bitmap.createScaledBitmap (level1Crop, (cropW * scale1).toInt (), (cropH * scale1).toInt (), true)

DebugLogger.log (tag, " 满载安培 - 二次探测 (第一级): 裁剪 (left=
lefttop=
top w=
cropWh=
cropH) 放大
倍
后
{level1Zoom.width}x




{level1Zoom.height}")
                 val level1Lines = recognizer.process(InputImage.fromBitmap(level1Zoom, 0)).await().textBlocks.flatMap { it.lines }
                 level1Lines.forEach {
                     val bx = it.boundingBox
                     DebugLogger.log(tag, "满载安培-第一级识别行: Text={it.text} Box=
bx?.left,
{bx?.top},
bx?.right,
{bx?.bottom}")

}

fun plausibleNum(texts: List<String>): String? =

texts.flatMap { t -> Regex("""-?\d{1,4}(.\d{1,2})?""").findAll(t).map { m -> m.value } }

.firstOrNull { n -> !n.contains(".") && n.length >= 2 && (n.toIntOrNull() ?: -1) in 0..100 }</string>

var finalNum = plausibleNum(level1Lines.map { it.text })

// 第二级：定位到含 "满载 / 安培 / 安增" 字样的那一行，围绕它单独再裁一次、

// 再放大一次，专门针对这一小块争取把完整两位数读出来

val motorLine = level1Lines.firstOrNull {it.text.containsAny (listOf ("安培", "安增", "满载", "载安")) }

val box = motorLine?.boundingBox

if (box != null) {

val padX = (box.width () * 0.5f).toInt ().coerceAtLeast (15)

val padY = (box.height () * 0.8f).toInt ().coerceAtLeast (15)

val l2 = (box.left - padX).coerceAtLeast (0)

val t2 = (box.top - padY).coerceAtLeast (0)

val r2 = (box.right + padX).coerceAtMost (level1Zoom.width)

val b2 = (box.bottom + padY).coerceAtMost (level1Zoom.height)

val w2 = (r2 - l2).coerceAtLeast (1).coerceAtMost (level1Zoom.width - l2)

val h2 = (b2 - t2).coerceAtLeast (1).coerceAtMost (level1Zoom.height - t2)

if (w2 > 4 && h2 > 4) {

level2Crop = Bitmap.createBitmap (level1Zoom, l2, t2, w2, h2)

val scale2 = 3f

level2Zoom = Bitmap.createScaledBitmap (level2Crop, (w2 * scale2).toInt (), (h2 * scale2).toInt (), true)

DebugLogger.log (tag, " 满载安培 - 二次探测 (第二级): 围绕'
再
裁
剪
并
放
大
{scale2} 倍后 =
level2Zoom.widthx
{level2Zoom.height}")

val level2Lines = recognizer.process (InputImage.fromBitmap (level2Zoom, 0)).await ().textBlocks.flatMap { it.lines }

level2Lines.forEach { DebugLogger.log (tag, "满载安培 - 第二级识别行: Text=${it.text}") }

val level2Num = plausibleNum (level2Lines.map { it.text })

if (level2Num != null) finalNum = level2Num // 第二级更聚焦，优先采用

}

}

if (finalNum != null) {

DebugLogger.log (tag, " 满载安培 - 二次探测命中：值 =







$finalNum")

putResult ("motorCurrent | 满载安培", finalNum)

} else {

DebugLogger.log (tag, "满载安培 - 二次探测未拿到可信的两位数读数 (单个数字不采信，避免误填)，建议靠近重拍这个角或人工填写")

}

}

} catch (e: Throwable) {

DebugLogger.log (tag, "满载安培 - 二次探测异常: ${e.javaClass.simpleName} ${e.message}")

} finally {

try { level1Crop?.let { if (!it.isRecycled) it.recycle () } } catch (: Throwable) {}

try { level1Zoom?.let { if (!it.isRecycled) it.recycle() } } catch (: Throwable) {}

try { level2Crop?.let { if (!it.isRecycled) it.recycle() } } catch (: Throwable) {}

try { level2Zoom?.let { if (!it.isRecycled) it.recycle() } } catch (: Throwable) {}

}

}

}

DebugLogger.log (tag, "约克最终提取结果: $results")

return@withContext results

}



/**


特灵机组识别引擎（完全不变）

*/

private suspend fun extractScrewDataFromBitmap (

bitmap: Bitmap,

template: DeviceTemplate,

screenIndex: Int,

tag: String

): Map<String, String> = withContext (Dispatchers.IO) {

DebugLogger.log (tag, " 开始螺杆机原图直接识别，尺寸: 
bitmap.widthx
{bitmap.height}")

val recognizer = TextRecognition.getClient (TextRecognizerOptions.DEFAULT_OPTIONS)

val image = InputImage.fromBitmap (bitmap, 0)

val visionResult = recognizer.process (image).await ()

val lines = visionResult.textBlocks.flatMap { it.lines }

DebugLogger.log (tag, "ML Kit 原始识别到 








{lines.size} 行文本")
 data class LineInfo(val y: Float, val x: Float, val text: String)
 val sortedLines = lines.mapNotNull { line ->
     val box = line.boundingBox ?: return@mapNotNull null
     LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
 }.sortedBy { it.y }
 DebugLogger.log(tag, "--- 按Y坐标排序后的文本行 ---")
 sortedLines.forEachIndexed { index, lineInfo ->
     DebugLogger.log(tag, "行[index] Y=
lineInfo.y.toInt()X=
{lineInfo.x.toInt()} Text='











{lineInfo.text}'")
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
     DebugLogger.log(tag, "数字行[index] Y=
numLine.y.toInt()Text= 
′
 
{numLine.text}' Nums=



























${numLine.nums}")

}

if (numLines.isEmpty ()) {

DebugLogger.log (tag, "未提取到任何数字，匹配终止")

return@withContext emptyMap ()

}

val results = mutableMapOf<String, String>()

val relativeRois = DeviceOcrStrategy.getRelativeRois (template.machineId, screenIndex)

if (relativeRois.isEmpty ()) {

DebugLogger.log (tag, "未找到当前屏幕的相对坐标配置，无法获取字段列表")

return@withContext emptyMap ()

}

when (screenIndex) {

0, 1 -> {

val tempNums = mutableListOf<NumLineInfo>()

var pressureNum: NumLineInfo? = null

for (numLine in numLines) {

val lowerText = numLine.text.lowercase ()

when {

lowerText.contains ("kpag") || lowerText.contains ("kpa") || lowerText.contains ("mpa") -> {

pressureNum = numLine

}

lowerText.contains ("c") || lowerText.contains ("℃") -> {

tempNums.add (numLine)

}

}

}

DebugLogger.log (tag, " 分类结果 - 温度行数量: ${tempNums.size}, 压力行: 







${pressureNum?.text ?: "无"}")

for (roi in relativeRois) {

val label = roi.label

when {

label.contains ("冷媒压力") || label.contains ("制冷剂压力") -> {

pressureNum?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, " 匹配成功: ${roi.label} = 






$it")

}

}

label.contains ("进水温度") || label.contains ("回水温度") -> {

tempNums.getOrNull (0)?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, " 匹配成功: ${roi.label} = 






$it")

}

}

label.contains ("出水温度") -> {

tempNums.getOrNull (1)?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, " 匹配成功: ${roi.label} = 






$it")

}

}

label.contains ("饱和温度") || label.contains ("蒸发温度") || label.contains ("冷凝温度") -> {

tempNums.getOrNull (2)?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, " 匹配成功: ${roi.label} = 





















$it")

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

val lowerText = numLine.text.lowercase ()

when {

lowerText.contains ("kpag") || lowerText.contains ("kpa") || lowerText.contains ("mpa") -> pressureNum = numLine

lowerText.contains ("amps") || lowerText.contains ("amp") -> currentNum = numLine

lowerText.contains ("c") || lowerText.contains ("℃") -> tempNums.add (numLine)

else -> {

// 纯数字行用于匹配 % RLA

}

}

}

DebugLogger.log (tag, "分类结果 - 温度行: ${tempNums.size}, 压力行: ${pressureNum?.text ?:" 无 "}, 电流行: ${currentNum?.text ?:" 无 "}")

for (roi in relativeRois) {

val label = roi.label

when {

label.contains ("油压") -> {

pressureNum?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, "匹配成功: ${roi.label} = $it")

}

}

label.contains ("电流") -> {

currentNum?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, "匹配成功: ${roi.label} = $it (取 L1)")

}

}

label.contains ("排出") || label.contains ("排气") -> {

tempNums.getOrNull (0)?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, "匹配成功: ${roi.label} = $it")

}

}

label.contains ("负载") || label.contains ("RLA") -> {

val rlaLine = numLines.firstOrNull {

it.text.matches (Regex ("""[\d.\s]+""")) && it.nums.isNotEmpty ()

}

rlaLine?.nums?.firstOrNull ()?.let {

results [roi.fieldId] = it

DebugLogger.log (tag, "匹配成功: ${roi.label} = $it")

}

}

}

}

}

}

DebugLogger.log (tag, "最终提取结果: $results")

return@withContext results

}

}
