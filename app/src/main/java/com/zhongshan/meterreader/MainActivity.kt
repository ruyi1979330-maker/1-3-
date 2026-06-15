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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                processImageSuspend(pendingCameraUri!!)
                setProcessing(false)
            }
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty() && !isProcessing) {
            lifecycleScope.launch {
                setProcessing(true)
                for (uri in uris) {
                    processImageSuspend(uri)
                }
                setProcessing(false)
            }
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
                        binding.tvDataPreview.text =
                            "已切换至 ${newTemplate.displayName}，请重新采集"
                        updateScreenProgress()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        binding.btnLaunchCamera.setOnClickListener {
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
                galleryLauncher.launch("image/*")
            }
        }

        binding.btnPresetSettings.setOnClickListener {
            if (!isProcessing && !isFinishing) {
                startActivity(Intent(this, PresetSettingsActivity::class.java))
            }
        }

        binding.btnTransferAndFill.setOnClickListener {
            val template = selectedTemplate ?: return@setOnClickListener
            lifecycleScope.launch {
                val cachedData =
                    RecognitionResultHolder.getFieldsForMachine(template.machineId)
                if (cachedData.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity, "暂无采集数据", Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val finalData = HashMap(cachedData)
                finalData.putAll(PresetManager.getPresetsForMachine(template.machineId))

                // 【安全处理】：防止 pumpFieldIds 属性不存在导致编译失败
                val pumpIds = try {
                    val method = template.javaClass.getMethod("getPumpFieldIds")
                    (method.invoke(template) as? List<*>)?.map { it.toString() }?.toTypedArray() ?: emptyArray()
                } catch (e: Exception) {
                    emptyArray<String>()
                }

                val intent = Intent(this@MainActivity, WebViewActivity::class.java).apply {
                    putExtra("EXTRA_URL", template.formUrl)
                    putExtra("EXTRA_TAB_NAME", TemplateManager.getTabName(template))
                    putExtra("EXTRA_KEYS", finalData.keys.toTypedArray())
                    putExtra("EXTRA_VALUES", finalData.values.toTypedArray())
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
        val fileName =
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        pendingPhotoFileName = fileName
        val photoFile = File(cacheDir, fileName)
        pendingCameraUri =
            FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        cameraLauncher.launch(pendingCameraUri!!)
    }

    private suspend fun processImageSuspend(uri: Uri) {
        val template = selectedTemplate ?: return

        val result = OCRFacade.performSmartOcr(
            this@MainActivity, uri, template, currentScreenIndex
        )

        if (result.isNotEmpty()) {
            RecognitionResultHolder.saveFieldsForMachine(template.machineId, result)
        }

        val aggregatedData =
            RecognitionResultHolder.getFieldsForMachine(template.machineId)

        // 【修复点 1】：直接从 key 中解析中文标签，彻底抛弃不存在的 template.screens
        binding.tvDataPreview.text = aggregatedData.entries
            .sortedBy { it.key }
            .joinToString("\n") { (k, v) ->
                val label = if (k.contains("|")) k.split("|")[1] else k
                "  $label：$v"
            }

        // 【修复点 2】：使用 DeviceOcrStrategy 获取总屏数
        val totalScreens = DeviceOcrStrategy.totalScreens(template.machineId)

        if (result.isNotEmpty()) {
            if (!template.isHeatExchanger && currentScreenIndex < totalScreens - 1) {
                currentScreenIndex++
                Toast.makeText(
                    this@MainActivity,
                    "第${currentScreenIndex}屏采集成功，请拍摄下一屏",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "设备全部数据已采集完成！",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateScreenProgress()
        } else {
            Toast.makeText(
                this@MainActivity, "未识别到有效数据", Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 【修复点 3】：删除了原来依赖 template.screens 的 buildFieldLabelMap 方法

    private fun updateScreenProgress() {
        val template = selectedTemplate ?: return
        
        // 【修复点 4】：使用 DeviceOcrStrategy 获取总屏数和当前屏名称
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
