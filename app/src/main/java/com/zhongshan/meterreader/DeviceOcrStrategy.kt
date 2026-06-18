package com.zhongshan.meterreader

import com.zhongshan.meterreader.data.RoiBox

object DeviceOcrStrategy {

    fun totalScreens(machineId: String): Int = when {
        machineId.startsWith("screw_") && !machineId.startsWith("screw_3") -> 3
        else -> 1
    }

    fun screenName(machineId: String, screenIndex: Int): String = when {
        machineId.startsWith("screw_") && !machineId.startsWith("screw_3") -> when (screenIndex) {
            0 -> "蒸发器"; 1 -> "冷凝器"; 2 -> "压缩机与电流"; else -> "完成"
        }
        else -> "总览"
    }

    /**
     * 真正的硬编码识别策略已经废弃。
     * 实际识别逻辑请使用 `RoiConfigManager` 提供的坐标，
     * 交由 `OCRFacade` 进行裁剪和识别。
     * 此文件仅保留分屏数量等基础逻辑，保持兼容性。
     */
    fun getFixedRoiFieldIds(machineId: String, screenIndex: Int): List<String> {
        // 如果你不需要使用 RoiConfigManager 打点，而是希望我把硬编码坐标写在代码里，
        // 你后续把端正的图片发给我，我会修改这个函数，返回绝对坐标 List<RoiBox>
        return emptyList()
    }
}
