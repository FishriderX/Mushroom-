package com.example.pikminhelper.automation

import java.time.DayOfWeek
import java.time.LocalDate

enum class MushroomKind {
    GIANT,
    EVENT,
    OTHER
}

object MushroomPolicy {
    fun chooseTarget(
        candidates: List<DetectedMushroom>,
        date: LocalDate = LocalDate.now()
    ): DetectedMushroom? {
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY ||
            date.dayOfWeek == DayOfWeek.SUNDAY

        if (!weekend) return candidates.maxByOrNull { it.confidence }

        candidates.filter { it.kind == MushroomKind.GIANT }
            .maxByOrNull { it.confidence }
            ?.let { return it }

        return candidates.filter { it.kind == MushroomKind.EVENT }
            .maxByOrNull { it.confidence }
    }
}

data class DetectedMushroom(
    val kind: MushroomKind,
    val x: Float,
    val y: Float,
    val confidence: Float
)
