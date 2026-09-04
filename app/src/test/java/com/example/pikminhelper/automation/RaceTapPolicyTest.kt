package com.example.pikminhelper.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceTapPolicyTest {
    @Test
    fun criticalWindowStaysFast() {
        val spawn = 1_000_000L
        assertEquals(350L, RaceTapPolicy.intervalMs(spawn - 800L, spawn))
        assertEquals(350L, RaceTapPolicy.intervalMs(spawn, spawn))
        assertEquals(350L, RaceTapPolicy.intervalMs(spawn + 4_999L, spawn))
    }

    @Test
    fun retriesTaperButNeverStopInsidePredictionWindow() {
        val spawn = 1_000_000L
        assertEquals(550L, RaceTapPolicy.intervalMs(spawn + 5_000L, spawn))
        assertEquals(900L, RaceTapPolicy.intervalMs(spawn + 20_000L, spawn))
        assertEquals(1_500L, RaceTapPolicy.intervalMs(spawn + 60_000L, spawn))
        assertEquals(1_500L, RaceTapPolicy.intervalMs(spawn + 89_000L, spawn))
    }
}
