	package com.zhongshan.meterreader
	import android.content.Context
	import android.graphics.Bitmap
	import android.net.Uri
	import android.widget.Toast
	import com.zhongshan.meterreader.data.DeviceTemplate
	import com.zhongshan.meterreader.util.OCREngine
	import com.zhongshan.meterreader.util.StorageAndImageUtils
	import kotlinx.coroutines.Dispatchers
	import kotlinx.coroutines.withContext
	import java.io.File
	enum class ImageSource { CAMERA, GALLERY }
	object OCRFacade {
	    suspend fun performSmartOcr(
	        context: Context,
	        imageUri: Uri,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        source: ImageSource
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
	        if (bitmap == null) {
	            withContext(Dispatchers.Main) {
	                Toast.makeText(context, "图片加载失败", Toast.LENGTH_LONG).show()
	            }
	            return@withContext emptyMap()
	        }
	        DebugLogger.log("OCR", "图片尺寸: width=${bitmap.width}, height=${bitmap.height}, source=$source")
	        try {
	            // 板交全图识别（不受双轨影响）
	            if (template.isHeatExchanger) {
	                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
	                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
	            }
	            val results = mutableMapOf<String, String>()
	            val bmpWidth = bitmap.width
	            val bmpHeight = bitmap.height
	            // 修复：将截图保存至外部缓存目录，解决普通文件管理器无法查看的问题
	            val cropDir = File(context.externalCacheDir, "ocr_crops")
	            if (!cropDir.exists()) cropDir.mkdirs()
	            DebugLogger.log("OCR", "📷 本次裁剪图将保存在: ${cropDir.absolutePath}")
	            when (source) {
	                ImageSource.CAMERA -> {
	                    val rois = DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex)
	                    DebugLogger.log("OCR", "使用绝对坐标，共${rois.size}个ROI")
	                    if (rois.isEmpty()) return@withContext emptyMap()
	                    for (roi in rois) {
	                        val cropX = (roi.xPercent * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
	                        val cropY = (roi.yPercent * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
	                        val cropW = (roi.wPercent * bmpWidth).toInt().coerceAtMost(bmpWidth - cropX)
	                        val cropH = (roi.hPercent * bmpHeight).toInt().coerceAtMost(bmpHeight - cropY)
	                        if (cropW <= 10 || cropH <= 10) {
	                            DebugLogger.log("OCR-ROI", "字段=${roi.fieldId} 裁剪区域过小，跳过")
	                            continue
	                        }
	                        val cropped = try {
	                            Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
	                        } catch (e: Exception) { 
	                            DebugLogger.log("OCR-ROI", "字段=${roi.fieldId} 裁剪失败: ${e.message}")
	                            continue 
	                        }
	                        try {
	                            val cropFile = File(cropDir, "${roi.fieldId.split("|")[0]}.jpg")
	                            cropFile.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 100, it) }
	                        } catch (e: Exception) {}
	                        val (rawText, number) = OCREngine.extractPureNumber(cropped)
	                        DebugLogger.log("OCR-ROI", "字段=${roi.fieldId}, 裁剪区域=($cropX,$cropY,$cropW,$cropH), 原始识别=\"$rawText\", 清洗结果=$number")
	                        if (!number.isNullOrEmpty()) results[roi.fieldId] = number
	                    }
	                }
	                ImageSource.GALLERY -> {
	                    val rois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
	                    DebugLogger.log("OCR", "使用相对坐标，共${rois.size}个ROI")
	                    if (rois.isEmpty()) return@withContext emptyMap()
	                    for (roi in rois) {
	                        val xStart = roi.xStartPct
	                        val yStart = roi.yStartPct
	                        val xEnd = roi.xEndPct
	                        val yEnd = roi.yEndPct
	                        val roiWidth = xEnd - xStart
	                        val roiHeight = yEnd - yStart
	                        // 修复：扩大边距至 10%，提升对手工裁切偏差的容错率
	                        val marginX = roiWidth * 0.10f
	                        val marginY = roiHeight * 0.10f
	                        val x0 = (xStart - marginX).coerceIn(0f, 1f) * bmpWidth
	                        val y0 = (yStart - marginY).coerceIn(0f, 1f) * bmpHeight
	                        val x1 = (xEnd + marginX).coerceIn(0f, 1f) * bmpWidth
	                        val y1 = (yEnd + marginY).coerceIn(0f, 1f) * bmpHeight
	                        val cropX = x0.toInt().coerceIn(0, bmpWidth - 1)
	                        val cropY = y0.toInt().coerceIn(0, bmpHeight - 1)
	                        val cropW = (x1 - x0).toInt().coerceAtMost(bmpWidth - cropX)
	                        val cropH = (y1 - y0).toInt().coerceAtMost(bmpHeight - cropY)
	                        if (cropW <= 10 || cropH <= 10) {
	                            DebugLogger.log("OCR-ROI", "字段=${roi.fieldId} 裁剪区域过小，跳过")
	                            continue
	                        }
	                        val cropped = try {
	                            Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
	                        } catch (e: Exception) { 
	                            DebugLogger.log("OCR-ROI", "字段=${roi.fieldId} 裁剪失败: ${e.message}")
	                            continue 
	                        }
	                        try {
	                            val cropFile = File(cropDir, "${roi.fieldId.split("|")[0]}.jpg")
	                            cropFile.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 100, it) }
	                        } catch (e: Exception) {}
	                        val (rawText, number) = OCREngine.extractPureNumber(cropped)
	                        DebugLogger.log("OCR-ROI", "字段=${roi.fieldId}, 裁剪区域=($cropX,$cropY,$cropW,$cropH), 原始识别=\"$rawText\", 清洗结果=$number")
	                        if (!number.isNullOrEmpty()) results[roi.fieldId] = number
	                    }
	                }
	            }
	            return@withContext results
	        } finally {
	            bitmap.recycle()
	        }
	    }
	}
