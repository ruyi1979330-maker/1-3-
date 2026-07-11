package com.zhongshan.meterreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zhongshan.meterreader.util.BinarizeResourcePool
import com.zhongshan.meterreader.util.OcrTemplateConverter
import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.TraneOcrStateManager
import com.zhongshan.meterreader.util.UltimateLcdBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

class CameraOcrActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
        const val EXTRA_SCREEN_INDEX = "extra_screen_index"
        const val EXTRA_OCR_RESULT = "extra_ocr_result"
    }

    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var resourcePool: BinarizeResourcePool
    private lateinit var templateConfig: com.zhongshan.meterreader.util.MeterTemplateConfig
    private var hasReturnedResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_ocr)

        val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID) ?: run { finish(); return }
        val screenIndex = intent.getIntExtra(EXTRA_SCREEN_INDEX, 0)
        val deviceTemplate = TemplateManager.findById(templateId) ?: run { finish(); return }

        templateConfig = OcrTemplateConverter.convert(deviceTemplate, screenIndex)

        resourcePool = BinarizeResourcePool()
        lifecycle.addObserver(resourcePool)

        previewView = findViewById(R.id.preview_view)
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            previewView.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "相机权限被拒绝", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val viewFinder = findViewById<View>(R.id.view_finder)
            val screenRect = getViewFinderImageRect(viewFinder, previewView)

            val stateManager = TraneOcrStateManager(templateConfig)

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            var isProcessing = false
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (hasReturnedResult || isProcessing) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                isProcessing = true

                lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        val processResult = UltimateLcdBinarizer.processYuvToSprite(
                            imageProxy, screenRect, templateConfig.roiList, resourcePool
                        ) ?: return@launch

                        val frameResult = OCREngine.recognizeBySprite(processResult, templateConfig)

                        if (stateManager.pushFrame(frameResult)) {
                            hasReturnedResult = true
                            triggerVibration()
                            returnResult(stateManager.getFinalResult())
                        }
                    } catch (e: Exception) {
                        // 静默失败，继续下一帧
                    } finally {
                        imageProxy.close()
                        isProcessing = false
                    }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getViewFinderImageRect(viewFinder: View, previewView: PreviewView): Rect {
        val previewWidth = previewView.width
        val previewHeight = previewView.height
        val scaleX = 1280f / previewWidth.toFloat()
        val scaleY = 720f / previewHeight.toFloat()

        return Rect(
            (viewFinder.left.toFloat() * scaleX).toInt(),
            (viewFinder.top.toFloat() * scaleY).toInt(),
            (viewFinder.right.toFloat() * scaleX).toInt(),
            (viewFinder.bottom.toFloat() * scaleY).toInt()
        )
    }

    private fun triggerVibration() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    private fun returnResult(result: Map<String, String>) {
        runOnUiThread {
            val resultJson = JSONObject()
            result.forEach { (k, v) -> resultJson.put(k, v) }
            intent.putExtra(EXTRA_OCR_RESULT, resultJson.toString())
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
