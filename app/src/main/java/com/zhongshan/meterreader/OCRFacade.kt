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
	            if (template.isHeatExchanger) {
	                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
	                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
	            }
	            val results = mutableMapOf<String, String>()
	            val bmpWidth = bitmap.width
	            val bmpHeight = bitmap.height
	            val cropDir = File(context.externalCacheDir, "ocr_crops")
	            if (!cropDir.exists()) cropDir.mkdirs()
	            DebugLogger.log("OCR", "📷 本次裁剪图将保存在: ${cropDir.absolutePath}")
	            val savedCropFields = mutableListOf<String>()
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
	                        if (cropW <= 10 || cropH <= 10) continue
	                        val cropped = try {
	                            Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
	                        } catch (e: Exception) { continue }
	                        try {
	                            val fieldIdName = roi.fieldId.split("|")[0]
	                            val cropFile = File(cropDir, "$fieldIdName.jpg")
	                            cropFile.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 100, it) }
	                            savedCropFields.add(fieldIdName)
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
	                        val roiWidth = roi.xEndPct - roi.xStartPct
	                        val roiHeight = roi.yEndPct - roi.yStartPct
	                        val marginX = roiWidth * 0.05f
	                        val marginY = roiHeight * 0.05f
	                        val x0 = (roi.xStartPct - marginX).coerceIn(0f, 1f) * bmpWidth
	                        val y0 = (roi.yStartPct - marginY).coerceIn(0f, 1f) * bmpHeight
	                        val x1 = (roi.xEndPct + marginX).coerceIn(0f, 1f) * bmpWidth
	                        val y1 = (roi.yEndPct + marginY).coerceIn(0f, 1f) * bmpHeight
	                        val cropX = x0.toInt().coerceIn(0, bmpWidth - 1)
	                        val cropY = y0.toInt().coerceIn(0, bmpHeight - 1)
	                        val cropW = (x1 - x0).toInt().coerceAtMost(bmpWidth - cropX)
	                        val cropH = (y1 - y0).toInt().coerceAtMost(bmpHeight - cropY)
	                        if (cropW <= 10 || cropH <= 10) continue
	                        val cropped = try {
	                            Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
	                        } catch (e: Exception) { continue }
	                        try {
	                            val fieldIdName = roi.fieldId.split("|")[0]
	                            val cropFile = File(cropDir, "$fieldIdName.jpg")
	                            cropFile.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 100, it) }
	                            savedCropFields.add(fieldIdName)
	                        } catch (e: Exception) {}
	                        val (rawText, number) = OCREngine.extractPureNumber(cropped)
	                        DebugLogger.log("OCR-ROI", "字段=${roi.fieldId}, 裁剪区域=($cropX,$cropY,$cropW,$cropH), 原始识别=\"$rawText\", 清洗结果=$number")
	                        if (!number.isNullOrEmpty()) results[roi.fieldId] = number
	                    }
	                }
	            }
	            DebugLogger.log("OCR-Summary", "本次识别共配置 ${if (source == ImageSource.CAMERA) DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex).size else DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex).size} 个ROI，实际成功保存 ${savedCropFields.size} 张截图。")
	            return@withContext results
	        } finally {
	            bitmap.recycle()
	        }
	    }
	}
