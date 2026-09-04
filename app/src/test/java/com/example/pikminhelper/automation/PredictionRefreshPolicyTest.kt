package com.example.pikminhelper.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionRefreshPolicyTest {
    @Test
    fun firstRefreshStartsBeforePredictedSpawn() {
        val spawn = 100_000L
        assertEquals(98_200L, PredictionRefreshPolicy.firstRefreshAt(spawn))
    }

    @Test
    fun refreshNeverRunsBeforeLeadOrAfterWindow() {
        val spawn = 100_000L
        val until = 190_000L
        assertFalse(PredictionRefreshPolicy.shouldRefresh(98_199L, spawn, until, 0L))
        assertTrue(PredictionRefreshPolicy.shouldRefresh(98_200L, spawn, until, 0L))
        assertFalse(PredictionRefreshPolicy.shouldRefresh(190_001L, spawn, until, 0L))
    }

    @Test
    fun retryDeadlineIsRespected() {
        val spawn = 100_000L
        val until = 190_000L
        assertFalse(PredictionRefreshPolicy.shouldRefresh(101_000L, spawn, until, 101_400L))
        assertTrue(PredictionRefreshPolicy.shouldRefresh(101_400L, spawn, until, 101_400L))
    }
}
