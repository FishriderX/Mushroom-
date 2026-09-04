from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"patch pattern expected once, found {count}: {old[:140]!r}")
    s = s.replace(old, new, 1)


# Keep an explicit progress generation. A fresh-list watchdog can then tell
# whether OCR actually caused a tap/swipe instead of silently doing nothing.
replace_once(
    '''    @Volatile private var rewindSwipeCount = 0
    @Volatile private var forceAdvanceOnList = false
''',
    '''    @Volatile private var rewindSwipeCount = 0
    @Volatile private var listProgressGeneration = 0L
    @Volatile private var listContextActive = false
    @Volatile private var forceAdvanceOnList = false
'''
)

# V0.3.6 could still lose the cycle when the scheduled kick arrived while the
# previous OCR request was busy. Retry instead of dropping the kick, and add a
# progress guard after scanOnce(). If OCR finishes without moving/tapping, the
# guard schedules another deterministic kick.
replace_once(
    '''    private val listWatchdogKick = Runnable {
        lastFrameFingerprint = Long.MIN_VALUE
        lastProcessedFrameAt = 0L
        lastOcrAt = 0L
        scanOnce()
    }
''',
    '''    private val listWatchdogKick = object : Runnable {
        override fun run() {
            if (!::prefs.isInitialized || !prefs.enabled) return
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != PIKMIN_PACKAGE) return
            if (!listContextActive) return

            if (ocrBusy.get()) {
                main.postDelayed(this, LIST_BUSY_RETRY_MS)
                return
            }

            nextActionAt = 0L
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            val generationBefore = listProgressGeneration
            scanOnce()

            main.postDelayed({
                if (!::prefs.isInitialized || !prefs.enabled) return@postDelayed
                if (!listContextActive) return@postDelayed
                if (generationBefore != listProgressGeneration) return@postDelayed
                val currentRoot = rootInActiveWindow ?: return@postDelayed
                if (currentRoot.packageName?.toString() != PIKMIN_PACKAGE) return@postDelayed

                if (ocrBusy.get()) {
                    main.removeCallbacks(listWatchdogKick)
                    main.postDelayed(listWatchdogKick, LIST_BUSY_RETRY_MS)
                    return@postDelayed
                }

                // No tap/swipe happened after a forced list scan. Wake the
                // screenshot/OCR path again instead of leaving card 1 idle.
                nextActionAt = 0L
                lastFrameFingerprint = Long.MIN_VALUE
                lastProcessedFrameAt = 0L
                lastOcrAt = 0L
                main.removeCallbacks(listWatchdogKick)
                main.postDelayed(listWatchdogKick, LIST_STALL_RETRY_MS)
            }, LIST_PROGRESS_GUARD_MS)
        }
    }
'''
)

# Mark when OCR is positively on the mushroom list. Other major screens clear
# the flag so the watchdog never swipes around unrelated Pikmin screens.
replace_once(
    '''    private fun handleMushroomList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        detailCameFromBirdMap = false
''',
    '''    private fun handleMushroomList(
        frame: OcrFrame,
        lines: List<Text.Line>,
        normalized: String
    ) {
        listContextActive = true
        detailCameFromBirdMap = false
'''
)

replace_once(
    '''        if (normalized.contains("選擇派出皮克敏")) {
            handleTeamSelectionOcr(frame, lines, normalized)
            return
        }
        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            handleDetailOcr(frame, lines, normalized)
            return
        }
''',
    '''        if (normalized.contains("選擇派出皮克敏")) {
            listContextActive = false
            handleTeamSelectionOcr(frame, lines, normalized)
            return
        }
        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            listContextActive = false
            handleDetailOcr(frame, lines, normalized)
            return
        }
'''
)

replace_once(
    '''        if (looksLikeBirdMap(frame.bitmap, lines, normalized)) {
            handleBirdMap(frame, lines)
            return
        }
''',
    '''        if (looksLikeBirdMap(frame.bitmap, lines, normalized)) {
            listContextActive = false
            handleBirdMap(frame, lines)
            return
        }
'''
)

# A detected mushroom target is genuine progress. This also cancels the stall
# guard without needing to cancel every delayed lambda individually.
replace_once(
    '''            tapOcrButton(frame, target, 220)
            return
''',
    '''            listProgressGeneration++
            tapOcrButton(frame, target, 220)
            return
'''
)

# Every carousel gesture is progress. V0.3.7 keeps the direct Unity-safe
# gestures from V0.3.6 but makes them observable by the watchdog.
replace_once(
    '''    private fun swipeListReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            val dm = resources.displayMetrics
            val y = dm.heightPixels * 0.705f
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.86f, y)
                lineTo(dm.widthPixels * 0.14f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 170))
                .build()
            dispatchGesture(gesture, null, null)
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
            main.removeCallbacks(listWatchdogKick)
            main.postDelayed(listWatchdogKick, cooldownMs + 80L)
        }
    }
''',
    '''    private fun swipeListReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            listContextActive = true
            listProgressGeneration++
            val dm = resources.displayMetrics
            val y = dm.heightPixels * 0.705f
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.86f, y)
                lineTo(dm.widthPixels * 0.14f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 170))
                .build()
            dispatchGesture(gesture, null, null)
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
            main.removeCallbacks(listWatchdogKick)
            main.postDelayed(listWatchdogKick, cooldownMs + 80L)
        }
    }
'''
)

replace_once(
    '''    private fun swipeListBackwardReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            val dm = resources.displayMetrics
            val y = dm.heightPixels * 0.705f
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.14f, y)
                lineTo(dm.widthPixels * 0.86f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 170))
                .build()
            dispatchGesture(gesture, null, null)
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
            main.removeCallbacks(listWatchdogKick)
            main.postDelayed(listWatchdogKick, cooldownMs + 80L)
        }
    }
''',
    '''    private fun swipeListBackwardReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            listContextActive = true
            listProgressGeneration++
            val dm = resources.displayMetrics
            val y = dm.heightPixels * 0.705f
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.14f, y)
                lineTo(dm.widthPixels * 0.86f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 170))
                .build()
            dispatchGesture(gesture, null, null)
            lastFrameFingerprint = Long.MIN_VALUE
            lastProcessedFrameAt = 0L
            lastOcrAt = 0L
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
            main.removeCallbacks(listWatchdogKick)
            main.postDelayed(listWatchdogKick, cooldownMs + 80L)
        }
    }
'''
)

# A fresh cycle always arms the list context before the delayed watchdog.
replace_once(
    '''    private fun scheduleFreshListScan(delayMs: Long) {
        if (!prefs.enabled) return
        val whenAt = SystemClock.elapsedRealtime() + delayMs
        nextActionAt = whenAt
        main.removeCallbacks(listWatchdogKick)
        main.postDelayed(listWatchdogKick, delayMs + 20L)
    }
''',
    '''    private fun scheduleFreshListScan(delayMs: Long) {
        if (!prefs.enabled) return
        listContextActive = true
        val whenAt = SystemClock.elapsedRealtime() + delayMs
        nextActionAt = whenAt
        main.removeCallbacks(listWatchdogKick)
        main.postDelayed(listWatchdogKick, delayMs + 20L)
    }
'''
)

# Reset list state cleanly at service shutdown.
replace_once(
    '''    override fun onDestroy() {
        main.removeCallbacks(listWatchdogKick)
''',
    '''    override fun onDestroy() {
        listContextActive = false
        main.removeCallbacks(listWatchdogKick)
'''
)

# Retry timings are deliberately lightweight: no busy-looping and no extra OCR
# threads. They only run while the mushroom list is known to be active.
replace_once(
    '''        private const val MIN_MUSHROOM_PATCH_SCORE = 32
''',
    '''        private const val MIN_MUSHROOM_PATCH_SCORE = 32
        private const val LIST_BUSY_RETRY_MS = 140L
        private const val LIST_PROGRESS_GUARD_MS = 900L
        private const val LIST_STALL_RETRY_MS = 180L
'''
)

p.write_text(s, encoding="utf-8")
print("Applied Mushroom Helper V0.3.7 robust list-watchdog patch")
