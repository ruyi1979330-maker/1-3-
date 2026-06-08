private fun extractNextNumericValue(lines: List<String>, currentIndex: Int): String? {
        val regex = """\d+(\.\d+)?""".toRegex()
        val searchEnd = minOf(currentIndex + 4, lines.size)
        // 创建暴力屏蔽字典：抹除可能干扰数字提取的环境文本
        val noisePattern = Regex("[0-9]+号[楼]?|[0-9]+#|板交|进[水口]温度|出[水口]温度|进[水口]压力|出[水口]压力|蒸汽压力|水泵电流|请填写数值|℃|MPa|KPa|A|%", RegexOption.IGNORE_CASE)
        
        for (i in currentIndex until searchEnd) {
            // 核心修复：先用正则干掉所有干扰词汇，留下纯净的数值供后续提取
            val cleanedLine = lines[i].replace(noisePattern, "")
            val candidate = regex.find(cleanedLine)?.value ?: continue
            val cleaned = cleanOcrText(candidate)
            if (cleaned.isNotEmpty()) return cleaned
        }
        return null
    }
