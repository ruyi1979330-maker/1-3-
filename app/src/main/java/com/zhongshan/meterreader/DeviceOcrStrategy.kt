	package com.zhongshan.meterreader
	object DeviceOcrStrategy {
	    data class HardcodedRoi(
	        val xPercent: Float,
	        val yPercent: Float,
	        val wPercent: Float,
	        val hPercent: Float,
	        val fieldId: String,
	        val label: String
	    )
	    data class RoiRelative(
	        val xStartPct: Float,
	        val yStartPct: Float,
	        val xEndPct: Float,
	        val yEndPct: Float,
	        val fieldId: String,
	        val label: String
	    )
	    // =====================================================================
	    // 绝对百分比坐标（基于3000×4000带黑边原图，相机模式使用）
	    // =====================================================================
	    private val screw1Evaporator = listOf(
	        HardcodedRoi(0.7600f, 0.3450f, 0.1800f, 0.0400f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
	        HardcodedRoi(0.7500f, 0.4050f, 0.1900f, 0.0400f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
	        HardcodedRoi(0.7600f, 0.4650f, 0.1800f, 0.0400f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度"),
	        HardcodedRoi(0.6800f, 0.5250f, 0.2600f, 0.0400f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力")
	    )
	    private val screw1Condenser = listOf(
	        HardcodedRoi(0.7600f, 0.3450f, 0.1800f, 0.0400f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
	        HardcodedRoi(0.7600f, 0.4050f, 0.1800f, 0.0400f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
	        HardcodedRoi(0.7600f, 0.4650f, 0.1800f, 0.0400f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度"),
	        HardcodedRoi(0.6800f, 0.5250f, 0.2600f, 0.0400f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力")
	    )
	    private val screw1Compressor = listOf(
	        HardcodedRoi(0.6800f, 0.3450f, 0.2600f, 0.0400f, "field_1_14|压缩机油压", "油压"),
	        HardcodedRoi(0.7600f, 0.4650f, 0.1800f, 0.0400f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
	        HardcodedRoi(0.4300f, 0.5850f, 0.1200f, 0.0400f, "field_1_18|主机负载", "%RLA"),
	        HardcodedRoi(0.4100f, 0.6450f, 0.1400f, 0.0400f, "field_1_17|电机电流", "电流L1 L2 L3")
	    )
	    private val yorkCentrifugal = listOf(
	        // 左上角区域
	        HardcodedRoi(0.2600f, 0.4000f, 0.1000f, 0.0300f, "field_1_76|压缩机排气温度", "压缩机出口温度"),
	        HardcodedRoi(0.2600f, 0.4300f, 0.0800f, 0.0300f, "field_1_82|压缩机导液开度", "%满载安培"),
	        // 右上角区域
	        HardcodedRoi(0.7800f, 0.3800f, 0.1300f, 0.0300f, "field_1_75|压缩机油箱温度", "油槽温度"),
	        HardcodedRoi(0.7800f, 0.4200f, 0.1300f, 0.0300f, "field_1_74|压缩机油泵压力", "油压"),
	        // 左侧中间 冷却水
	        HardcodedRoi(0.1000f, 0.5100f, 0.1000f, 0.0300f, "field_1_71|冷凝器出口水温", "冷却水温度 出水"),
	        HardcodedRoi(0.1000f, 0.5400f, 0.1000f, 0.0300f, "field_1_79|冷凝器进口水温", "冷却水温度 返回"),
	        // 右侧中间 冷冻水
	        HardcodedRoi(0.7800f, 0.5100f, 0.1300f, 0.0300f, "field_1_69|蒸发器出口水温", "冷冻水温度 出水"),
	        HardcodedRoi(0.7800f, 0.5400f, 0.1300f, 0.0300f, "field_1_68|蒸发器进口水温", "冷冻水温度 返回"),
	        // 底部左侧 冷凝器
	        HardcodedRoi(0.2600f, 0.7700f, 0.1300f, 0.0300f, "field_1_77|冷凝器冷媒压力", "冷凝器压力"),
	        HardcodedRoi(0.2600f, 0.8000f, 0.1300f, 0.0300f, "field_1_81|冷凝器冷凝温度", "冷凝器饱和温度"),
	        // 底部右侧 蒸发器
	        HardcodedRoi(0.6800f, 0.7700f, 0.1800f, 0.0300f, "field_1_78|蒸发器冷媒压力", "蒸发器压力"),
	        HardcodedRoi(0.6800f, 0.8000f, 0.1800f, 0.0300f, "field_1_70|蒸发器蒸发温度", "蒸发器饱和温度")
	    )
	    private val screw3_1_abs = listOf(
	        // 左上角区域
	        HardcodedRoi(0.2600f, 0.4000f, 0.1300f, 0.0300f, "field_3_14|压缩机油压", "油压差"),
	        HardcodedRoi(0.2600f, 0.4300f, 0.1000f, 0.0300f, "field_3_15|压缩机油箱温度", "油温"),
	        // 右上角区域
	        HardcodedRoi(0.8200f, 0.4000f, 0.1000f, 0.0300f, "field_3_16|压缩机排口温度", "压缩机出口温度"),
	        HardcodedRoi(0.8200f, 0.4500f, 0.1000f, 0.0300f, "field_3_17|压缩机导液开度", "滑阀位置"),
	        // 左侧中间 冷却水
	        HardcodedRoi(0.1000f, 0.5400f, 0.1000f, 0.0300f, "field_3_09|冷凝器出口水温", "冷却水温度 出水"),
	        HardcodedRoi(0.1000f, 0.5700f, 0.1000f, 0.0300f, "field_3_08|冷凝器进口水温", "冷却水温度 返回"),
	        // 右侧中间 冷冻水
	        HardcodedRoi(0.7800f, 0.5400f, 0.1300f, 0.0300f, "field_3_02|蒸发器出口水温", "冷冻水温度 出水"),
	        HardcodedRoi(0.7800f, 0.5700f, 0.1300f, 0.0300f, "field_3_01|蒸发器进口水温", "冷冻水温度 返回"),
	        // 底部左侧 冷凝器
	        HardcodedRoi(0.2600f, 0.7900f, 0.1400f, 0.0300f, "field_3_12|冷凝器冷凝压力", "冷凝器压力"),
	        HardcodedRoi(0.2600f, 0.8200f, 0.1400f, 0.0300f, "field_3_13|冷凝器冷凝温度", "冷凝器饱和温度"),
	        // 底部右侧 蒸发器
	        HardcodedRoi(0.6800f, 0.7900f, 0.1800f, 0.0300f, "field_3_05|蒸发器蒸发压力", "蒸发压力"),
	        HardcodedRoi(0.6800f, 0.8200f, 0.1800f, 0.0300f, "field_3_06|蒸发器蒸发温度", "蒸发器饱和温度")
	    )
	    private val absoluteConfigs: Map<String, List<HardcodedRoi>> = run {
	        val map = mutableMapOf<String, List<HardcodedRoi>>()
	        map["screw_1_0"] = screw1Evaporator
	        map["screw_1_1"] = screw1Condenser
	        map["screw_1_2"] = screw1Compressor
	        map["screw_2_0"] = screw1Evaporator.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map["screw_2_1"] = screw1Condenser.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map["screw_2_2"] = screw1Compressor.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map["screw_3_0"] = screw1Evaporator.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
	        map["screw_3_1"] = screw1Condenser.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
	        map["screw_3_2"] = screw1Compressor.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
	        map["cent_1_0"] = yorkCentrifugal
	        map["screw_3_1_0"] = screw3_1_abs
	        map["screw_3_2_0"] = screw3_1_abs.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map
	    }
	    // =====================================================================
	    // 相对百分比坐标（基于纯发光屏幕区域，图库裁切模式使用）
	    // 所有坐标均由有效识别日志像素反推，位置经过验证
	    // =====================================================================
	    private val screw1EvapRel = listOf(
	        RoiRelative(0.7522f, 0.2728f, 0.9461f, 0.3232f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
	        // 左边界左移，确保数字7完整纳入，解决7识别为2的问题
	        RoiRelative(0.7000f, 0.3705f, 0.9491f, 0.4218f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
	        RoiRelative(0.7791f, 0.4677f, 0.9476f, 0.5209f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度"),
	        RoiRelative(0.6107f, 0.5654f, 0.9491f, 0.6226f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力")
	    )
	    private val screw1CondRel = listOf(
	        RoiRelative(0.8295f, 0.2713f, 0.9728f, 0.3260f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
	        RoiRelative(0.8295f, 0.3719f, 0.9707f, 0.4207f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
	        RoiRelative(0.8259f, 0.4694f, 0.9696f, 0.5241f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度"),
	        RoiRelative(0.7334f, 0.5659f, 0.9672f, 0.6234f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力")
	    )
	    private val screw1CompRel = listOf(
	        RoiRelative(0.7350f, 0.2707f, 0.9697f, 0.3295f, "field_1_14|压缩机油压", "油压"),
	        RoiRelative(0.8266f, 0.4676f, 0.9651f, 0.5187f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
	        // 精确对准负载行，仅包含第一个数值
	        RoiRelative(0.4764f, 0.6568f, 0.5990f, 0.7124f, "field_1_18|主机负载", "%RLA"),
	        // 精确对准电流行，仅包含L1数值
	        RoiRelative(0.4542f, 0.7580f, 0.6000f, 0.8095f, "field_1_17|电机电流", "电流L1 L2 L3")
	    )
	    private val centRel = listOf(
	        // 左上角
	        RoiRelative(0.2570f, 0.1600f, 0.3560f, 0.1990f, "field_1_76|压缩机排气温度", "压缩机出口温度"),
	        // 屏蔽：RoiRelative(0.2570f, 0.2040f, 0.3308f, 0.2410f, "field_1_82|压缩机导液开度", "%满载安培"),
	        // 右上角
	        RoiRelative(0.8150f, 0.1390f, 0.9430f, 0.1790f, "field_1_75|压缩机油箱温度", "油槽温度"),
	        RoiRelative(0.8120f, 0.1850f, 0.9440f, 0.2250f, "field_1_74|压缩机油泵压力", "油压"),
	        // 左中 冷却水
	        RoiRelative(0.1144f, 0.3585f, 0.2125f, 0.3992f, "field_1_71|冷凝器出口水温", "冷却水温度 出水"),
	        RoiRelative(0.1154f, 0.4050f, 0.2136f, 0.4443f, "field_1_79|冷凝器进口水温", "冷却水温度 返回"),
	        // 右中 冷冻水
	        RoiRelative(0.8470f, 0.3620f, 0.9420f, 0.4010f, "field_1_69|蒸发器出口水温", "冷冻水温度 出水"),
	        // 屏蔽：RoiRelative(0.8460f, 0.4050f, 0.9420f, 0.4400f, "field_1_68|蒸发器进口水温", "冷冻水温度 返回"),
	        // 底部左侧 冷凝器
	        RoiRelative(0.2550f, 0.9000f, 0.3830f, 0.9430f, "field_1_77|冷凝器冷媒压力", "冷凝器压力"),
	        RoiRelative(0.2570f, 0.9480f, 0.3850f, 0.9850f, "field_1_81|冷凝器冷凝温度", "冷凝器饱和温度"),
	        // 底部右侧 蒸发器
	        // 屏蔽：RoiRelative(0.7830f, 0.9040f, 0.9100f, 0.9410f, "field_1_78|蒸发器冷媒压力", "蒸发器压力"),
	        // 屏蔽：RoiRelative(0.7820f, 0.9480f, 0.9090f, 0.9856f, "field_1_70|蒸发器蒸发温度", "蒸发器饱和温度")
	    )
	    private val screw3_1_rel = listOf(
	        // 左上角
	        RoiRelative(0.2630f, 0.1560f, 0.3890f, 0.1900f, "field_3_14|压缩机油压", "油压差"),
	        RoiRelative(0.2630f, 0.1950f, 0.3550f, 0.2320f, "field_3_15|压缩机油箱温度", "油温"),
	        // 右上角
	        // 修复：上轮修改高度过小导致识别为空，现调整Y轴下边界至0.1820f，保证文字高度同时避免截到下一行
	        RoiRelative(0.8800f, 0.1470f, 0.9500f, 0.1820f, "field_3_16|压缩机排口温度", "压缩机出口温度"),
	        RoiRelative(0.8820f, 0.2920f, 0.9500f, 0.3310f, "field_3_17|压缩机导液开度", "滑阀位置"),
	        // 左中 冷却水
	        RoiRelative(0.1140f, 0.4010f, 0.2080f, 0.4360f, "field_3_09|冷凝器出口水温", "冷却水温度 出水"),
	        RoiRelative(0.1140f, 0.4420f, 0.2070f, 0.4830f, "field_3_08|冷凝器进口水温", "冷却水温度 返回"),
	        // 右中 冷冻水
	        RoiRelative(0.8400f, 0.4050f, 0.9370f, 0.4440f, "field_3_02|蒸发器出口水温", "冷冻水温度 出水"),
	        RoiRelative(0.8400f, 0.4500f, 0.9380f, 0.4890f, "field_3_01|蒸发器进口水温", "冷冻水温度 返回"),
	        // 底部左侧 冷凝器
	        RoiRelative(0.2540f, 0.8960f, 0.3780f, 0.9330f, "field_3_12|冷凝器冷凝压力", "冷凝器压力"),
	        RoiRelative(0.2550f, 0.9380f, 0.3780f, 0.9770f, "field_3_13|冷凝器冷凝温度", "冷凝器饱和温度"),
	        // 底部右侧 蒸发器
	        RoiRelative(0.7730f, 0.9040f, 0.9010f, 0.9410f, "field_3_05|蒸发器蒸发压力", "蒸发压力"),
	        RoiRelative(0.7750f, 0.9490f, 0.9030f, 0.9865f, "field_3_06|蒸发器蒸发温度", "蒸发器饱和温度")
	    )
	    private val relativeConfigs: Map<String, List<RoiRelative>> = run {
	        val map = mutableMapOf<String, List<RoiRelative>>()
	        map["screw_1_0"] = screw1EvapRel
	        map["screw_1_1"] = screw1CondRel
	        map["screw_1_2"] = screw1CompRel
	        map["screw_2_0"] = screw1EvapRel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map["screw_2_1"] = screw1CondRel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map["screw_2_2"] = screw1CompRel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map["screw_3_0"] = screw1EvapRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
	        map["screw_3_1"] = screw1CondRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
	        map["screw_3_2"] = screw1CompRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
	        map["cent_1_0"] = centRel
	        map["screw_3_1_0"] = screw3_1_rel
	        map["screw_3_2_0"] = screw3_1_rel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
	        map
	    }
	    // ============ 公共方法 ============
	    fun getHardcodedRois(machineId: String, screenIndex: Int): List<HardcodedRoi> {
	        return absoluteConfigs["${machineId}_${screenIndex}"] ?: emptyList()
	    }
	    fun getRelativeRois(machineId: String, screenIndex: Int): List<RoiRelative> {
	        return relativeConfigs["${machineId}_${screenIndex}"] ?: emptyList()
	    }
	    fun totalScreens(machineId: String): Int = when (machineId) {
	        "screw_1", "screw_2", "screw_3" -> 3
	        else -> 1
	    }
	    fun screenName(machineId: String, screenIndex: Int): String = when {
	        machineId in listOf("screw_1", "screw_2", "screw_3") -> when (screenIndex) {
	            0 -> "蒸发器"; 1 -> "冷凝器"; 2 -> "压缩机与电流"; else -> "完成"
	        }
	        machineId == "cent_1"   -> "系统总览"
	        machineId.startsWith("screw_3_") -> "系统总览"
	        else -> "全组板交"
	    }
	    private fun shiftId(compoundId: String, offset: Int): String {
	        val parts = compoundId.split("|")
	        val rawId = parts[0]
	        val label = if (parts.size > 1) parts[1] else ""
	        val lastUnderscore = rawId.lastIndexOf("_")
	        val prefix = rawId.substring(0, lastUnderscore + 1)
	        val num = rawId.substring(lastUnderscore + 1).toInt() + offset
	        val newId = prefix + num.toString().padStart(2, '0')
	        return if (label.isNotEmpty()) "$newId|$label" else newId
	    }
	}
