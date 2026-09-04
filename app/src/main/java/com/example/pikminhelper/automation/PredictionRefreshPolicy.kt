package com.example.pikminhelper.automation

object PredictionRefreshPolicy {
    /** Exit/re-enter Explore shortly before the predicted respawn so the
     * mushroom list is freshly loaded around the server-side spawn time. */
    const val FIRST_REFRESH_LEAD_MS = 1_800L
    const val RETRY_REFRESH_INTERVAL_MS = 1_400L

    fun firstRefreshAt(predictedSpawnAt: Long): Long =
        (predictedSpawnAt - FIRST_REFRESH_LEAD_MS).coerceAtLeast(0L)

    fun shouldRefresh(
        now: Long,
        predictedSpawnAt: Long,
        predictionWindowUntil: Long,
        nextRefreshAt: Long
    ): Boolean =
        predictedSpawnAt > 0L &&
            now <= predictionWindowUntil &&
            now >= maxOf(firstRefreshAt(predictedSpawnAt), nextRefreshAt)
}
