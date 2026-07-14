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
suspend fun performStreamOcr(
imageProxy: ImageProxy,
template: DeviceTemplate,
screenIndex: Int,
resourcePool: BinarizeResourcePool
): Map<String, String> = withContext(Dispatchers.IO) {
val rawBitmap = imageProxy.toBitmap()
val rotation = imageProxy.imageInfo.rotationDegrees
val rotatedBitmap = if (rotation != 0) {
val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
rawBitmap.recycle()
}
} else {
rawBitmap
}
val targetWidth = 1080
val bitmap = if (rotatedBitmap.width < targetWidth) {
val scale = targetWidth.toFloat() / rotatedBitmap.width
Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, (rotatedBitmap.height * scale).toInt(), true).also {
if (it != rotatedBitmap) rotatedBitmap.recycle()
}
} else {
rotatedBitmap
}
try {
if (template.isHeatExchanger) {
val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
}
return@withContext extractScrewDataFromBitmap(bitmap, template, screenIndex, "StreamOCR")
} finally {
bitmap.recycle()
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
if (template.isHeatExchanger) {
val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
}
return@withContext extractScrewDataFromBitmap(bitmap, template, screenIndex, "SmartOCR")
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
DebugLogger.log(tag, "开始螺杆机原图直接识别，尺寸: 
b
i
t
m
a
p
.
w
i
d
t
h
x
bitmap.widthx{bitmap.height}")
val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
val image = InputImage.fromBitmap(bitmap, 0)
val visionResult = recognizer.process(image).await()
val lines = visionResult.textBlocks.flatMap { it.lines }
DebugLogger.log(tag, "ML Kit 原始识别到 {lines.size} 行文本")
        data class LineInfo(val y: Float, val x: Float, val text: String)
        val sortedLines = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
        }.sortedBy { it.y }
        DebugLogger.log(tag, "--- 按Y坐标排序后的文本行 ---")
        sortedLines.forEachIndexed { index, lineInfo ->
            DebugLogger.log(tag, "行[index] Y=
l
i
n
e
I
n
f
o
.
y
.
t
o
I
n
t
(
)
X
=
lineInfo.y.toInt()X={lineInfo.x.toInt()} Text='{lineInfo.text}'")
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
n
u
m
L
i
n
e
.
y
.
t
o
I
n
t
(
)
T
e
x
t
=
′
numLine.y.toInt()Text= 
′
 {numLine.text}' Nums=${numLine.nums}")
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
DebugLogger.log(tag, "分类结果 - 温度行数量: 
t
e
m
p
N
u
m
s
.
s
i
z
e
,
压力行
:
tempNums.size,压力行:{pressureNum?.text ?: "无"}")
for (roi in relativeRois) {
val label = roi.label
when {
label.contains("冷媒压力") || label.contains("制冷剂压力") -> {
pressureNum?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it")
}
}
label.contains("进水温度") || label.contains("回水温度") -> {
tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it")
}
}
label.contains("出水温度") -> {
tempNums.getOrNull(1)?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it")
}
}
label.contains("饱和温度") || label.contains("蒸发温度") || label.contains("冷凝温度") -> {
tempNums.getOrNull(2)?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it")
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
}
}
}
DebugLogger.log(tag, "分类结果 - 温度行: 
t
e
m
p
N
u
m
s
.
s
i
z
e
,
压力行
:
tempNums.size,压力行:{pressureNum?.text ?: "无"}, 电流行: ${currentNum?.text ?: "无"}")
for (roi in relativeRois) {
val label = roi.label
when {
label.contains("油压") -> {
pressureNum?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it")
}
}
label.contains("电流") -> {
currentNum?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it (取L1)")
}
}
label.contains("排出") || label.contains("排气") -> {
tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it")
}
}
label.contains("负载") || label.contains("RLA") -> {
val rlaLine = numLines.firstOrNull {
it.text.matches(Regex("""[\d.\s]+""")) && it.nums.isNotEmpty()
}
rlaLine?.nums?.firstOrNull()?.let {
results[roi.fieldId] = it
DebugLogger.log(tag, "匹配成功: 
r
o
i
.
l
a
b
e
l
=
roi.label=it")
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
