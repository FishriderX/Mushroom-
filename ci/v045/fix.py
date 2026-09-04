from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, got {count}: {old[:180]!r}")
    s = s.replace(old, new, 1)

replace_once(
'''    @Volatile private var detailCameFromBirdMap = false
    @Volatile private var lastBirdMapTapKey: Int? = null
    private val rejectedBirdMapPoints = ConcurrentHashMap<Int, Long>()
''',
'''    @Volatile private var detailCameFromBirdMap = false
    @Volatile private var lastBirdMapTapKey: Int? = null
    @Volatile private var detailSourceListPosition = -1
    private val rejectedBirdMapPoints = ConcurrentHashMap<Int, Long>()
    private val unreachableListPositions = ConcurrentHashMap<Int, Long>()
'''
)

replace_once(
'''        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            findGoToMapNode(entries)?.let {
''',
'''        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            if (isOutOfRangeDetail(entries, normalized)) {
                rejectTargetOutOfRange()
                return true
            }
            findGoToMapNode(entries)?.let {
'''
)

replace_once(
'''        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
            handleEtaInspectionDetail(normalized)
            return true
        }
''',
'''        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
            if (isOutOfRangeDetail(entries, normalized)) {
                skipEtaOutOfRange()
            } else {
                handleEtaInspectionDetail(normalized)
            }
            return true
        }
'''
)

replace_once(
'''    private fun handleDetailNodes(
        entries: List<NodeEntry>,
        normalized: String
    ): Boolean {
        autoTapAttempts = 0
''',
'''    private fun handleDetailNodes(
        entries: List<NodeEntry>,
        normalized: String
    ): Boolean {
        autoTapAttempts = 0
        if (isOutOfRangeDetail(entries, normalized)) {
            markCurrentTargetUnreachable()
            rejectDetailAndAdvance()
            return true
        }
'''
)

replace_once(
'''        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            listContextActive = false
            handleTargetDetailToBirdMap(frame, lines)
            return
        }
''',
'''        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            listContextActive = false
            if (MushroomReachability.isOutOfRangeText(normalized)) {
                rejectTargetOutOfRange()
            } else {
                handleTargetDetailToBirdMap(frame, lines)
            }
            return
        }
'''
)

replace_once(
'''        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
            listContextActive = false
            handleEtaInspectionDetail(normalized)
            return
        }
''',
'''        if (etaInspectionActive && looksLikeMushroomDetail(normalized)) {
            listContextActive = false
            if (MushroomReachability.isOutOfRangeText(normalized)) {
                skipEtaOutOfRange()
            } else {
                handleEtaInspectionDetail(normalized)
            }
            return
        }
'''
)

replace_once(
'''    private fun handleDetailOcr(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        autoTapAttempts = 0
''',
'''    private fun handleDetailOcr(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        autoTapAttempts = 0
        if (MushroomReachability.isOutOfRangeText(normalized)) {
            markCurrentTargetUnreachable()
            rejectDetailAndAdvance()
            return
        }
'''
)

replace_once(
'''        if (etaInspectionActive) {
            handleEtaInspectionList(frame, lines, reachedEnd, position)
            return
        }

        if (forceAdvanceOnList) {
''',
'''        if (etaInspectionActive) {
            handleEtaInspectionList(frame, lines, reachedEnd, position)
            return
        }

        if (position != null && isListPositionUnreachable(position.first)) {
            if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
                advanceSearchPhaseAndRefresh()
            } else {
                listSwipeCount++
                swipeListReliable(searchSwipeCooldownMs())
            }
            return
        }

        if (forceAdvanceOnList) {
'''
)

replace_once(
'''            parkedAtStart = false
            listProgressGeneration++
            tapOcrButton(frame, target, RACE_TARGET_TAP_COOLDOWN_MS)
''',
'''            parkedAtStart = false
            detailSourceListPosition = position?.first ?: lastListPosition
            listProgressGeneration++
            tapOcrButton(frame, target, RACE_TARGET_TAP_COOLDOWN_MS)
'''
)

replace_once(
'''            etaInspectionCurrentPosition = position?.first ?: (etaInspectionCount + 1)
            etaInspectionCurrentWasLast = reachedEnd
            etaInspectionCount++
            listProgressGeneration++
''',
'''            etaInspectionCurrentPosition = position?.first ?: (etaInspectionCount + 1)
            detailSourceListPosition = etaInspectionCurrentPosition
            etaInspectionCurrentWasLast = reachedEnd
            etaInspectionCount++
            listProgressGeneration++
'''
)

replace_once(
'''        targetPositioning = false
        targetDetailOpenPending = true
        targetMapOpenAttempts = 0
        listProgressGeneration++
''',
'''        targetPositioning = false
        detailSourceListPosition = predictedSourcePosition
        targetDetailOpenPending = true
        targetMapOpenAttempts = 0
        listProgressGeneration++
'''
)

replace_once(
'''    private fun handleEtaInspectionDetail(normalized: String) {
        val finishEtaMs = MushroomTiming.parseFinishEtaMillis(normalized)
''',
'''    private fun handleEtaInspectionDetail(normalized: String) {
        if (MushroomReachability.isOutOfRangeText(normalized)) {
            skipEtaOutOfRange()
            return
        }
        val finishEtaMs = MushroomTiming.parseFinishEtaMillis(normalized)
'''
)

replace_once(
'''        val best = candidates
            .filterNot { isMapPointRejected(it.key) }
            .maxByOrNull { it.score }
''',
'''        val best = candidates
            .filter { isWithinLocalBirdSearchArea(it.x, it.y, frame.bitmap) }
            .filterNot { isMapPointRejected(it.key) }
            .maxByOrNull { it.score }
'''
)

replace_once(
'''    private fun handleTargetLockedBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
''',
'''    private fun isWithinLocalBirdSearchArea(x: Float, y: Float, bitmap: Bitmap): Boolean {
        val centerX = bitmap.width * TARGET_MAP_CENTER_X
        val centerY = bitmap.height * TARGET_MAP_CENTER_Y
        val dx = (x - centerX) / (bitmap.width * LOCAL_BIRD_SEARCH_X_RADIUS)
        val dy = (y - centerY) / (bitmap.height * LOCAL_BIRD_SEARCH_Y_RADIUS)
        return dx * dx + dy * dy <= 1f
    }

    private fun handleTargetLockedBirdMap(frame: OcrFrame, lines: List<Text.Line>) {
'''
)

replace_once(
'''            urgentListChange = false
            etaInspectionActive = false
''',
'''            urgentListChange = false
            unreachableListPositions.clear()
            etaInspectionActive = false
'''
)

replace_once(
'''    private fun updateDailyRemaining(value: String) {
''',
'''    private fun isOutOfRangeDetail(entries: List<NodeEntry>, normalized: String): Boolean {
        if (MushroomReachability.isOutOfRangeText(normalized)) return true
        val join = findNode(entries, "參加") ?: return false
        return !join.node.isEnabled
    }

    private fun markCurrentTargetUnreachable() {
        val position = detailSourceListPosition
        if (position > 0) {
            unreachableListPositions[position] =
                SystemClock.elapsedRealtime() + OUT_OF_RANGE_POSITION_CACHE_MS
        }
    }

    private fun isListPositionUnreachable(position: Int): Boolean {
        val until = unreachableListPositions[position] ?: return false
        if (SystemClock.elapsedRealtime() >= until) {
            unreachableListPositions.remove(position)
            return false
        }
        return true
    }

    private fun skipEtaOutOfRange() {
        val position = etaInspectionCurrentPosition.takeIf { it > 0 } ?: detailSourceListPosition
        if (position > 0) {
            unreachableListPositions[position] =
                SystemClock.elapsedRealtime() + OUT_OF_RANGE_POSITION_CACHE_MS
        }
        etaInspectionAdvancePending = true
        listContextActive = false
        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)
    }

    private fun rejectTargetOutOfRange() {
        markCurrentTargetUnreachable()
        clearPrediction()
        targetDetailOpenPending = false
        targetMapLockPending = false
        targetMapLockActive = false
        forceAdvanceOnList = true
        goBack(220L)
    }

    private fun updateDailyRemaining(value: String) {
'''
)

replace_once(
'''        clearPrediction()
        detailCameFromBirdMap = false
''',
'''        clearPrediction()
        unreachableListPositions.clear()
        detailSourceListPosition = -1
        detailCameFromBirdMap = false
'''
)

replace_once(
'''            clearPrediction()
            nextActionAt = 0L
''',
'''            clearPrediction()
            unreachableListPositions.clear()
            detailSourceListPosition = -1
            nextActionAt = 0L
'''
)

replace_once(
'''        private const val MAP_REJECT_MS = 30_000L
        private const val MIN_MUSHROOM_PATCH_SCORE = 32
''',
'''        private const val MAP_REJECT_MS = 30_000L
        private const val OUT_OF_RANGE_POSITION_CACHE_MS = 5L * 60L * 1000L
        private const val MIN_MUSHROOM_PATCH_SCORE = 32
'''
)

replace_once(
'''        private const val TARGET_MAP_CENTER_X = 0.50f
        private const val TARGET_MAP_CENTER_Y = 0.46f
        private const val TARGET_MAP_ANCHOR_RADIUS_NORM_SQ = 0.040f
''',
'''        private const val TARGET_MAP_CENTER_X = 0.50f
        private const val TARGET_MAP_CENTER_Y = 0.46f
        private const val LOCAL_BIRD_SEARCH_X_RADIUS = 0.24f
        private const val LOCAL_BIRD_SEARCH_Y_RADIUS = 0.28f
        private const val TARGET_MAP_ANCHOR_RADIUS_NORM_SQ = 0.040f
'''
)

p.write_text(s, encoding="utf-8")
print("Applied V0.4.5 reachability and local-search hardening")
