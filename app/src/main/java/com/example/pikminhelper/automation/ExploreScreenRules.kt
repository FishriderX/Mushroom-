package com.example.pikminhelper.automation

/**
 * Pure screen-classification rules for the Explore page.
 *
 * Important safety rule: decor/seedling UI is never sufficient evidence that
 * the mushroom carousel is active. A mushroom-specific token must be present
 * before list gestures or mushroom-card taps are allowed.
 */
object ExploreScreenRules {
    private val listPositionRegex = Regex("蘑菇[:：]?([0-9]{1,2})/([0-9]{1,2})")

    fun isMushroomList(raw: String): Boolean {
        val s = clean(raw)
        if (!s.contains("蘑菇")) return false

        val hasDailyHeader = s.contains("今天還剩下")
        val hasListPosition = listPositionRegex.containsMatchIn(s)
        val hasExploreBody = s.contains("花苗和水果")
        val hasMushroomType =
            s.contains("巨大蘑菇") ||
            s.contains("華麗蘑菇") ||
            s.contains("活動蘑菇") ||
            s.contains("特殊活動") ||
            s.contains("一般蘑菇") ||
            s.contains("大型蘑菇")

        return hasDailyHeader || hasListPosition || hasExploreBody || hasMushroomType
    }

    fun isDecorOnlyExplore(raw: String): Boolean {
        val s = clean(raw)
        return s.contains("飾品一覽") &&
            s.contains("花苗和水果") &&
            !s.contains("蘑菇")
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), "")
}
