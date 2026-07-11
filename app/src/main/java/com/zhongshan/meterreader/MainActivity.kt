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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedTemplate: DeviceTemplate? = null
    private var currentScreenIndex = 0
    private var pendingCameraUri: Uri? = null
    private var pendingPhotoFileName: String? = null
    private var isProcessing = false

    // 板交分组定义（原有逻辑完全保留）
    private val plateGroupDefs = mapOf(
        "hx1_all" to listOf(
            "1号楼板交" to "bj1_0",
            "3号楼板交" to "bj1_1",
            "备用板交" to "bj1_2",
            "10号楼1#板交" to "bj1_3",
            "10号楼2#板交" to "bj1_4",
            "1号楼水汀板交" to "bj1_5"
        ),
        "hx3_all" to listOf(
            "1#板交" to "bj3_0",
            "2#板交" to "bj3_1"
        )
    )

    // 相机权限
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchRealTimeScan() // 默认进入实时扫码模式
        else Toast.makeText(this, "需授予相机权限", Toast.LENGTH_SHORT).show()
    }

    // 原有拍照模式（兜底备用）
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

    // 相册选图
    private val galleryPickLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !isProcessing) {
            val template = selectedTemplate
            if (template != null && template.isHeatExchanger) {
                lifecycleScope.launch {
                    setProcessing(true)
                    processImageSuspend(uri, ImageSource.GALLERY)
                    setProcessing(false)
                }
            } else {
                startUcrop(uri)
            }
        }
    }

    // 裁剪回调
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

    // 实时扫码结果回调
    private val realTimeScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultStr = result.data?.getStringExtra(CameraOcrActivity.EXTRA_OCR_RESULT) ?: return@registerForActivityResult
            val resultMap = parseResultJson(resultStr)
            if (resultMap.isNotEmpty()) {
                handleOcrResult(resultMap)
            } else {
                Toast.makeText(this, "未识别到有效数据", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 状态恢复
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

        // 清理缓存
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
        // 模板选择器（原有逻辑保留）
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

        // 主入口：实时扫码识别（推荐）
        binding.btnLaunchCamera.apply {
            text = "📷 实时扫码识别（推荐）"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "将屏幕对准取景框，自动识别", Toast.LENGTH_LONG).show()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchRealTimeScan()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        // 原有拍照模式（兜底入口）
        binding.btnLegacyPhoto.apply {
            visibility = View.VISIBLE
            text = "📷 传统拍照识别"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "请将机组数据铺满屏幕后拍摄", Toast.LENGTH_LONG).show()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchCamera()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        // 相册选图（原有逻辑保留）
        binding.btnGallery.setOnClickListener {
            if (!isProcessing) {
                galleryPickLauncher.launch("image/*")
            }
        }

        // 预设设置、数据传输（原有逻辑完全保留）
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

                // 螺杆机组处理
                if (template.machineId in listOf("screw_1", "screw_2", "screw_3")) {
                    val fillData = buildScrewFillData(template.machineId, cachedData)
                    DebugLogger.log("MainActivity", "螺杆填充JSON: $fillData")
                    val intent = Intent(this@MainActivity, WebViewActivity::class.java).apply {
                        putExtra("EXTRA_URL", template.formUrl)
                        putExtra("EXTRA_TAB_NAME", TemplateManager.getTabName(template))
                        putExtra("EXTRA_FILL_DATA_JSON", fillData.toString())
                        putExtra("EXTRA_FILL_TYPE", "screw")
                    }
                    startActivity(intent)
                    return@launch
                }

                // 板交处理
                if (template.isHeatExchanger) {
                    val fillData = buildPlateFillData(template.machineId, cachedData)
                    DebugLogger.log("MainActivity", "板交填充JSON: $fillData")
                    val intent = Intent(this@MainActivity, WebViewActivity::class.java).apply {
                        putExtra("EXTRA_URL", template.formUrl)
                        putExtra("EXTRA_TAB_NAME", TemplateManager.getTabName(template))
                        putExtra("EXTRA_FILL_DATA_JSON", fillData.toString())
                        putExtra("EXTRA_FILL_TYPE", "plate")
                    }
                    startActivity(intent)
                    return@launch
                }
            }
        }
    }

    // 启动实时扫码
    private fun launchRealTimeScan() {
        val template = selectedTemplate ?: return
        val intent = Intent(this, CameraOcrActivity::class.java).apply {
            putExtra(CameraOcrActivity.EXTRA_TEMPLATE_ID, template.machineId)
            putExtra(CameraOcrActivity.EXTRA_SCREEN_INDEX, currentScreenIndex)
        }
        realTimeScanLauncher.launch(intent)
    }

    // 原有拍照启动
    private fun launchCamera() {
        val fileName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        pendingPhotoFileName = fileName
        val photoFile = File(cacheDir, fileName)
        pendingCameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        cameraLauncher.launch(pendingCameraUri!!)
    }

    // 原有裁剪逻辑
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

    // 原有单图识别
    private suspend fun processImageSuspend(uri: Uri, source: ImageSource) {
        val template = selectedTemplate ?: return
        DebugLogger.log("OCR", "开始识别: machineId=${template.machineId}, screenIndex=$currentScreenIndex, source=$source")
        val result = OCRFacade.performSmartOcr(this, uri, template, currentScreenIndex, source)
        DebugLogger.log("OCR", "识别结果: $result")
        handleOcrResult(result)
    }

    // 统一处理识别结果（拍照/扫码共用）
    private fun handleOcrResult(result: Map<String, String>) {
        val template = selectedTemplate ?: return
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

    // 解析扫码返回的JSON结果
    private fun parseResultJson(resultStr: String): Map<String, String> {
        return runCatching {
            val json = JSONObject(resultStr)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.getString(key) }
            map
        }.getOrDefault(emptyMap())
    }

    // 原有屏幕进度更新
    private fun updateScreenProgress() {
        val template = selectedTemplate ?: return
        val total = DeviceOcrStrategy.totalScreens(template.machineId)
        if (template.isHeatExchanger || total <= 1) {
            binding.btnLaunchCamera.text = "📷 实时扫码识别（推荐）"
            return
        }
        val current = currentScreenIndex + 1
        val screen = DeviceOcrStrategy.screenName(template.machineId, currentScreenIndex)
        binding.btnLaunchCamera.text = "📷 扫第 $current/$total 屏 · $screen"
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        binding.btnLaunchCamera.isEnabled = !processing
        binding.btnLegacyPhoto.isEnabled = !processing
        binding.btnGallery.isEnabled = !processing
        binding.btnTransferAndFill.isEnabled = !processing
        binding.btnPresetSettings.isEnabled = !processing
        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
    }

    // ==================== 以下原有业务逻辑完全保留 ====================
    private fun buildScrewFillData(machineId: String, cachedData: Map<String, String>): JSONObject {
        val root = JSONObject()
        root.put("operator", "")

        val unitNo = when (machineId) {
            "screw_1" -> 1
            "screw_2" -> 2
            "screw_3" -> 3
            else -> return root
        }

        val baseNum = when (unitNo) {
            1 -> 1
            2 -> 31
            3 -> 51
            else -> return root
        }
        val unitData = mutableMapOf<String, String>()
        for ((key, value) in cachedData) {
            val parts = key.split("|")
            if (parts.size != 2) continue
            val fieldId = parts[0]
            val label = parts[1]
            val idParts = fieldId.split("_")
            if (idParts.size == 3) {
                val num = idParts[2].toIntOrNull() ?: continue
                if (num in baseNum..(baseNum + 29)) {
                    val dataKey = labelToScrewDataKey(label)
                    if (dataKey != null) {
                        unitData[dataKey] = value
                    }
                }
            }
        }

        if (unitData.isEmpty()) return root

        val unitJson = JSONObject()
        for ((k, v) in unitData) {
            unitJson.put(k, v)
        }

        val presets = PresetManager.getPresetsForMachine(machineId)
        for ((fieldIdWithLabel, value) in presets) {
            val parts = fieldIdWithLabel.split("|")
            if (parts.size != 2) continue
            val label = parts[1]
            val dataKey = labelToScrewDataKey(label)
            if (dataKey != null) {
                unitJson.put(dataKey, value)
            }
        }

        val pumpsKey = when (machineId) {
            "screw_1" -> "screw_1_pumps"
            "screw_2" -> "screw_2_pumps"
            "screw_3" -> "screw_3_pumps"
            else -> null
        }
        if (pumpsKey != null) {
            val pumpsStr = PresetManager.getPresetValue(pumpsKey, "")
            val pumpsList = pumpsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val pumpsArray = JSONArray()
            pumpsList.forEach { pumpsArray.put(it) }
