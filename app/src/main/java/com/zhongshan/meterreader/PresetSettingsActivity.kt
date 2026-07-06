	// 文件名: PresetSettingsActivity.kt
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
	        // 判断是否为冷冻泵勾选项
	        if (item.storageKey.endsWith("_pumps")) {
	            val currentPumps = PresetManager.getPumps(item.storageKey, item.defaultValue)
	            val checkedItems = PresetManager.availablePumps.map { currentPumps.contains(it) }.toBooleanArray()
	            AlertDialog.Builder(this)
	                .setTitle("勾选: ${item.label}")
	                .setMultiChoiceItems(PresetManager.availablePumps.toTypedArray(), checkedItems) { _, which, isChecked ->
	                    checkedItems[which] = isChecked
	                }
	                .setPositiveButton("保存") { _, _ ->
	                    val selectedPumps = PresetManager.availablePumps.filterIndexed { index, _ -> checkedItems[index] }
	                    PresetManager.savePumps(item.storageKey, selectedPumps)
	                    binding.recyclerView.adapter?.notifyDataSetChanged()
	                    Toast.makeText(this, "已更新${item.label}", Toast.LENGTH_SHORT).show()
	                }
	                .setNegativeButton("取消", null)
	                .show()
	        } else {
	            // 普通数值输入
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
	            val displayValue = if (item.storageKey.endsWith("_pumps")) {
	                PresetManager.getPumps(item.storageKey, item.defaultValue).joinToString(", ")
	            } else {
	                PresetManager.getPresetValue(item.storageKey, item.defaultValue)
	            }
	            holder.itemBinding.tvLabel.text = item.label
	            holder.itemBinding.tvValue.text = displayValue
	            holder.itemBinding.root.setOnClickListener { onItemClick(item) }
	        }
	        override fun getItemCount() = items.size
	    }
	}
