// 文件名: PresetSettingsActivity.kt
package com.zhongshan.meterreader
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
        try {
            binding = ActivityPresetSettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "预设管理"
            binding.toolbar.setNavigationOnClickListener { finish() }
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.adapter = PresetAdapter(PresetManager.allItems) { item ->
                showEditDialog(item)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                if (!::binding.isInitialized) {
                    binding = ActivityPresetSettingsBinding.inflate(layoutInflater)
                    setContentView(binding.root)
                }
                binding.toolbar.setNavigationOnClickListener { finish() }
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
                binding.recyclerView.adapter = PresetAdapter(PresetManager.allItems) { item ->
                    showEditDialog(item)
                }
            } catch (e2: Exception) {
                e2.printStackTrace()
                finish()
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    private fun showEditDialog(item: PresetManager.PresetItem) {
        if (isFinishing || isDestroyed) return
        if (item.storageKey.endsWith("_pumps")) return
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
            RecyclerView.ViewHolder(itemBinding.root) {
            val pumpCheckboxContainer: LinearLayout = LinearLayout(itemView.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }
            init {
                (itemBinding.root as? ViewGroup)?.let { root ->
                    if (pumpCheckboxContainer.parent == null) {
                        root.addView(pumpCheckboxContainer)
                    }
                }
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            if (item.storageKey.endsWith("_pumps")) {
                holder.itemBinding.tvLabel.text = item.label
                holder.itemBinding.tvValue.visibility = View.GONE
                holder.pumpCheckboxContainer.visibility = View.VISIBLE
                holder.pumpCheckboxContainer.removeAllViews()
                val fixedPumps = PresetManager.getFixedPumpsForItem(item.storageKey)
                for (pumpName in fixedPumps) {
                    val checkBox = CheckBox(holder.itemView.context).apply {
                        text = pumpName
                        textSize = holder.itemBinding.tvLabel.textSize / resources.displayMetrics.density
                        isChecked = PresetManager.isPumpSelected(item.storageKey, pumpName)
                        setOnCheckedChangeListener { _, isChecked ->
                            PresetManager.setPumpSelected(item.storageKey, pumpName, isChecked)
                        }
                    }
                    holder.pumpCheckboxContainer.addView(checkBox)
                }
                holder.itemBinding.root.setOnClickListener(null)
            } else {
                holder.pumpCheckboxContainer.visibility = View.GONE
                holder.itemBinding.tvValue.visibility = View.VISIBLE
                val displayValue = PresetManager.getPresetValue(item.storageKey, item.defaultValue)
                holder.itemBinding.tvLabel.text = item.label
                holder.itemBinding.tvValue.text = displayValue
                holder.itemBinding.root.setOnClickListener { onItemClick(item) }
            }
        }
        override fun getItemCount() = items.size
    }
}
