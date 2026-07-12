	// ====================文件名：PresetSettingsActivity.kt 完整可直接复制代码====================
	// 项目：医院特灵冷水机组OCR抄表APP
	// 修改版本：V2.0 一级菜单固定冷冻泵直选重构
	// 改动标记：所有新增/修改逻辑标注 //【本次重构改动点】
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
	        // ===== 以下为旧版冷冻泵8泵全选弹窗逻辑，V2.0已废弃，注释保留以支持一键回滚 =====
	        // // 判断是否为冷冻泵勾选项
	        // if (item.storageKey.endsWith("_pumps")) {
	        //     val currentPumps = PresetManager.getPumps(item.storageKey, item.defaultValue)
	        //     val checkedItems = PresetManager.availablePumps.map { currentPumps.contains(it) }.toBooleanArray()
	        //     AlertDialog.Builder(this)
	        //         .setTitle("勾选: ${item.label}")
	        //         .setMultiChoiceItems(PresetManager.availablePumps.toTypedArray(), checkedItems) { _, which, isChecked ->
	        //             checkedItems[which] = isChecked
	        //         }
	        //         .setPositiveButton("保存") { _, _ ->
	        //             val selectedPumps = PresetManager.availablePumps.filterIndexed { index, _ -> checkedItems[index] }
	        //             PresetManager.savePumps(item.storageKey, selectedPumps)
	        //             binding.recyclerView.adapter?.notifyDataSetChanged()
	        //             Toast.makeText(this, "已更新${item.label}", Toast.LENGTH_SHORT).show()
	        //         }
	        //         .setNegativeButton("取消", null)
	        //         .show()
	        // }
	        // ===== 旧版弹窗逻辑结束 =====
	        // 【本次重构改动点】冷冻泵条目不再使用弹窗，行内复选框直接处理勾选
	        // 若为冷冻泵条目则直接返回，不弹出任何对话框
	        if (item.storageKey.endsWith("_pumps")) return
	        // 普通数值输入（水压条目，逻辑完全保留不改动）
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
	            // 【本次重构改动点】行内复选框容器
	            // 用于冷冻泵条目右侧直接展示2台固定泵的勾选框，替代旧版8泵弹窗
	            // 使用LinearLayout水平排列，程序化创建无需修改XML布局
	            val pumpCheckboxContainer: LinearLayout = LinearLayout(itemView.context).apply {
	                orientation = LinearLayout.HORIZONTAL
	                layoutParams = LinearLayout.LayoutParams(
	                    ViewGroup.LayoutParams.WRAP_CONTENT,
	                    ViewGroup.LayoutParams.WRAP_CONTENT
	                )
	                // 初始隐藏，仅冷冻泵条目显示
	                visibility = View.GONE
	            }
	            init {
	                // 【本次重构改动点】将复选框容器添加到列表项根布局
	                // 仅添加一次，防止RecyclerView复用时重复添加
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
	            // 【本次重构改动点】根据条目类型区分渲染逻辑
	            if (item.storageKey.endsWith("_pumps")) {
	                // ===== 冷冻泵条目：行内复选框直选模式 =====
	                holder.itemBinding.tvLabel.text = item.label
	                // 隐藏水压数值文本框
	                holder.itemBinding.tvValue.visibility = View.GONE
	                // 显示行内复选框容器
	                holder.pumpCheckboxContainer.visibility = View.VISIBLE
	                // 清除旧复选框（RecyclerView复用处理）
	                holder.pumpCheckboxContainer.removeAllViews()
	                // 【本次重构改动点】从PresetManager获取该机组绑定的固定2台泵
	                // 不在页面写死泵号，统一从PresetManager.fixedPumpMapping读取
	                val fixedPumps = PresetManager.getFixedPumpsForItem(item.storageKey)
	                // 为每台固定泵创建一个复选框
	                for (pumpName in fixedPumps) {
	                    val checkBox = CheckBox(holder.itemView.context).apply {
	                        // 设置泵名称
	                        text = pumpName
	                        // 字号与标签一致，保持UI统一
	                        textSize = holder.itemBinding.tvLabel.textSize / resources.displayMetrics.density
	                        // 【本次重构改动点】回显历史勾选状态
	                        // 从本地缓存读取该泵是否已勾选
	                        isChecked = PresetManager.isPumpSelected(item.storageKey, pumpName)
	                        // 【本次重构改动点】复选框状态变更监听
	                        // 勾选/取消勾选后立即写入本地缓存，无需弹窗保存确认
	                        setOnCheckedChangeListener { _, isChecked ->
	                            PresetManager.setPumpSelected(item.storageKey, pumpName, isChecked)
	                        }
	                    }
	                    holder.pumpCheckboxContainer.addView(checkBox)
	                }
	                // 【本次重构改动点】冷冻泵条目不再响应点击弹窗
	                // 行内复选框已直接处理交互，无需点击整行触发对话框
	                holder.itemBinding.root.setOnClickListener(null)
	            } else {
	                // ===== 水压条目：保持原始逻辑完全不变 =====
	                // 隐藏复选框容器
	                holder.pumpCheckboxContainer.visibility = View.GONE
	                // 显示水压数值文本框
	                holder.itemBinding.tvValue.visibility = View.VISIBLE
	                // 设置标签和数值
	                val displayValue = PresetManager.getPresetValue(item.storageKey, item.defaultValue)
	                holder.itemBinding.tvLabel.text = item.label
	                holder.itemBinding.tvValue.text = displayValue
	                // 水压条目保留点击编辑弹窗逻辑
	                holder.itemBinding.root.setOnClickListener { onItemClick(item) }
	            }
	        }
	        override fun getItemCount() = items.size
	    }
	}
