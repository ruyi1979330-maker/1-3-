package com.zhongshan.meterreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.databinding.ActivityMainBinding
import com.zhongshan.meterreader.util.StorageAndImageUtils
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedTemplate: DeviceTemplate? = null
    private var currentScreenIndex = 0
    private var pendingCameraUri: Uri? = null
    private var pendingPhotoFileName: String? = null
    private var isProcessing = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "需授予相机权限", Toast.LENGTH_SHORT).show()
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCameraUri != null && !isProcessing) {
            lifecycleScope.launch {
                setProcessing(true)
                processImageSuspend(pendingCameraUri!!, ImageSource.CAMERA)
                setProcessing(false)
            }
        }
    }

    private val galleryPickLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !isProcessing) {
            startUcrop(uri)
        }
    }

    private val uCropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && !isProcessing) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                lifecycleScope.launch {
                    setProcessing(true)
                    processImageSuspend(resultUri, ImageSource.GALLERY)
                    setProcessing(false)
                }
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(result.data!!)
            Toast.makeText(this, "裁剪失败: ${cropError?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            currentScreenIndex = savedInstanceState.getInt("KEY_SCREEN_INDEX", 0)
            val restoredFileName = savedInstanceState.getString("KEY_CAMERA_FILENAME")
            if (restoredFileName != null) {
                pendingPhotoFileName = restoredFileName
                val photoFile = File(cacheDir, restoredFileName)
                pendingCameraUri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", photoFile
                )
            }
            savedInstanceState.getString("KEY_TEMPLATE_ID")?.let { id ->
                selectedTemplate = TemplateManager.findById(id)
            }
        } else {
            selectedTemplate = TemplateManager.allTemplates.first()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            StorageAndImageUtils.clearOldCacheFiles(cacheDir)
        }

        setupUI()
        updateScreenProgress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("KEY_SCREEN_INDEX", currentScreenIndex)
        outState.putString("KEY_CAMERA_FILENAME", pendingPhotoFileName)
        outState.putString("KEY_TEMPLATE_ID", selectedTemplate?.machineId)
    }

    private fun setupUI() {
        val names = TemplateManager.allTemplates.map { it.displayName }
        binding.spinnerDevice.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        val pos = TemplateManager.allTemplates.indexOfFirst {
            it.machineId == selectedTemplate?.machineId
        }
        if (pos >= 0) binding.spinnerDevice.setSelection(pos)

        binding.spinnerDevice.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val newTemplate = TemplateManager.allTemplates[position]
                    if (selectedTemplate?.machineId != newTemplate.machineId) {
                        val oldMachineId = selectedTemplate?.machineId
                        selectedTemplate = newTemplate
                        currentScreenIndex = 0
                        lifecycleScope.launch {
                            if (oldMachineId != null) {
                                RecognitionResultHolder.clearMachineData(oldMachineId)
                            }
                        }
                        binding.tvDataPreview.text = "已切换至 ${newTemplate.displayName}，请重新采集"
                        updateScreenProgress()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        binding.btnLaunchCamera.setOnClickListener {
            Toast.makeText(this, "请将机组数据铺满屏幕后拍摄", Toast.LENGTH_LONG).show()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnGallery.setOnClickListener {
            if (!isProcessing) {
                galleryPickLauncher.launch("image/*")
            }
        }

        binding.btnRoiCalibration.visibility = View.GONE
        binding.btnRoiCalibration.isEnabled = false

        binding.btnPresetSettings.setOnClickListener {
            if (!isProcessing && !isFinishing) {
                startActivity(Intent(this, PresetSettingsActivity::class.java))
            }
        }

        binding.btnTransferAndFill.setOnLongClickListener {
            DebugLogger.saveAndShare(this@MainActivity)
            Toast.makeText(this, "日志已导出，请通过分享发送给开发者", Toast.LENGTH_LONG).show()
            true
        }

        binding.btnTransferAndFill.setOnClickListener {
            val template = selectedTemplate ?: return@setOnClickListener
            lifecycleScope.launch {
                val cachedData = RecognitionResultHolder.getFieldsForMachine(template.machineId)
                DebugLogger.log("MainActivity", "推送前缓存数据 (machineId=${template.machineId}): $cachedData")

                if (cachedData.isEmpty()) {
                    Toast.makeText(this@MainActivity, "暂无采集数据", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 推断当前机组的数字前缀，用于给预设值字段添加前缀
                val machineNumPrefix = when (template.machineId) {
                    "screw_1", "cent_1" -> "1#"
                    "screw_2" -> "2#"
                    "screw_3" -> "3#"
                    "screw_3_1" -> "1#"
                    "screw_3_2" -> "2#"
                    else -> ""
                }

                val finalData = HashMap<String, String>()
                // 先放入预设值，并给 label 加上前缀
                val presets = PresetManager.getPresetsForMachine(template.machineId)
                for ((fieldId, value) in presets) {
                    // fieldId 格式如 "field_1_03|蒸发器进口水压"
                    val parts = fieldId.split("|")
                    if (parts.size == 2) {
                        val labelWithPrefix = if (machineNumPrefix.isNotEmpty() && !parts[1].startsWith(machineNumPrefix)) {
                            "${machineNumPrefix}${parts[1]}"
                        } else parts[1]
                        finalData["${parts[0]}|$labelWithPrefix"] = value
                    } else {
                        finalData[fieldId] = value
                    }
                }
                // 再放入 OCR 数据
                finalData.putAll(cachedData)

                fun getValueByLabel(data: Map<String, String>, labelName: String): String? {
                    return data.entries.find { it.key.endsWith("|$labelName") }?.value
                }

                val loadPctRaw = getValueByLabel(finalData, "压缩机导液开度")
                if (loadPctRaw != null) {
                    val cleanPct = loadPctRaw.replace(Regex("[^0-9.]"), "").toFloatOrNull()
                    if (cleanPct != null) {
                        if (template.machineId == "cent_1" || template.machineId.startsWith("screw_3_")) {
                            val current = (cleanPct * 2.5f).roundToInt().toString()
                            finalData["calc_current|${machineNumPrefix}电机电流"] = current
                        }
                    }
                }

                // 移除可能重复的老的预设值字段（无前缀），保留新 key
                // 这一步非必须，因为我们已覆盖，但为了安全可以不清除

                DebugLogger.log("MainActivity", "最终推送数据 (machineId=${template.machineId}): $finalData")

                val pumpIds = try {
                    val method = template.javaClass.getMethod("getPumpFieldIds")
                    (method.invoke(template) as? List<*>)?.map { it.toString() }?.toTypedArray() ?: emptyArray()
                } catch (e: Exception) {
                    emptyArray<String>()
                }

                val exactEntries = finalData.entries.toList()
                val safeKeys = exactEntries.map { it.key }.toTypedArray()
                val safeValues = exactEntries.map { it.value }.toTypedArray()

                DebugLogger.log("MainActivity", "Keys: ${safeKeys.joinToString()}")
                DebugLogger.log("MainActivity", "Values: ${safeValues.joinToString()}")

                val intent = Intent(this@MainActivity, WebViewActivity::class.java).apply {
                    putExtra("EXTRA_URL", template.formUrl)
                    putExtra("EXTRA_TAB_NAME", TemplateManager.getTabName(template))
                    putExtra("EXTRA_KEYS", safeKeys)
                    putExtra("EXTRA_VALUES", safeValues)
                    putExtra("EXTRA_PUMP_IDS", pumpIds)
                }
                startActivity(intent)
            }
        }
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        binding.btnLaunchCamera.isEnabled = !processing
        binding.btnGallery.isEnabled = !processing
        binding.btnTransferAndFill.isEnabled = !processing
        binding.btnPresetSettings.isEnabled = !processing
        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
    }

    private fun launchCamera() {
        val fileName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        pendingPhotoFileName = fileName
        val photoFile = File(cacheDir, fileName)
        pendingCameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        cameraLauncher.launch(pendingCameraUri!!)
    }

    private fun startUcrop(sourceUri: Uri) {
        val fileName = "crop_${System.currentTimeMillis()}.jpg"
        val destFile = File(cacheDir, fileName)
        val destUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)

        val options = UCrop.Options()
        options.setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
        options.setCompressionQuality(90)
        options.setHideBottomControls(false)

        val uCropIntent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(4f, 3f)
            .withMaxResultSize(3000, 4000)
            .withOptions(options)
            .getIntent(this)
        uCropLauncher.launch(uCropIntent)
    }

    private suspend fun processImageSuspend(uri: Uri, source: ImageSource) {
        val template = selectedTemplate ?: return
        DebugLogger.log("OCR", "开始识别: machineId=${template.machineId}, screenIndex=$currentScreenIndex, source=$source")

        val result = OCRFacade.performSmartOcr(this, uri, template, currentScreenIndex, source)

        DebugLogger.log("OCR", "识别结果: $result")

        if (result.isNotEmpty()) {
            RecognitionResultHolder.saveFieldsForMachine(template.machineId, result)
        }

        val aggregatedData = RecognitionResultHolder.getFieldsForMachine(template.machineId)
        DebugLogger.log("OCR", "聚合数据: $aggregatedData")

        binding.tvDataPreview.text = aggregatedData.entries
            .sortedBy { it.key }
            .joinToString("\n") { (k, v) ->
                val labelName = if (k.contains("|")) k.split("|")[1] else k
                "【$labelName】：$v"
            }

        val totalScreens = DeviceOcrStrategy.totalScreens(template.machineId)

        if (result.isNotEmpty()) {
            if (!template.isHeatExchanger && currentScreenIndex < totalScreens - 1) {
                currentScreenIndex++
                Toast.makeText(this, "第${currentScreenIndex}屏采集成功，请拍摄下一屏", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "设备全部数据已采集完成！", Toast.LENGTH_LONG).show()
            }
            updateScreenProgress()
        } else {
            Toast.makeText(this, "未识别到有效数据", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateScreenProgress() {
        val template = selectedTemplate ?: return
        val total = DeviceOcrStrategy.totalScreens(template.machineId)
        if (template.isHeatExchanger || total <= 1) {
            binding.btnLaunchCamera.text = "📷 现场拍照"
            return
        }
        val current = currentScreenIndex + 1
        val screen = DeviceOcrStrategy.screenName(template.machineId, currentScreenIndex)
        binding.btnLaunchCamera.text = "📷 拍第 $current/$total 屏 · $screen"
    }
}
