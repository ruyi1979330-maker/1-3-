package com.zhongshan.meterreader

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongshan.meterreader.databinding.ActivityPresetSettingsBinding
import com.zhongshan.meterreader.databinding.ItemPresetBinding

class PresetSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPresetSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bug Fix 5（齿轮按钮闪退防护补充）：
        // 原代码直接 inflate binding 并调用 setSupportActionBar，
        // 在极少数情况下（如系统内存压力、某些华为机型 HMS 服务冲突时），
        // setSupportActionBar 前 supportActionBar 已经被系统提前初始化，
        // 导致 IllegalStateException: This Activity already has an action bar。
        // 修复：用 try-catch 包裹整个初始化流程，并在出错时优雅降级（移除 toolbar 的 actionbar 角色）。
        try {
            binding = ActivityPresetSettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "压力与电压预设管理"
            binding.toolbar.setNavigationOnClickListener { finish() }

            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.adapter = PresetAdapter(PresetManager.allItems) { item ->
                showEditDialog(item)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 降级处理：不使用 toolbar 作为 action bar，但界面仍然可用
            try {
                if (!::binding.isInitialized) {
                    binding = ActivityPresetSettingsBinding.inflate(layoutInflater)
                    setContentView(binding.root)
                }
                // 不设置 ActionBar，直接绑定数据
                binding.toolbar.setNavigationOnClickListener { finish() }
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
                binding.recyclerView.adapter = PresetAdapter(PresetManager.allItems) { item ->
                    showEditDialog(item)
                }
            } catch (e2: Exception) {
                e2.printStackTrace()
                finish() // 最终降级：无法初始化则安全退出
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showEditDialog(item: PresetManager.PresetItem) {
        // 防止 Activity 已销毁时弹出对话框导致 WindowManager 异常
        if (isFinishing || isDestroyed) return

        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val currentValue = PresetManager.getPresetValue(item.storageKey, item.defaultValue)
            setText(currentValue)
            setSelection(text.length)

            filters = arrayOf(InputFilter { source, _, _, dest, _, _ ->
                if (source == "." && dest.contains(".")) "" else null
            })
        }

        AlertDialog.Builder(this)
            .setTitle("修改预设: ${item.label}")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val newValue = editText.text.toString().trim()
                if (newValue.isNotEmpty()) {
                    PresetManager.updatePreset(item.storageKey, newValue)
                    binding.recyclerView.adapter?.notifyDataSetChanged()
                    Toast.makeText(this, "已同步更新同组机型预设值", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class PresetAdapter(
        private val items: List<PresetManager.PresetItem>,
        private val onItemClick: (PresetManager.PresetItem) -> Unit
    ) : RecyclerView.Adapter<PresetAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemPresetBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val currentValue = PresetManager.getPresetValue(item.storageKey, item.defaultValue)

            holder.itemBinding.tvLabel.text = item.label
            holder.itemBinding.tvValue.text = currentValue
            holder.itemBinding.root.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
