package com.example.pikminhelper.automation

object MushroomReachability {
    private val outOfRangeMarkers = listOf(
        "距離太遠",
        "距離過遠",
        "離得太遠",
        "目前位置太遠",
        "超出範圍",
        "超出參加範圍",
        "參加範圍外",
        "不在參加範圍",
        "不在可參加範圍",
        "無法從目前位置參加",
        "無法從這裡參加",
        "無法參加這個蘑菇"
    )

    fun isOutOfRangeText(value: String): Boolean {
        val normalized = value.replace(Regex("\\s+"), "")
        return outOfRangeMarkers.any { normalized.contains(it) }
    }
}
