	// 文件名: MainActivity.kt
	package com.zhongshan.meterreader
	import android.Manifest
	import android.content.Intent
	import android.content.pm.PackageManager
	import android.net.Uri
	import android.os.Build
	import android.os.Bundle
	import android.os.VibrationEffect
	import android.os.Vibrator
	import android.util.Size
	import android.view.View
	import android.widget.AdapterView
	import android.widget.ArrayAdapter
	import android.widget.Toast
	import androidx.activity.result.contract.ActivityResultContracts
	import androidx.appcompat.app.AppCompatActivity
	import androidx.camera.core.CameraSelector
	import androidx.camera.core.ImageAnalysis
	import androidx.camera.core.Preview
	import androidx.camera.lifecycle.ProcessCameraProvider
	import androidx.core.content.ContextCompat
	import androidx.core.content.FileProvider
	import androidx.lifecycle.lifecycleScope
	import com.zhongshan.meterreader.data.DeviceTemplate
	import com.zhongshan.meterreader.databinding.ActivityMainBinding
	import com.zhongshan.meterreader.util.BinarizeResourcePool
	import com.zhongshan.meterreader.util.StorageAndImageUtils
	import com.yalantis.ucrop.UCrop
	import kotlinx.coroutines.Dispatchers
	import kotlinx.coroutines.launch
	import kotlinx.coroutines.withContext
	import org.json.JSONArray
	import org.json.JSONObject
	import java.io.File
	import java.text.SimpleDateFormat
	import java.util.*
	import java.util.concurrent.Executors
	class MainActivity : AppCompatActivity() {
	    private lateinit var binding: ActivityMainBinding
	    private val binarizePool = BinarizeResourcePool()
	    private val executor = Executors.newSingleThreadExecutor()
	    private var cameraProvider: ProcessCameraProvider? = null
	    private var isStreaming = false
	    private var selectedTemplate: DeviceTemplate? = null
	    private var currentScreenIndex = 0
	    private var pendingCameraUri: Uri? = null
	    private var pendingPhotoFileName: String? = null
	    private var isProcessing = false
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
	    private val cameraPermissionLauncher = registerForActivityResult(
	        ActivityResultContracts.RequestPermission()
	    ) { granted ->
	        if (granted) startCamera()
	        else Toast.makeText(this, "需授予相机权限才能自动抄表", Toast.LENGTH_SHORT).show()
	    }
	    private val galleryPickLauncher = registerForActivityResult(
	        ActivityResultContracts.GetContent()
	    ) { uri ->
	        if (uri != null && !isProcessing) {
	            lifecycleScope.launch {
	                setProcessing(true)
	                processImageSuspend(uri, ImageSource.GALLERY)
	                setProcessing(false)
	            }
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
	        lifecycle.addObserver(binarizePool)
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
	        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
	            startCamera()
	        } else {
	            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
	        }
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
	                        initOcrStateManager()
	                    }
	                }
	                override fun onNothingSelected(p: AdapterView<*>?) {}
	            }
	        binding.btnGallery.setOnClickListener {
	            if (!isProcessing) galleryPickLauncher.launch("image/*")
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
	                if (cachedData.isEmpty()) {
	                    Toast.makeText(this@MainActivity, "暂无采集数据", Toast.LENGTH_SHORT).show()
	                    return@launch
	                }
	                if (template.machineId in listOf("screw_1", "screw_2", "screw_3")) {
	                    val fillData = buildScrewFillData(template.machineId, cachedData)
	                    val intent = Intent(this@MainActivity, WebViewActivity::class.java).apply {
	                        putExtra("EXTRA_URL", template.formUrl)
	                        putExtra("EXTRA_TAB_NAME", TemplateManager.getTabName(template))
	                        putExtra("EXTRA_FILL_DATA_JSON", fillData.toString())
	                        putExtra("EXTRA_FILL_TYPE", "screw")
	                    }
	                    startActivity(intent)
	                    return@launch
	                }
	                if (template.isHeatExchanger) {
	                    val fillData = buildPlateFillData(template.machineId, cachedData)
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
	        initOcrStateManager()
	    }
	    private fun startCamera() {
	        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
	        cameraProviderFuture.addListener({
	            cameraProvider = cameraProviderFuture.get()
	            val preview = Preview.Builder().build().also {
	                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
	            }
	            // 优化：提升相机分析帧的分辨率，避免因分辨率过低导致 ML Kit 识别失败
	            val analyzer = ImageAnalysis.Builder()
	                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
	                .setTargetResolution(Size(1080, 1920))
	                .build()
	                .also {
	                    it.setAnalyzer(executor) { imageProxy ->
	                        if (isStreaming || isProcessing) {
	                            imageProxy.close()
	                            return@setAnalyzer
	                        }
	                        isStreaming = true
	                        processStreamFrame(imageProxy)
	                    }
	                }
	            cameraProvider?.unbindAll()
	            cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
	        }, ContextCompat.getMainExecutor(this))
	    }
	    private fun processStreamFrame(imageProxy: androidx.camera.core.ImageProxy) {
	        val template = selectedTemplate ?: run {
	            imageProxy.close()
	            isStreaming = false
	            return
	        }
	        lifecycleScope.launch(Dispatchers.IO) {
	            try {
	                val result = OCRFacade.performStreamOcr(imageProxy, template, currentScreenIndex, binarizePool)
	                if (result.isNotEmpty()) {
	                    TraneOcrStateManager.submitFrame(result)
	                    withContext(Dispatchers.Main) {
	                        val aggregatedData = RecognitionResultHolder.getFieldsForMachine(template.machineId)
	                        binding.tvDataPreview.text = aggregatedData.entries
	                            .sortedBy { it.key }
	                            .joinToString("\n") { (k, v) ->
	                                val labelName = if (k.contains("|")) k.split("|")[1] else k
	                                "【$labelName】：$v"
	                            }
	                    }
	                }
	            } catch (e: Exception) {
	                DebugLogger.log("StreamOCR", "处理异常: ${e.message}")
	            } finally {
	                imageProxy.close()
	                isStreaming = false
	            }
	        }
	    }
	    private fun initOcrStateManager() {
	        val template = selectedTemplate ?: return
	        val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, currentScreenIndex)
	        val requiredFields = relativeRois.map { it.fieldId }
	        TraneOcrStateManager.init(requiredFields) { lockedData ->
	            runOnUiThread {
	                handleOcrSuccess(lockedData)
	            }
	        }
	    }
	    private fun handleOcrSuccess(data: Map<String, String>) {
	        val template = selectedTemplate ?: return
	        // 冻结画面
	        cameraProvider?.unbindAll()
	        // 震动反馈
	        val vibrator = getSystemService(Vibrator::class.java)
	        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
	            vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
	        }
	        lifecycleScope.launch(Dispatchers.IO) {
	            // 保存数据
	            RecognitionResultHolder.saveFieldsForMachine(template.machineId, data)
	            withContext(Dispatchers.Main) {
	                val totalScreens = DeviceOcrStrategy.totalScreens(template.machineId)
	                if (!template.isHeatExchanger && currentScreenIndex < totalScreens - 1) {
	                    currentScreenIndex++
	                    Toast.makeText(this@MainActivity, "第${currentScreenIndex}屏采集成功，请对准下一屏", Toast.LENGTH_SHORT).show()
	                    updateScreenProgress()
	                    // 恢复相机并重置状态机
	                    startCamera()
	                    initOcrStateManager()
	                } else {
	                    Toast.makeText(this@MainActivity, "设备全部数据已采集完成！请核对屏幕数据后点击填表", Toast.LENGTH_LONG).show()
	                    // 修改：不再自动跳转填表，让人员观察数据后再手动点击传输填表
	                }
	            }
	        }
	    }
	    private fun buildScrewFillData(machineId: String, cachedData: Map<String, String>): JSONObject {
	        val root = JSONObject()
	        root.put("operator", "")
	        val unitNo = when (machineId) { "screw_1" -> 1; "screw_2" -> 2; "screw_3" -> 3; else -> return root }
	        val baseNum = when (unitNo) { 1 -> 1; 2 -> 31; 3 -> 51; else -> return root }
	        val unitData = mutableMapOf<String, String>()
	        for ((key, value) in cachedData) {
	            val parts = key.split("|")
	            if (parts.size != 2) continue
	            val fieldId = parts[0]; val label = parts[1]
	            val idParts = fieldId.split("_")
	            if (idParts.size == 3) {
	                val num = idParts[2].toIntOrNull() ?: continue
	                if (num in baseNum..(baseNum + 29)) {
	                    val dataKey = labelToScrewDataKey(label)
	                    if (dataKey != null) unitData[dataKey] = value
	                }
	            }
	        }
	        if (unitData.isEmpty()) return root
	        val unitJson = JSONObject()
	        for ((k, v) in unitData) unitJson.put(k, v)
	        val presets = PresetManager.getPresetsForMachine(machineId)
	        for ((fieldIdWithLabel, value) in presets) {
	            val parts = fieldIdWithLabel.split("|")
	            if (parts.size != 2) continue
	            val dataKey = labelToScrewDataKey(parts[1])
	            if (dataKey != null) unitJson.put(dataKey, value)
	        }
	        val pumpsKey = when (machineId) { "screw_1" -> "screw_1_pumps"; "screw_2" -> "screw_2_pumps"; "screw_3" -> "screw_3_pumps"; else -> null }
	        if (pumpsKey != null) {
	            val pumpsStr = PresetManager.getPresetValue(pumpsKey, "")
	            val pumpsList = pumpsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
	            val pumpsArray = JSONArray()
	            pumpsList.forEach { pumpsArray.put(it) }
	            unitJson.put("pumps", pumpsArray)
	        } else { unitJson.put("pumps", JSONArray()) }
	        unitJson.put("remark", "")
	        when (unitNo) { 1 -> root.put("unit1", unitJson); 2 -> root.put("unit2", unitJson); 3 -> root.put("unit3", unitJson) }
	        return root
	    }
	    private fun labelToScrewDataKey(label: String): String? {
	        return when (label) {
	            "蒸发器进口水温", "蒸发器进水温度" -> "evapInTemp"
	            "蒸发器出口水温", "蒸发器出水温度" -> "evapOutTemp"
	            "蒸发器进口水压" -> "evapInPressure"
	            "蒸发器出口水压" -> "evapOutPressure"
	            "蒸发器冷媒压力", "蒸发器制冷剂压力" -> "evapRefPressure"
	            "蒸发器蒸发温度", "蒸发器制冷剂饱和温度" -> "evapTemp"
	            "冷凝器进口水温", "冷凝器回水温度" -> "condInTemp"
	            "冷凝器出口水温", "冷凝器出水温度" -> "condOutTemp"
	            "冷凝器进口水压" -> "condInPressure"
	            "冷凝器出口水压" -> "condOutPressure"
	            "冷凝器冷媒压力", "冷凝器制冷剂压力" -> "condRefPressure"
	            "冷凝器冷凝温度", "冷凝器制冷剂饱和温度" -> "condTemp"
	            "压缩机油压", "油压" -> "compOilPressure"
	            "压缩机排出口温度", "压缩机排出端冷剂温度" -> "compDischargeTemp"
	            "电机电流", "电流L1 L2 L3" -> "motorCurrent"
	            "主机负载", "%RLA" -> "hostLoad"
	            else -> null
	        }
	    }
	    private fun buildPlateFillData(machineId: String, cachedData: Map<String, String>): JSONObject {
	        val root = JSONObject()
	        val groupsArray = JSONArray()
	        val groupDefs = plateGroupDefs[machineId] ?: return root
	        for ((groupTitle, prefix) in groupDefs) {
	            val fields = JSONObject()
	            for ((key, value) in cachedData) {
	                if (!key.startsWith("$prefix|")) continue
	                val label = key.substringAfter("|")
	                val fieldKey = when {
	                    label.contains("进水温度") -> "inTemp"
	                    label.contains("出水温度") -> "outTemp"
	                    label.contains("进水压力") -> "inPressure"
	                    label.contains("出水压力") -> "outPressure"
	                    label.contains("蒸汽压力") -> "steamPressure"
	                    label.contains("水泵电流") -> "pumpCurrent"
	                    label.contains("备注") -> "remark"
	                    else -> continue
	                }
	                if (fieldKey == "remark") fields.put("remark", value) else fields.put(fieldKey, value)
	            }
	            if (fields.length() > 0) {
	                val groupObj = JSONObject()
	                groupObj.put("groupTitle", groupTitle)
	                groupObj.put("fields", fields)
	                groupsArray.put(groupObj)
	            }
	        }
	        root.put("plateGroups", groupsArray)
	        return root
	    }
	    private fun setProcessing(processing: Boolean) {
	        isProcessing = processing
	        binding.btnGallery.isEnabled = !processing
	        binding.btnTransferAndFill.isEnabled = !processing
	        binding.btnPresetSettings.isEnabled = !processing
	        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
	    }
	    private suspend fun processImageSuspend(uri: Uri, source: ImageSource) {
	        val template = selectedTemplate ?: return
	        val result = OCRFacade.performSmartOcr(this, uri, template, currentScreenIndex, source, binarizePool)
	        if (result.isNotEmpty()) {
	            RecognitionResultHolder.saveFieldsForMachine(template.machineId, result)
	        }
	        val aggregatedData = RecognitionResultHolder.getFieldsForMachine(template.machineId)
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
	                Toast.makeText(this, "第${currentScreenIndex}屏采集成功，请选择下一屏照片", Toast.LENGTH_SHORT).show()
	            } else {
	                Toast.makeText(this, "设备全部数据已采集完成！请核对屏幕数据后点击填表", Toast.LENGTH_LONG).show()
	            }
	            updateScreenProgress()
	        } else {
	            Toast.makeText(this, "未识别到有效数据", Toast.LENGTH_SHORT).show()
	        }
	    }
	    private fun updateScreenProgress() {
	        val template = selectedTemplate ?: return
	        val total = DeviceOcrStrategy.totalScreens(template.machineId)
	        if (template.isHeatExchanger || total <= 1) return
	        val current = currentScreenIndex + 1
	        val screen = DeviceOcrStrategy.screenName(template.machineId, currentScreenIndex)
	        binding.tvDataPreview.text = "请将屏幕对准黄框 · 拍第 $current/$total 屏 · $screen"
	    }
	    override fun onDestroy() {
	        super.onDestroy()
	        cameraProvider?.unbindAll()
	        executor.shutdown()
	        lifecycle.removeObserver(binarizePool)
	    }
	}
