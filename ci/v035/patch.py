from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"patch pattern expected once, found {count}: {old[:120]!r}")
    s = s.replace(old, new, 1)


# Track an explicit rewind pass so an empty mushroom list stays in Explore
# instead of backing out and getting stranded on another game screen.
replace_once(
    '''    @Volatile private var stuckListPositionCount = 0
    @Volatile private var forceAdvanceOnList = false
''',
    '''    @Volatile private var stuckListPositionCount = 0
    @Volatile private var rewindListPending = false
    @Volatile private var rewindSwipeCount = 0
    @Volatile private var forceAdvanceOnList = false
'''
)

# If a previous bad tap ever opens the decor collection, escape immediately.
# More importantly, handle the Explore mushroom list before bird-map heuristics.
replace_once(
    '''        if (isMushroomList(normalized)) {
            handleMushroomList(frame, lines, normalized)
            return
        }
        if (looksLikeBirdMap(frame.bitmap, lines, normalized)) {
            handleBirdMap(frame, lines)
''',
    '''        if (isMushroomList(normalized)) {
            handleMushroomList(frame, lines, normalized)
            return
        }
        if (normalized.contains("飾品一覽") && !normalized.contains("花苗和水果")) {
            goBack(220)
            return
        }
        if (looksLikeBirdMap(frame.bitmap, lines, normalized)) {
            handleBirdMap(frame, lines)
'''
)

# Replace the list runner with a closed loop: scan forward, rewind to card 1,
# idle briefly, then scan again. Normal empty-list operation never leaves Explore.
old_list = '''    private fun handleMushroomList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
        refreshPending = false
        autoTapAttempts = 0
        joinTapAttempts = 0
        val position = extractListPosition(normalized)
        val reachedEnd = updateListPositionAndCheckEnd(position)
        if (forceAdvanceOnList) {
            forceAdvanceOnList = false
            if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
                advanceSearchPhaseAndRefresh()
            } else {
                listSwipeCount++
                swipeListReliable(240)
            }
            return
        }
        val target = chooseOcrTarget(lines)
        if (target != null) {
            val participantCount = estimateListParticipantCount(frame.bitmap, target)
            if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
                if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
                    advanceSearchPhaseAndRefresh()
                } else {
                    listSwipeCount++
                    swipeListReliable(240)
                }
                return
            }
            tapOcrButton(frame, target, 220)
            return
        }
        if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
            advanceSearchPhaseAndRefresh()
        } else {
            listSwipeCount++
            swipeListReliable(240)
        }
    }
'''
new_list = '''    private fun handleMushroomList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
        refreshPending = false
        autoTapAttempts = 0
        joinTapAttempts = 0
        val position = extractListPosition(normalized)
        val reachedEnd = updateListPositionAndCheckEnd(position)

        if (rewindListPending) {
            val atStart = position?.first == 1
            if (atStart || rewindSwipeCount >= MAX_REWIND_SWIPES) {
                rewindListPending = false
                rewindSwipeCount = 0
                listSwipeCount = 0
                lastListPosition = -1
                stuckListPositionCount = 0
                nextActionAt = SystemClock.elapsedRealtime() + listCycleIdleMs()
            } else {
                rewindSwipeCount++
                swipeListBackwardReliable(220)
            }
            return
        }

        if (forceAdvanceOnList) {
            forceAdvanceOnList = false
            if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
                advanceSearchPhaseAndRefresh()
            } else {
                listSwipeCount++
                swipeListReliable(240)
            }
            return
        }
        val target = chooseOcrTarget(lines)
        if (target != null) {
            val participantCount = estimateListParticipantCount(frame.bitmap, target)
            if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {
                if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
                    advanceSearchPhaseAndRefresh()
                } else {
                    listSwipeCount++
                    swipeListReliable(240)
                }
                return
            }
            tapOcrButton(frame, target, 220)
            return
        }
        if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
            advanceSearchPhaseAndRefresh()
        } else {
            listSwipeCount++
            swipeListReliable(240)
        }
    }
'''
replace_once(old_list, new_list)

# Bird-map mode must never engage on the Explore list. Require strong evidence
# (explicit bird-view label or at least two participant badges) before map taps.
old_bird = '''    private fun looksLikeBirdMap(
        bitmap: Bitmap,
        lines: List<Text.Line>,
        normalized: String
    ): Boolean {
        if (normalized.contains("鳥瞰風景")) return true
        if (
            normalized.contains("今天還剩下") ||
            normalized.contains("選擇派出皮克敏") ||
            normalized.contains("參加")
        ) return false
        val badges = findMapBadges(bitmap, lines)
        if (badges.size >= 2) return true
        return badges.isNotEmpty() && estimateMapGreenRatio(bitmap) >= 0.28f
    }
'''
new_bird = '''    private fun looksLikeBirdMap(
        bitmap: Bitmap,
        lines: List<Text.Line>,
        normalized: String
    ): Boolean {
        if (
            isMushroomList(normalized) ||
            normalized.contains("飾品一覽") ||
            normalized.contains("花苗和水果") ||
            normalized.contains("選擇派出皮克敏") ||
            normalized.contains("參加")
        ) return false
        if (normalized.contains("鳥瞰風景")) return true
        val badges = findMapBadges(bitmap, lines)
        return badges.size >= 2
    }
'''
replace_once(old_bird, new_bird)

# Only run mushroom-color fallback when the screen is convincingly bird-map.
replace_once(
    '''        candidates.addAll(findUnbadgedMushroomCandidates(frame.bitmap, badges))
''',
    '''        val explicitBirdView = lines.any { clean(it.text).contains("鳥瞰風景") }
        if (explicitBirdView || badges.size >= 2) {
            candidates.addAll(findUnbadgedMushroomCandidates(frame.bitmap, badges))
        }
'''
)

# Keep image-only map candidates away from the lower control / decor-button area.
replace_once(
    '''        val maxY = (bitmap.height * 0.84f).toInt()
''',
    '''        val maxY = (bitmap.height * 0.80f).toInt()
'''
)

# A completed scan now rewinds inside Explore instead of pressing Back.
old_advance = '''    private fun advanceSearchPhaseAndRefresh() {
        phase = when {
            isWeekend() && phase == SearchPhase.GIANT -> SearchPhase.EVENT
            isWeekend() -> SearchPhase.GIANT
            phase == SearchPhase.EVENT -> SearchPhase.ANY
            else -> SearchPhase.EVENT
        }
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        forceAdvanceOnList = false
        refreshPending = true
        reopenExploreAt = SystemClock.elapsedRealtime() + refreshDelayMs()
        goBack(220)
    }
'''
new_advance = '''    private fun advanceSearchPhaseAndRefresh() {
        phase = when {
            isWeekend() && phase == SearchPhase.GIANT -> SearchPhase.EVENT
            isWeekend() -> SearchPhase.GIANT
            phase == SearchPhase.EVENT -> SearchPhase.ANY
            else -> SearchPhase.EVENT
        }
        listSwipeCount = 0
        lastListPosition = -1
        stuckListPositionCount = 0
        forceAdvanceOnList = false
        refreshPending = false
        rewindListPending = true
        rewindSwipeCount = 1
        swipeListBackwardReliable(220)
    }
'''
replace_once(old_advance, new_advance)

# The Explore screen is recognizable even when OCR misses the daily-count line.
replace_once(
    '''    private fun isMushroomList(normalized: String): Boolean =
        normalized.contains("今天還剩下") && normalized.contains("蘑菇")
''',
    '''    private fun isMushroomList(normalized: String): Boolean {
        val s = clean(normalized)
        val dailyHeader = s.contains("今天還剩下") && s.contains("蘑菇")
        val exploreBody = s.contains("花苗和水果") &&
            (s.contains("飾品一覽") || s.contains("蘑菇"))
        return dailyHeader || exploreBody
    }
'''
)

# Idle briefly after a full pass, then keep monitoring in the same list.
replace_once(
    '''    private fun swipeListReliable(cooldownMs: Long) {
''',
    '''    private fun listCycleIdleMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 5_000L
        RunMode.WATCH -> 1_800L
        RunMode.RACE -> 650L
    }
    private fun swipeListReliable(cooldownMs: Long) {
'''
)

# Add a reverse horizontal list gesture for rewind-to-start.
needle = '''    private fun findListScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
'''
rewind_fn = '''    private fun swipeListBackwardReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            val root = rootInActiveWindow
            val scrollable = root?.let { findListScrollable(it) }
            if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
                return@post
            }
            val dm = resources.displayMetrics
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.17f, dm.heightPixels * 0.72f)
                lineTo(dm.widthPixels * 0.82f, dm.heightPixels * 0.72f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 230))
                .build()
            dispatchGesture(gesture, null, null)
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
        }
    }
'''
replace_once(needle, rewind_fn + needle)

# Clear rewind state when daily state rolls over or after a successful submission.
replace_once(
    '''            stuckListPositionCount = 0
            nextActionAt = 0L
''',
    '''            stuckListPositionCount = 0
            rewindListPending = false
            rewindSwipeCount = 0
            nextActionAt = 0L
'''
)
replace_once(
    '''        phase = firstPhase()
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
''',
    '''        phase = firstPhase()
        rewindListPending = false
        rewindSwipeCount = 0
        detailCameFromBirdMap = false
        lastBirdMapTapKey = null
'''
)

# Bound rewind work; no infinite back-and-forth gestures.
replace_once(
    '''        private const val MAX_STUCK_LIST_POSITION = 2
''',
    '''        private const val MAX_STUCK_LIST_POSITION = 2
        private const val MAX_REWIND_SWIPES = 18
'''
)

p.write_text(s, encoding="utf-8")
print("Applied Mushroom Helper V0.3.5 patch")
