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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showEditDialog(item: PresetManager.PresetItem) {
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