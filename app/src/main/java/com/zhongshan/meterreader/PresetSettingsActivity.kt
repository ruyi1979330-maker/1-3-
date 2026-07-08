// 文件名: PresetSettingsActivity.kt
package com.zhongshan.meterreader

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PresetSettingsActivity : AppCompatActivity() {
    private lateinit var etEvapInPressure: EditText
    private lateinit var etEvapOutPressure: EditText
    private lateinit var etCondInPressure: EditText
    private lateinit var etCondOutPressure: EditText
    private val pumpCheckboxes = mutableListOf<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "预设管理"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // 1. 蒸发器进口水压
        val tvEvapIn = TextView(this).apply {
            text = "蒸发器进口水压"
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        etEvapInPressure = EditText(this).apply {
            hint = "请输入蒸发器进口水压预设值"
            setText(PresetManager.getEvapInPressure())
        }

        // 2. 蒸发器出口水压
        val tvEvapOut = TextView(this).apply {
            text = "蒸发器出口水压"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }
        etEvapOutPressure = EditText(this).apply {
            hint = "请输入蒸发器出口水压预设值"
            setText(PresetManager.getEvapOutPressure())
        }

        // 3. 冷凝器进口水压
        val tvCondIn = TextView(this).apply {
            text = "冷凝器进口水压"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }
        etCondInPressure = EditText(this).apply {
            hint = "请输入冷凝器进口水压预设值"
            setText(PresetManager.getCondInPressure())
        }

        // 4. 冷凝器出口水压
        val tvCondOut = TextView(this).apply {
            text = "冷凝器出口水压"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }
        etCondOutPressure = EditText(this).apply {
            hint = "请输入冷凝器出口水压预设值"
            setText(PresetManager.getCondOutPressure())
        }

        // 5. 冷冻泵选择
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
        root.addView(tvEvapIn)
        root.addView(etEvapInPressure)
        root.addView(tvEvapOut)
        root.addView(etEvapOutPressure)
        root.addView(tvCondIn)
        root.addView(etCondInPressure)
        root.addView(tvCondOut)
        root.addView(etCondOutPressure)
        root.addView(tvPumps)
        root.addView(pumpsContainer)
        root.addView(btnSave)

        setContentView(root)
    }

    private fun savePreset() {
        // 保存四个水压预设
        PresetManager.setEvapInPressure(etEvapInPressure.text.toString().trim())
        PresetManager.setEvapOutPressure(etEvapOutPressure.text.toString().trim())
        PresetManager.setCondInPressure(etCondInPressure.text.toString().trim())
        PresetManager.setCondOutPressure(etCondOutPressure.text.toString().trim())

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
