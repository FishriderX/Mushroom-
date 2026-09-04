package com.example.pikminhelper.automation

import java.time.Duration
import java.time.LocalTime

object MushroomTiming {
    private const val MAX_REASONABLE_ETA_MS = 24L * 60L * 60L * 1000L

    fun parseFinishEtaMillis(raw: String, now: LocalTime = LocalTime.now()): Long? {
        val s = raw.replace(Regex("\\s+"), "")

        HOUR_MINUTE_SECOND.find(s)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
            val sec = m.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
            return bounded((h * 3600L + min * 60L + sec) * 1000L)
        }

        MINUTE_SECOND.find(s)?.let { m ->
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
            return bounded((min * 60L + sec) * 1000L)
        }

        SECOND_ONLY.find(s)?.let { m ->
            val sec = m.groupValues[1].toLongOrNull() ?: return@let
            return bounded(sec * 1000L)
        }

        CLOCK_AFTER.find(s)?.let { m ->
            parseClock(m.groupValues[1], m.groupValues[2], now)?.let { return it }
        }
        CLOCK_BEFORE.find(s)?.let { m ->
            parseClock(m.groupValues[1], m.groupValues[2], now)?.let { return it }
        }

        return null
    }

    private fun parseClock(hourText: String, minuteText: String, now: LocalTime): Long? {
        val hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val target = LocalTime.of(hour, minute)
        var seconds = Duration.between(now, target).seconds
        if (seconds < -60L) seconds += 24L * 60L * 60L
        if (seconds < 0L) seconds = 0L
        return bounded(seconds * 1000L)
    }

    private fun bounded(value: Long): Long? =
        value.takeIf { it in 0L..MAX_REASONABLE_ETA_MS }

    private val HOUR_MINUTE_SECOND = Regex(
        "(?:剩餘|剩下|還有|約|預計)?([0-9]{1,2})小時(?:([0-9]{1,2})分(?:鐘)?)?(?:([0-9]{1,2})秒)?"
    )
    private val MINUTE_SECOND = Regex(
        "(?:剩餘|剩下|還有|約|預計)?([0-9]{1,3})分(?:鐘)?(?:([0-9]{1,2})秒)?"
    )
    private val SECOND_ONLY = Regex(
        "(?:剩餘|剩下|還有|約|預計)?([0-9]{1,4})秒"
    )
    private val CLOCK_AFTER = Regex(
        "(?:預計|約)?([0-9]{1,2})[:：]([0-9]{2})(?:左右)?(?:結束|完成)"
    )
    private val CLOCK_BEFORE = Regex(
        "(?:結束|完成)(?:時間)?[:：]?([0-9]{1,2})[:：]([0-9]{2})"
    )
}
