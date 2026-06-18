package com.zhongshan.meterreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zhongshan.meterreader.data.RoiBox
import com.zhongshan.meterreader.data.RoiConfigManager
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.launch

class RoiConfigActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var btnNext: Button
    
    private var machineId: String = ""
    private var screenIndex: Int = 0
    private var fieldIds: List<String> = emptyList()
    private var fieldLabels: List<String> = emptyList()

    private var currentBoxIndex = 0
    private val roiBoxes = mutableListOf<RoiBox>()
    private var startX = 0f
    private var startY = 0f
    private var isDrawing = false
    private var baseBitmap: Bitmap? = null
    private var overlayBitmap: Bitmap? = null

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 需要创建一个 layout/activity_roi_config.xml，放置一个 ImageView 和下方的按钮
        // 建议 xml 代码见下方的【XML布局补充说明】
        setContentView(R.layout.activity_roi_config)

        imageView = findViewById(R.id.ivRoiImage)
        btnSave = findViewById(R.id.btnSaveRoi)
        btnCancel = findViewById(R.id.btnCancelRoi)
        btnNext = findViewById(R.id.btnNextRoi)

        machineId = intent.getStringExtra("machineId") ?: ""
        screenIndex = intent.getIntExtra("screenIndex", 0)
        fieldIds = intent.getStringArrayListExtra("fieldIds") ?: emptyList()
        fieldLabels = intent.getStringArrayListExtra("fieldLabels") ?: emptyList()

        if (fieldIds.isEmpty()) {
            Toast.makeText(this, "参数错误，未传入表单字段ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 从相册选一张端正的基准图
        val pickIntent = Intent(Intent.ACTION_PICK)
        pickIntent.type = "image/*"
        startActivityForResult(pickIntent, 100)

        btnCancel.setOnClickListener { finish() }
        btnNext.setOnClickListener { 
            if (currentBoxIndex < fieldIds.size - 1) {
                currentBoxIndex++
                updateInstruction()
            } else {
                Toast.makeText(this, "配置完成，数据已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        btnSave.setOnClickListener {
            if (currentBoxIndex <= roiBoxes.lastIndex && roiBoxes[currentBoxIndex].wPercent > 0.02) {
                Toast.makeText(this, "框选面积太小，请重新框选", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 保存配置
            RoiConfigManager.saveRoiConfigs(this, machineId, screenIndex, roiBoxes)
            Toast.makeText(this, "第 ${currentBoxIndex+1}/${fieldIds.size} 个区域已保存", Toast.LENGTH_SHORT).show()
            btnNext.performClick()
        }

        setupTouchListener()
        updateInstruction()
    }

    private fun updateInstruction() {
        if (currentBoxIndex < fieldIds.size) {
            Toast.makeText(this, "请用手指框选: ${fieldLabels[currentBoxIndex]}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            if (currentBoxIndex > roiBoxes.lastIndex) return@setOnTouchListener true
            val bmp = baseBitmap ?: return@setOnTouchListener true
            val scaleX = bmp.width.toFloat() / imageView.width.toFloat()
            val scaleY = bmp.height.toFloat() / imageView.height.toFloat()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x * scaleX
                    startY = event.y * scaleY
                    isDrawing = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDrawing) return@setOnTouchListener true
                    val endX = event.x * scaleX
                    val endY = event.y * scaleY
                    roiBoxes.add(RoiBox(
                        xPercent = startX / bmp.width,
                        yPercent = startY / bmp.height,
                        wPercent = (endX - startX) / bmp.width,
                        hPercent = (endY - startY) / bmp.height,
                        fieldId = fieldIds[currentBoxIndex],
                        label = fieldLabels[currentBoxIndex]
                    ))
                    drawOverlay()
                    isDrawing = false
                    btnSave.isEnabled = true
                }
            }
            true
        }
    }

    private fun drawOverlay() {
        overlayBitmap = baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)?.apply {
            val canvas = Canvas(this)
            for (box in roiBoxes) {
                val left = box.xPercent * width
                val top = box.yPercent * height
                val right = left + box.wPercent * width
                val bottom = top + box.hPercent * height
                canvas.drawRect(RectF(left, top, right, bottom), paint)
            }
        }
        imageView.setImageBitmap(overlayBitmap)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch {
                baseBitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(this@RoiConfigActivity, uri)
                if (baseBitmap != null) {
                    imageView.setImageBitmap(baseBitmap)
                } else {
                    Toast.makeText(this@RoiConfigActivity, "图片加载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
