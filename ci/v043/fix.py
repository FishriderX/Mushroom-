from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, got {count}: {old[:160]!r}")
    s = s.replace(old, new, 1)

# Bird-map target-lock state. The card list is only used to discover ETA and
# navigate to the mushroom detail; the actual respawn race happens on bird view.
replace_once(
    '''    @Volatile private var predictedSpawnAt = 0L
    @Volatile private var predictionReadyAt = 0L
    @Volatile private var predictionWindowUntil = 0L
    @Volatile private var predictionLastSweepAt = 0L
    @Volatile private var predictedSourcePosition = -1
    @Volatile private var rewindResume: RewindResume = RewindResume.SEARCH
''',
    '''    @Volatile private var predictedFinishAt = 0L
    @Volatile private var predictedSpawnAt = 0L
    @Volatile private var predictionReadyAt = 0L
    @Volatile private var predictionWindowUntil = 0L
    @Volatile private var predictionLastSweepAt = 0L
    @Volatile private var predictedSourcePosition = -1
    @Volatile private var targetPositioning = false
    @Volatile private var targetAdvanceRemaining = 0
    @Volatile private var targetDetailOpenPending = false
    @Volatile private var targetMapOpenAttempts = 0
    @Volatile private var targetMapLockPending = false
    @Volatile private var targetMapLockActive = false
    @Volatile private var targetMapAnchorReady = false
    @Volatile private var targetMapAnchorX = 0f
    @Volatile private var targetMapAnchorY = 0f
    @Volatile private var rewindResume: RewindResume = RewindResume.SEARCH
'''
)

replace_once(
    '''    private enum class RewindResume { SEARCH, INSPECT, PARK }
''',
    '''    private enum class RewindResume { SEARCH, INSPECT, PARK, TARGET }
'''
)

# Node-first target-detail route to bird view. If Unity doesn't expose the
# button as an accessibility node, return false so OCR gets the same chance.
replace_once(
    '''        if (normalized.contains("選擇派出皮克敏")) {
            return handleTeamSelectionNodes(entries, normalized)
        }
        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
''',
    '''        if (normalized.contains("選擇派出皮克敏")) {
            return handleTeamSelectionNodes(entries, normalized)
        }
        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            findGoToMapNode(entries)?.let {
                targetDetailOpenPending = false
                targetMapOpenAttempts = 0
                targetMapLockPending = true
                clickNode(it.node, TARGET_MAP_OPEN_COOLDOWN_MS)
                return true
            }
            return false
        }
        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
'''
)

# OCR target-detail route to bird view.
replace_once(
    '''        if (normalized.contains("選擇派出皮克敏")) {
            listContextActive = false
            handleTeamSelectionOcr(frame, lines, normalized)
            return
        }
        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
''',
    '''        if (normalized.contains("選擇派出皮克敏")) {
            listContextActive = false
            handleTeamSelectionOcr(frame, lines, normalized)
            return
        }
        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            listContextActive = false
            handleTargetDetailToBirdMap(frame, lines)
            return
        }
        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
'''
)

# When a go-to-map tap is pending, the next non-detail/non-list Explore frame
# is treated as bird view even if Unity doesn't expose the explicit bird-view
# label or enough participant badges for the generic map detector.
replace_once(
    '''        if (normalized.contains("飾品一覽") && !normalized.contains("花苗和水果")) {
            goBack(220)
            return
        }
        if (looksLikeBirdMap(frame.bitmap, lines, normalized)) {
            listContextActive = false
            handleBirdMap(frame, lines)
            return
        }
''',
    '''        if (normalized.contains("飾品一覽") && !normalized.contains("花苗和水果")) {
            goBack(220)
            return
        }
        val forcedTargetBirdMap =
            targetMapLockPending &&
                !looksLikeMushroomDetail(normalized) &&
                !isMushroomList(normalized) &&
                !ExploreScreenRules.isDecorOnlyExplore(normalized)
        if (forcedTargetBirdMap || looksLikeBirdMap(frame.bitmap, lines, normalized)) {
            listContextActive = false
            if (targetMapLockPending) {
                targetMapLockPending = false
                targetMapLockActive = true
                targetMapAnchorReady = false
                targetMapOpenAttempts = 0
            }
            handleBirdMap(frame, lines)
            return
        }
'''
)

# Target-positioning on the list outranks normal patrol/search. It runs only
# briefly after ETA inspection so we can open the chosen mushroom while its
# detail is still available, then leaves the list completely.
replace_once(
    '''        val position = extractListPosition(normalized)
        val reachedEnd = updateListPositionAndCheckEnd(position)

        // A genuine Pikmin list mutation outranks whatever old scan was doing.
''',
    '''        val position = extractListPosition(normalized)
        val reachedEnd = updateListPositionAndCheckEnd(position)

        if (targetPositioning) {
            handleTargetPositioningList(frame, lines, reachedEnd, position)
            return
        }

        // A genuine Pikmin list mutation outranks whatever old scan was doing.
'''
)

# Add TARGET resume behavior after rewind.
replace_once(
    '''                RewindResume.PARK -> parkAtStart()
            }
            return
''',
    '''                RewindResume.PARK -> parkAtStart()
                RewindResume.TARGET -> {
                    parkedAtStart = false
                    targetPositioning = true
                    targetAdvanceRemaining = (predictedSourcePosition - 1).coerceAtLeast(0)
                    scheduleFreshListScan(140L)
                }
            }
            return
'''
)

# ETA detail parser records the selected source and prediction. The inspection
# still returns to the list so it can compare all candidates before choosing.
replace_once(
    '''    private fun recordPredictedRespawn(finishEtaMs: Long, sourcePosition: Int) {
        val now = SystemClock.elapsedRealtime()
        val predicted = now + finishEtaMs + MUSHROOM_RESPAWN_DELAY_MS
        if (predictedSpawnAt == 0L || predicted < predictedSpawnAt) {
            predictedSpawnAt = predicted
            predictedSourcePosition = sourcePosition
            predictionReadyAt = (predicted - PREDICTION_PREWARM_LEAD_MS).coerceAtLeast(now)
            predictionWindowUntil = predicted + PREDICTION_AFTER_WINDOW_MS
            predictionLastSweepAt = 0L
        }
    }
''',
    '''    private fun recordPredictedRespawn(finishEtaMs: Long, sourcePosition: Int) {
        val now = SystemClock.elapsedRealtime()
        val finishAt = now + finishEtaMs
        val predicted = finishAt + MUSHROOM_RESPAWN_DELAY_MS
        if (predictedSpawnAt == 0L || predicted < predictedSpawnAt) {
            predictedFinishAt = finishAt
            predictedSpawnAt = predicted
            predictedSourcePosition = sourcePosition
            predictionReadyAt = (predicted - PREDICTION_PREWARM_LEAD_MS).coerceAtLeast(now)
            predictionWindowUntil = predicted + PREDICTION_AFTER_WINDOW_MS
            predictionLastSweepAt = 0L
        }
    }
'''
)

# After inspecting the list, navigate to the selected mushroom and use its
# detail page to enter the corresponding bird-view location. Only if no ETA
# target exists do we fall back to parking on card 1.
replace_once(
    '''    private fun finishEtaInspection() {
        etaInspectionActive = false
        etaInspectionAdvancePending = false
        etaInspectionCurrentWasLast = false
        etaInspectionCurrentPosition = -1
        etaInspectionCount = 0
        nextEtaInspectionAt = SystemClock.elapsedRealtime() + ETA_REINSPECTION_MS
        phase = firstPhase()
        beginRewind(RewindResume.PARK)
    }
''',
    '''    private fun finishEtaInspection() {
        etaInspectionActive = false
        etaInspectionAdvancePending = false
        etaInspectionCurrentWasLast = false
        etaInspectionCurrentPosition = -1
        etaInspectionCount = 0
        nextEtaInspectionAt = SystemClock.elapsedRealtime() + ETA_REINSPECTION_MS
        phase = firstPhase()
        if (predictedSpawnAt > 0L && predictedSourcePosition > 0) {
            beginRewind(RewindResume.TARGET)
        } else {
            beginRewind(RewindResume.PARK)
        }
    }
'''
)

# Insert target-positioning and detail-to-map helpers before findAnyMushroomTitle.
replace_once(
    '''    private fun findAnyMushroomTitle(lines: List<Text.Line>): Text.Line? {
''',
    '''    private fun handleTargetPositioningList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        reachedEnd: Boolean,
        position: Pair<Int, Int>?
    ) {
        if (predictedSpawnAt <= 0L || predictedSourcePosition <= 0) {
            targetPositioning = false
            beginRewind(RewindResume.PARK)
            return
        }

        val current = position?.first
        if (current != null) {
            when {
                current < predictedSourcePosition && !reachedEnd -> {
                    swipeListReliable(ETA_INSPECTION_SWIPE_COOLDOWN_MS)
                    return
                }
                current > predictedSourcePosition -> {
                    beginRewind(RewindResume.TARGET)
                    return
                }
            }
        } else if (targetAdvanceRemaining > 0 && !reachedEnd) {
            targetAdvanceRemaining--
            swipeListReliable(ETA_INSPECTION_SWIPE_COOLDOWN_MS)
            return
        }

        val title = findAnyMushroomTitle(lines)
        if (title == null) {
            // Do not wander into unrelated Explore content. Re-scan this frame
            // briefly; if the target card truly vanished, prediction recovery
            // will fall back through the normal list watchdog.
            scheduleFreshListScan(TARGET_CARD_RETRY_MS)
            return
        }

        targetPositioning = false
        targetDetailOpenPending = true
        targetMapOpenAttempts = 0
        listProgressGeneration++
        tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)
    }

    private fun handleTargetDetailToBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
        val goToMap = findGoToMapLine(lines)
        if (goToMap != null) {
            targetDetailOpenPending = false
            targetMapOpenAttempts = 0
            targetMapLockPending = true
            tapOcrButton(frame, goToMap, TARGET_MAP_OPEN_COOLDOWN_MS)
            return
        }

        targetMapOpenAttempts++
        if (targetMapOpenAttempts <= MAX_TARGET_MAP_OPEN_ATTEMPTS) {
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            nextActionAt = SystemClock.elapsedRealtime() + TARGET_MAP_BUTTON_RETRY_MS
            return
        }

        // Safe failure: return to list and keep the prediction rather than
        // tapping an unverified coordinate on the detail page.
        targetDetailOpenPending = false
        targetMapOpenAttempts = 0
        goBack(220L)
    }

    private fun findGoToMapLine(lines: List<Text.Line>): Text.Line? =
        lines.firstOrNull { line ->
            val t = clean(line.text)
            GO_TO_MAP_TEXTS.any { t.contains(it) }
        }

    private fun findGoToMapNode(entries: List<NodeEntry>): NodeEntry? =
        entries.firstOrNull { entry ->
            val t = clean(entry.text)
            GO_TO_MAP_TEXTS.any { t.contains(it) }
        }

    private fun findAnyMushroomTitle(lines: List<Text.Line>): Text.Line? {
'''
)

# Bird-map handler gets a dedicated target-lock path. Generic map patrol is not
# allowed to drag us away during the predicted respawn window.
replace_once(
    '''    private fun handleBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        refreshPending = false
        cleanupRejectedMapPoints()
''',
    '''    private fun handleBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        refreshPending = false
        cleanupRejectedMapPoints()

        if (targetMapLockActive) {
            handleTargetLockedBirdMap(frame, lines)
            return
        }
'''
)

# Insert target-lock map logic before looksLikeBirdMap.
replace_once(
    '''    private fun looksLikeBirdMap(
''',
    '''    private fun handleTargetLockedBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
        val now = SystemClock.elapsedRealtime()
        if (predictedSpawnAt <= 0L) {
            targetMapLockActive = false
            targetMapAnchorReady = false
            return
        }

        val badges = findMapBadges(frame.bitmap, lines)
        if (!targetMapAnchorReady) {
            learnTargetMapAnchor(frame.bitmap, badges)
        }

        if (now < predictionReadyAt) {
            val remaining = predictionReadyAt - now
            nextActionAt = now + remaining.coerceIn(TARGET_MAP_IDLE_MIN_MS, TARGET_MAP_IDLE_MAX_MS)
            return
        }

        if (now > predictionWindowUntil) {
            targetMapLockActive = false
            targetMapAnchorReady = false
            clearPrediction()
            // One safe back step returns toward Explore/detail. Subsequent
            // normal detection re-enters the list without any blind tap.
            goBack(250L)
            return
        }

        burstUntil = max(burstUntil, predictionWindowUntil)
        val candidate = findTargetLockedCandidate(frame.bitmap, badges)
        if (candidate != null) {
            detailCameFromBirdMap = true
            lastBirdMapTapKey = candidate.key
            verifiedTap(
                candidate.x * frame.scaleX,
                candidate.y * frame.scaleY,
                RACE_TARGET_TAP_COOLDOWN_MS
            )
            return
        }

        lastFrameFingerprint = Long.MIN_VALUE
        lastProcessedFrameAt = 0L
        lastOcrAt = 0L
        nextActionAt = now + TARGET_MAP_RACE_POLL_MS
    }

    private fun learnTargetMapAnchor(bitmap: Bitmap, badges: List<MapBadge>) {
        val centerX = bitmap.width * TARGET_MAP_CENTER_X
        val centerY = bitmap.height * TARGET_MAP_CENTER_Y
        val points = ArrayList<Pair<Float, Float>>()
        badges.forEach { points.add(it.x to it.y) }
        findUnbadgedMushroomCandidates(bitmap, badges).forEach { points.add(it.x to it.y) }

        val best = points.minByOrNull { (x, y) ->
            val dx = (x - centerX) / bitmap.width
            val dy = (y - centerY) / bitmap.height
            dx * dx + dy * dy
        }

        if (best != null) {
            targetMapAnchorX = best.first
            targetMapAnchorY = best.second
        } else {
            // "前往這裡" centers the requested mushroom; if visual detection
            // cannot identify the old icon, use the map center as the anchor
            // rather than searching/tapping elsewhere.
            targetMapAnchorX = centerX
            targetMapAnchorY = centerY
        }
        targetMapAnchorReady = true
    }

    private fun findTargetLockedCandidate(
        bitmap: Bitmap,
        badges: List<MapBadge>
    ): MapCandidate? {
        val candidates = ArrayList<MapCandidate>()

        badges.forEach { badge ->
            if (badge.count < FREE_SLOT_LIMIT && !isMapPointRejected(badge.key)) {
                candidates.add(
                    MapCandidate(
                        x = badge.x,
                        y = badge.y,
                        key = badge.key,
                        participantCount = badge.count,
                        score = 20_000 - badge.count * 750
                    )
                )
            }
        }

        candidates.addAll(
            findUnbadgedMushroomCandidates(bitmap, badges)
                .filterNot { isMapPointRejected(it.key) }
        )

        return candidates
            .filter { isNearTargetMapAnchor(it.x, it.y, bitmap) }
            .maxByOrNull { candidate ->
                val dx = (candidate.x - targetMapAnchorX) / bitmap.width
                val dy = (candidate.y - targetMapAnchorY) / bitmap.height
                candidate.score - ((dx * dx + dy * dy) * 50_000f).toInt()
            }
    }

    private fun isNearTargetMapAnchor(x: Float, y: Float, bitmap: Bitmap): Boolean {
        if (!targetMapAnchorReady) return false
        val dx = (x - targetMapAnchorX) / bitmap.width
        val dy = (y - targetMapAnchorY) / bitmap.height
        return dx * dx + dy * dy <= TARGET_MAP_ANCHOR_RADIUS_NORM_SQ
    }

    private fun looksLikeBirdMap(
'''
)

# updatePredictionState must never kick a list sweep while bird-map lock is
# active. The map itself is now the authoritative race surface.
replace_once(
    '''        if (now > predictionWindowUntil) {
            clearPrediction()
            nextEtaInspectionAt = 0L
            if (parkedAtStart && listContextActive) {
''',
    '''        if (now > predictionWindowUntil) {
            if (targetMapLockActive || targetMapLockPending) return
            clearPrediction()
            nextEtaInspectionAt = 0L
            if (parkedAtStart && listContextActive) {
'''
)

# clearPrediction also clears every target-map/navigation state.
replace_once(
    '''    private fun clearPrediction() {
        predictedSpawnAt = 0L
        predictionReadyAt = 0L
        predictionWindowUntil = 0L
        predictionLastSweepAt = 0L
        predictedSourcePosition = -1
    }
''',
    '''    private fun clearPrediction() {
        predictedFinishAt = 0L
        predictedSpawnAt = 0L
        predictionReadyAt = 0L
        predictionWindowUntil = 0L
        predictionLastSweepAt = 0L
        predictedSourcePosition = -1
        targetPositioning = false
        targetAdvanceRemaining = 0
        targetDetailOpenPending = false
        targetMapOpenAttempts = 0
        targetMapLockPending = false
        targetMapLockActive = false
        targetMapAnchorReady = false
        targetMapAnchorX = 0f
        targetMapAnchorY = 0f
    }
'''
)

# Service shutdown clears target lock as well.
replace_once(
    '''        etaInspectionActive = false
        main.removeCallbacks(listWatchdogKick)
''',
    '''        etaInspectionActive = false
        targetPositioning = false
        targetDetailOpenPending = false
        targetMapLockPending = false
        targetMapLockActive = false
        targetMapAnchorReady = false
        main.removeCallbacks(listWatchdogKick)
'''
)

# Constants and accepted text variants.
replace_once(
    '''        private const val ETA_DETAIL_BACK_COOLDOWN_MS = 180L
        private const val MUSHROOM_RESPAWN_DELAY_MS = 5L * 60L * 1000L
''',
    '''        private const val ETA_DETAIL_BACK_COOLDOWN_MS = 180L
        private const val TARGET_CARD_RETRY_MS = 220L
        private const val TARGET_MAP_OPEN_COOLDOWN_MS = 300L
        private const val TARGET_MAP_BUTTON_RETRY_MS = 180L
        private const val MAX_TARGET_MAP_OPEN_ATTEMPTS = 4
        private const val TARGET_MAP_IDLE_MIN_MS = 600L
        private const val TARGET_MAP_IDLE_MAX_MS = 2_000L
        private const val TARGET_MAP_RACE_POLL_MS = 120L
        private const val MUSHROOM_RESPAWN_DELAY_MS = 5L * 60L * 1000L
'''
)

replace_once(
    '''        private const val PREDICTION_SWEEP_INTERVAL_MS = 1_500L
        private val FULL_TEAM_REGEX = Regex("(\\d{1,3})/\\1")
''',
    '''        private const val PREDICTION_SWEEP_INTERVAL_MS = 1_500L
        private const val TARGET_MAP_CENTER_X = 0.50f
        private const val TARGET_MAP_CENTER_Y = 0.46f
        private const val TARGET_MAP_ANCHOR_RADIUS_NORM_SQ = 0.040f
        private val GO_TO_MAP_TEXTS = listOf("前往這裡", "前往此處", "前往該處")
        private val FULL_TEAM_REGEX = Regex("(\\d{1,3})/\\1")
'''
)

p.write_text(s, encoding="utf-8")
print("Applied V0.4.3 bird-view respawn standby fix")
