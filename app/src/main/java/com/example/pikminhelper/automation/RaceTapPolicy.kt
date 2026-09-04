package com.example.pikminhelper.automation

object RaceTapPolicy {
    /**
     * Direct-target retry cadence around a predicted mushroom respawn.
     * Keep the critical first seconds aggressive, then taper while remaining
     * active for the entire prediction window. The caller owns the hard window.
     */
    fun intervalMs(now: Long, predictedSpawnAt: Long): Long {
        val delta = now - predictedSpawnAt
        return when {
            delta < 5_000L -> 350L
            delta < 20_000L -> 550L
            delta < 60_000L -> 900L
            else -> 1_500L
        }
    }
}
