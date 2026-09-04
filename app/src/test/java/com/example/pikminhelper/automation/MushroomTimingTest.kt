package com.example.pikminhelper.automation

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MushroomTimingTest {
    @Test
    fun parsesMinutesAndSeconds() {
        assertEquals(5 * 60_000L + 12_000L, MushroomTiming.parseFinishEtaMillis("還有 5 分 12 秒"))
    }

    @Test
    fun parsesHoursAndMinutes() {
        assertEquals(65 * 60_000L, MushroomTiming.parseFinishEtaMillis("剩餘1小時5分鐘"))
    }

    @Test
    fun parsesAbsoluteFinishTimeToday() {
        val now = LocalTime.of(9, 0, 0)
        assertEquals(7 * 60_000L, MushroomTiming.parseFinishEtaMillis("預計09:07結束", now))
    }

    @Test
    fun parsesAbsoluteFinishTimeAcrossMidnight() {
        val now = LocalTime.of(23, 58, 0)
        assertEquals(5 * 60_000L, MushroomTiming.parseFinishEtaMillis("00:03完成", now))
    }

    @Test
    fun ignoresUnrelatedClockText() {
        assertNull(MushroomTiming.parseFinishEtaMillis("現在時間09:07"))
    }
}
