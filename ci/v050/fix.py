from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, got {count}: {old[:220]!r}")
    s = s.replace(old, new, 1)

replace_once(
'''    @Volatile private var targetMapAnchorY = 0f
    @Volatile private var rewindResume: RewindResume = RewindResume.SEARCH
''',
'''    @Volatile private var targetMapAnchorY = 0f
    @Volatile private var targetMapEnterDeadline = 0L
    @Volatile private var listTargetStandby = false
    @Volatile private var listTargetTapX = 0f
    @Volatile private var listTargetTapY = 0f
    @Volatile private var lastBlindTargetTapAt = 0L
    @Volatile private var blindTargetTapAttempts = 0
    @Volatile private var raceTapVerificationUntil = 0L
    @Volatile private var rewindResume: RewindResume = RewindResume.SEARCH
'''
)

replace_once(
'''    private enum class RewindResume { SEARCH, INSPECT, PARK, TARGET }
''',
'''    private enum class RewindResume { SEARCH, INSPECT, PARK, TARGET, STANDBY }
'''
)

replace_once(
'''                if (prefs.mode == RunMode.RACE) {
                    burstUntil = now + EVENT_BURST_MS
                    val isMutation =
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
''',
'''                if (prefs.mode == RunMode.RACE) {
                    burstUntil = now + EVENT_BURST_MS
                    val isMutation =
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    if (
                        listTargetStandby &&
                        listContextActive &&
                        isMutation &&
                        now >= suppressListMutationEventsUntil &&
                        now >= predictedSpawnAt - DIRECT_TAP_LEAD_MS &&
                        now <= predictionWindowUntil &&
                        now - lastBlindTargetTapAt >= DIRECT_TARGET_TAP_INTERVAL_MS
                    ) {
                        tryBlindListTargetTap(now)
                    }
'''
)

replace_once(
'''        // Bird-view RACE never waits for whole-screen Chinese OCR. Android
        // limits Accessibility screenshots to roughly one every 333 ms, so
        // this path polls at a safe 350 ms cadence and performs only local
        // pixel analysis around the locked mushroom location.
        if (targetMapLockActive && isPredictionPrewarm(now)) {
            requestTargetMapFastFrame()
            return
        }

        if (now < nextActionAt) return
        if (handleAccessibilityTree(root)) return
''',
'''        // During a verified target standby, critical taps no longer wait for
        // OCR/image recognition. First give the accessibility tree a chance to
        // notice that a previous tap already opened detail/team selection.
        if (targetMapLockActive && isPredictionPrewarm(now)) {
            if (handleAccessibilityTree(root)) return
            if (now < raceTapVerificationUntil) {
                requestOcrFallback()
                return
            }
            if (now >= predictedSpawnAt - DIRECT_TAP_LEAD_MS && tryBlindMapTargetTap(now)) return
            requestTargetMapFastFrame()
            return
        }
        if (listTargetStandby && isPredictionPrewarm(now)) {
            if (handleAccessibilityTree(root)) return
            if (now < raceTapVerificationUntil) {
                requestOcrFallback()
                return
            }
            if (now >= predictedSpawnAt - DIRECT_TAP_LEAD_MS && tryBlindListTargetTap(now)) return
        }

        if (targetMapLockPending && targetMapEnterDeadline > 0L && now > targetMapEnterDeadline) {
            fallbackToListTargetStandby()
            return
        }

        if (now < nextActionAt) return
        if (handleAccessibilityTree(root)) return
'''
)

replace_once(
'''        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            if (isOutOfRangeDetail(entries, normalized)) {
                rejectTargetOutOfRange()
                return true
            }
            findGoToMapNode(entries)?.let {
                targetDetailOpenPending = false
                targetMapOpenAttempts = 0
                targetMapLockPending = true
                clickNode(it.node, TARGET_MAP_OPEN_COOLDOWN_MS)
                return true
            }
            return false
        }
''',
'''        if (targetDetailOpenPending && looksLikeMushroomDetail(normalized)) {
            if (isOutOfRangeDetail(entries, normalized)) {
                rejectTargetOutOfRange()
                return true
            }
            findGoToMapNode(entries)?.let {
                targetDetailOpenPending = false
                targetMapOpenAttempts = 0
                targetMapLockPending = true
                targetMapEnterDeadline = SystemClock.elapsedRealtime() + TARGET_MAP_ENTER_TIMEOUT_MS
                clickNode(it.node, TARGET_MAP_OPEN_COOLDOWN_MS)
                return true
            }
            return false
        }
        if (targetMapLockPending && normalized.contains("鳥瞰風景")) {
            activateTargetMapLock()
            return false
        }
'''
)

replace_once(
'''        joinTapAttempts++
        if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
            startBackOutToNextTarget(1)
            return true
        }
''',
'''        joinTapAttempts++
        if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
            markCurrentTargetUnreachable()
            startBackOutToNextTarget(1)
            return true
        }
'''
)

# replace OCR join attempt occurrence too
idx = s.find('''        joinTapAttempts++
        if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
            startBackOutToNextTarget(1)
            return
        }
''')
if idx < 0:
    raise SystemExit("missing OCR join-attempt block")
s = s[:idx] + '''        joinTapAttempts++
        if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
            markCurrentTargetUnreachable()
            startBackOutToNextTarget(1)
            return
        }
''' + s[idx + len('''        joinTapAttempts++
        if (joinTapAttempts > MAX_JOIN_TAP_ATTEMPTS) {
            startBackOutToNextTarget(1)
            return
        }
'''):]

replace_once(
'''        val forcedTargetBirdMap =
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
                fastMapBaselineReady = false
                fastMapBaselineKeys = emptySet()
                lastFastMapScreenshotAt = 0L
                targetMapOpenAttempts = 0
            }
            handleBirdMap(frame, lines)
            return
        }
''',
'''        val verifiedBirdMap = looksLikeBirdMap(frame.bitmap, lines, normalized)
        if (targetMapLockPending && verifiedBirdMap) {
            listContextActive = false
            activateTargetMapLock()
            handleBirdMap(frame, lines)
            return
        }
        if (verifiedBirdMap) {
            listContextActive = false
            handleBirdMap(frame, lines)
            return
        }
        if (targetMapLockPending && targetMapEnterDeadline > 0L &&
            SystemClock.elapsedRealtime() > targetMapEnterDeadline
        ) {
            fallbackToListTargetStandby()
            return
        }
'''
)

replace_once(
'''        if (targetPositioning) {
            handleTargetPositioningList(frame, lines, reachedEnd, position)
            return
        }
''',
'''        if (targetPositioning) {
            handleTargetPositioningList(frame, lines, reachedEnd, position)
            return
        }
        if (listTargetStandby) {
            handleListTargetStandby(frame, lines, position)
            return
        }
'''
)

replace_once(
'''        if (prefs.mode == RunMode.RACE && urgentListChange) {
''',
'''        if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {
'''
)

replace_once(
'''                RewindResume.TARGET -> {
                    parkedAtStart = false
                    targetPositioning = true
                    targetAdvanceRemaining = (predictedSourcePosition - 1).coerceAtLeast(0)
                    scheduleFreshListScan(140L)
                }
''',
'''                RewindResume.TARGET -> {
                    parkedAtStart = false
                    listTargetStandby = false
                    targetPositioning = true
                    targetAdvanceRemaining = (predictedSourcePosition - 1).coerceAtLeast(0)
                    scheduleFreshListScan(140L)
                }
                RewindResume.STANDBY -> {
                    parkedAtStart = false
                    listTargetStandby = true
                    targetPositioning = true
                    targetAdvanceRemaining = (predictedSourcePosition - 1).coerceAtLeast(0)
                    scheduleFreshListScan(140L)
                }
'''
)

replace_once(
'''        targetPositioning = false
        detailSourceListPosition = predictedSourcePosition
        targetDetailOpenPending = true
        targetMapOpenAttempts = 0
        listProgressGeneration++
        tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)
''',
'''        targetPositioning = false
        detailSourceListPosition = predictedSourcePosition
        if (listTargetStandby) {
            val box = title.boundingBox
            if (box != null) {
                listTargetTapX = box.exactCenterX() * frame.scaleX
                listTargetTapY = box.exactCenterY() * frame.scaleY
            } else {
                listTargetTapX = resources.displayMetrics.widthPixels * 0.50f
                listTargetTapY = resources.displayMetrics.heightPixels * 0.62f
            }
            blindTargetTapAttempts = 0
            lastBlindTargetTapAt = 0L
            scheduleFreshListScan(
                (predictionReadyAt - SystemClock.elapsedRealtime()).coerceAtLeast(180L)
            )
            return
        }
        targetDetailOpenPending = true
        targetMapOpenAttempts = 0
        listProgressGeneration++
        tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)
'''
)

replace_once(
'''        if (goToMap != null) {
            targetDetailOpenPending = false
            targetMapOpenAttempts = 0
            targetMapLockPending = true
            tapOcrButton(frame, goToMap, TARGET_MAP_OPEN_COOLDOWN_MS)
            return
        }
''',
'''        if (goToMap != null) {
            targetDetailOpenPending = false
            targetMapOpenAttempts = 0
            targetMapLockPending = true
            targetMapEnterDeadline = SystemClock.elapsedRealtime() + TARGET_MAP_ENTER_TIMEOUT_MS
            tapOcrButton(frame, goToMap, TARGET_MAP_OPEN_COOLDOWN_MS)
            return
        }
'''
)

replace_once(
'''        // Safe failure: return to list and keep the prediction rather than
        // tapping an unverified coordinate on the detail page.
        targetDetailOpenPending = false
        targetMapOpenAttempts = 0
        goBack(220L)
''',
'''        // Bird-view navigation is optional. If Pikmin does not expose a
        // reliable way to open the remote bird view, fall back to parking on
        // the known target card instead of wandering or stalling.
        targetDetailOpenPending = false
        targetMapOpenAttempts = 0
        fallbackToListTargetStandby(fromDetail = true)
'''
)

replace_once(
'''    private fun findGoToMapLine(lines: List<Text.Line>): Text.Line? =
''',
'''    private fun activateTargetMapLock() {
        targetMapLockPending = false
        targetMapLockActive = true
        targetMapEnterDeadline = 0L
        targetMapAnchorReady = true
        targetMapAnchorX = resources.displayMetrics.widthPixels * TARGET_MAP_CENTER_X
        targetMapAnchorY = resources.displayMetrics.heightPixels * TARGET_MAP_CENTER_Y
        fastMapBaselineReady = false
        fastMapBaselineKeys = emptySet()
        lastFastMapScreenshotAt = 0L
        blindTargetTapAttempts = 0
        lastBlindTargetTapAt = 0L
        raceTapVerificationUntil = 0L
        listTargetStandby = false
        targetMapOpenAttempts = 0
    }

    private fun fallbackToListTargetStandby(fromDetail: Boolean = false) {
        targetMapLockPending = false
        targetMapLockActive = false
        targetMapEnterDeadline = 0L
        targetDetailOpenPending = false
        targetMapOpenAttempts = 0
        fastMapBaselineReady = false
        fastMapBaselineKeys = emptySet()
        listTargetStandby = true
        blindTargetTapAttempts = 0
        lastBlindTargetTapAt = 0L
        raceTapVerificationUntil = 0L
        if (fromDetail) {
            goBack(220L)
            main.postDelayed({
                if (prefs.enabled && predictedSpawnAt > 0L) beginRewind(RewindResume.STANDBY)
            }, 300L)
        } else {
            beginRewind(RewindResume.STANDBY)
        }
    }

    private fun handleListTargetStandby(
        frame: OcrFrame,
        lines: List<Text.Line>,
        position: Pair<Int, Int>?
    ) {
        val now = SystemClock.elapsedRealtime()
        if (predictedSpawnAt <= 0L || now > predictionWindowUntil) {
            listTargetStandby = false
            clearPrediction()
            beginRewind(RewindResume.PARK)
            return
        }
        if (position != null && position.first != predictedSourcePosition) {
            listTargetStandby = true
            beginRewind(RewindResume.STANDBY)
            return
        }
        findAnyMushroomTitle(lines)?.boundingBox?.let { box ->
            listTargetTapX = box.exactCenterX() * frame.scaleX
            listTargetTapY = box.exactCenterY() * frame.scaleY
        }
        if (now < predictionReadyAt) {
            scheduleFreshListScan((predictionReadyAt - now).coerceAtLeast(250L))
            return
        }
        burstUntil = max(burstUntil, predictionWindowUntil)
        if (now >= predictedSpawnAt - DIRECT_TAP_LEAD_MS) {
            tryBlindListTargetTap(now)
        }
        scheduleFreshListScan(DIRECT_STANDBY_POLL_MS)
    }

    private fun tryBlindListTargetTap(now: Long): Boolean {
        if (!listTargetStandby || !listContextActive || predictedSpawnAt <= 0L) return false
        if (listTargetTapX <= 0f || listTargetTapY <= 0f) return false
        if (now > predictionWindowUntil) return false
        if (now - lastBlindTargetTapAt < DIRECT_TARGET_TAP_INTERVAL_MS) return false
        if (blindTargetTapAttempts >= MAX_DIRECT_TARGET_TAPS) return false
        lastBlindTargetTapAt = now
        blindTargetTapAttempts++
        raceTapVerificationUntil = now + DIRECT_TAP_VERIFY_MS
        verifiedTap(listTargetTapX, listTargetTapY, DIRECT_TAP_VERIFY_MS)
        return true
    }

    private fun tryBlindMapTargetTap(now: Long): Boolean {
        if (!targetMapLockActive || !targetMapAnchorReady || predictedSpawnAt <= 0L) return false
        if (now > predictionWindowUntil) return false
        if (now - lastBlindTargetTapAt < DIRECT_TARGET_TAP_INTERVAL_MS) return false
        if (blindTargetTapAttempts >= MAX_DIRECT_TARGET_TAPS) return false
        lastBlindTargetTapAt = now
        blindTargetTapAttempts++
        raceTapVerificationUntil = now + DIRECT_TAP_VERIFY_MS
        verifiedTap(targetMapAnchorX, targetMapAnchorY, DIRECT_TAP_VERIFY_MS)
        return true
    }

    private fun findGoToMapLine(lines: List<Text.Line>): Text.Line? =
'''
)

replace_once(
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
        val badges = findMapBadges(frame.bitmap, lines)
        val candidates = ArrayList<MapCandidate>()
        badges.forEach { badge ->
            if (badge.count < FREE_SLOT_LIMIT && !isMapPointRejected(badge.key)) {
                candidates.add(
                    MapCandidate(
                        x = badge.x,
                        y = badge.y,
                        key = badge.key,
                        participantCount = badge.count,
                        score = 10_000 - badge.count * 500
                    )
                )
            }
        }
        val explicitBirdView = lines.any { clean(it.text).contains("鳥瞰風景") }
        if (explicitBirdView || badges.size >= 2) {
            candidates.addAll(findUnbadgedMushroomCandidates(frame.bitmap, badges))
        }
        val best = candidates
            .filter { isWithinLocalBirdSearchArea(it.x, it.y, frame.bitmap) }
            .filterNot { isMapPointRejected(it.key) }
            .maxByOrNull { it.score }
        if (best == null) {
            nextActionAt = SystemClock.elapsedRealtime() + birdIdleRescanMs()
            return
        }
        detailCameFromBirdMap = true
        lastBirdMapTapKey = best.key
        verifiedTap(
            best.x * frame.scaleX,
            best.y * frame.scaleY,
            220
        )
    }
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

        // Never roam an arbitrary bird-view map. Its visible area is not the
        // same thing as Pikmin's joinable mushroom range, and flower graphics
        // can resemble mushrooms. Bird view is race-only after a verified
        // target navigation from a reachable mushroom detail page.
        nextActionAt = SystemClock.elapsedRealtime() + BIRD_UNTARGETED_IDLE_MS
    }
'''
)

# Remove now-unused local general-search helper but keep harmless if referenced nowhere.

replace_once(
'''        targetMapLockPending = false
        targetMapLockActive = false
        targetMapAnchorReady = false
        targetMapAnchorX = 0f
        targetMapAnchorY = 0f
        fastMapBaselineReady = false
''',
'''        targetMapLockPending = false
        targetMapLockActive = false
        targetMapAnchorReady = false
        targetMapAnchorX = 0f
        targetMapAnchorY = 0f
        targetMapEnterDeadline = 0L
        listTargetStandby = false
        listTargetTapX = 0f
        listTargetTapY = 0f
        lastBlindTargetTapAt = 0L
        blindTargetTapAttempts = 0
        raceTapVerificationUntil = 0L
        fastMapBaselineReady = false
'''
)

replace_once(
'''    private fun isOutOfRangeDetail(entries: List<NodeEntry>, normalized: String): Boolean {
        if (MushroomReachability.isOutOfRangeText(normalized)) return true
        val join = findNode(entries, "參加") ?: return false
        return !join.node.isEnabled
    }
''',
'''    private fun isOutOfRangeDetail(entries: List<NodeEntry>, normalized: String): Boolean {
        if (MushroomReachability.isOutOfRangeText(normalized)) return true
        val joins = entries.filter { clean(it.text).contains("參加") }
        for (entry in joins) {
            var node: AccessibilityNodeInfo? = entry.node
            repeat(5) {
                val candidate = node ?: return@repeat
                if (candidate.isClickable) return !candidate.isEnabled
                node = candidate.parent
            }
        }
        // No actionable accessibility node is inconclusive for Unity, not an
        // out-of-range signal. OCR text warnings and failed join attempts are
        // used as the safe fallbacks.
        return false
    }
'''
)

replace_once(
'''        targetMapLockActive = false
        targetMapAnchorReady = false
        main.removeCallbacks(listWatchdogKick)
''',
'''        targetMapLockActive = false
        targetMapAnchorReady = false
        targetMapEnterDeadline = 0L
        listTargetStandby = false
        main.removeCallbacks(listWatchdogKick)
'''
)

replace_once(
'''        private const val TARGET_MAP_OPEN_COOLDOWN_MS = 300L
        private const val TARGET_MAP_BUTTON_RETRY_MS = 180L
        private const val MAX_TARGET_MAP_OPEN_ATTEMPTS = 4
''',
'''        private const val TARGET_MAP_OPEN_COOLDOWN_MS = 300L
        private const val TARGET_MAP_BUTTON_RETRY_MS = 180L
        private const val TARGET_MAP_ENTER_TIMEOUT_MS = 1_600L
        private const val MAX_TARGET_MAP_OPEN_ATTEMPTS = 4
        private const val BIRD_UNTARGETED_IDLE_MS = 1_500L
'''
)

replace_once(
'''        private const val FAST_MAP_Y_RADIUS = 0.14f
        private const val MUSHROOM_RESPAWN_DELAY_MS = 5L * 60L * 1000L
''',
'''        private const val FAST_MAP_Y_RADIUS = 0.14f
        private const val DIRECT_TAP_LEAD_MS = 800L
        private const val DIRECT_TARGET_TAP_INTERVAL_MS = 380L
        private const val DIRECT_TAP_VERIFY_MS = 260L
        private const val DIRECT_STANDBY_POLL_MS = 180L
        private const val MAX_DIRECT_TARGET_TAPS = 8
        private const val MUSHROOM_RESPAWN_DELAY_MS = 5L * 60L * 1000L
'''
)

p.write_text(s, encoding="utf-8")
print("Applied V0.5.0 integrated race state machine")
