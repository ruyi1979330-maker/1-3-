package com.zhongshan.meterreader

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PresetSettingsActivity : AppCompatActivity() {
    private lateinit var etPressure: EditText
    private lateinit var etVoltage: EditText
    private val pumpCheckboxes = mutableListOf<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "预设管理"

        // 根布局
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // 1. 压力预设
        val tvPressure = TextView(this).apply {
            text = "压力预设值"
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        etPressure = EditText(this).apply {
            hint = "请输入默认压力值"
            setText(PresetManager.getPressurePreset())
        }

        // 2. 电压预设
        val tvVoltage = TextView(this).apply {
            text = "电压预设值"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }
        etVoltage = EditText(this).apply {
            hint = "请输入默认电压值"
            setText(PresetManager.getVoltagePreset())
        }

        // 3. 冷冻泵选择（一级界面直接显示，无需二级菜单）
        val tvPumps = TextView(this).apply {
            text = "冷冻泵选择"
            textSize = 16f
            setPadding(0, 32, 0, 12)
        }
        val pumpsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val selectedPumps = PresetManager.getSelectedPumps()
        for (i in 1..8) {
            val cb = CheckBox(this).apply {
                text = "${i}号冷冻泵"
                textSize = 15f
                setPadding(0, 8, 0, 8)
                isChecked = selectedPumps.contains("$i")
            }
            pumpCheckboxes.add(cb)
            pumpsContainer.addView(cb)
        }

        // 保存按钮
        val btnSave = android.widget.Button(this).apply {
            text = "保存预设"
            setOnClickListener { savePreset() }
            setPadding(0, 40, 0, 0)
        }

        // 组装界面
        root.addView(tvPressure)
        root.addView(etPressure)
        root.addView(tvVoltage)
        root.addView(etVoltage)
        root.addView(tvPumps)
        root.addView(pumpsContainer)
        root.addView(btnSave)

        setContentView(root)
    }

    private fun savePreset() {
        // 保存压力、电压预设
        PresetManager.setPressurePreset(etPressure.text.toString().trim())
        PresetManager.setVoltagePreset(etVoltage.text.toString().trim())

        // 保存冷冻泵勾选状态
        val selected = mutableSetOf<String>()
        pumpCheckboxes.forEachIndexed { index, cb ->
            if (cb.isChecked) selected.add("${index + 1}")
        }
        PresetManager.setSelectedPumps(selected)

        Toast.makeText(this, "预设已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
