from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"patch pattern expected once, found {count}: {old[:120]!r}")
    s = s.replace(old, new, 1)


# A scheduled fresh scan must not be suppressed just because the screenshot
# fingerprint is unchanged. This is the watchdog that guarantees another pass.
replace_once(
    '''    private val eventKick = Runnable { scanOnce() }
''',
    '''    private val eventKick = Runnable { scanOnce() }
    private val listWatchdogKick = Runnable {
        lastFrameFingerprint = Long.MIN_VALUE
        lastProcessedFrameAt = 0L
        lastOcrAt = 0L
        scanOnce()
    }
'''
)

# When a rewind reaches the first card, explicitly schedule a fresh OCR scan.
# Merely setting nextActionAt was not enough on some Unity frames because the
# unchanged-frame throttle could suppress the next list pass.
replace_once(
    '''                rewindListPending = false
                rewindSwipeCount = 0
                listSwipeCount = 0
                lastListPosition = -1
                stuckListPositionCount = 0
                nextActionAt = SystemClock.elapsedRealtime() + listCycleIdleMs()
''',
    '''                rewindListPending = false
                rewindSwipeCount = 0
                listSwipeCount = 0
                lastListPosition = -1
                stuckListPositionCount = 0
                scheduleFreshListScan(listCycleIdleMs())
'''
)

# If OCR cannot read the list position, rewind is still bounded. When it reaches
# the bound, the same explicit watchdog starts a brand-new pass.
replace_once(
    '''    private fun listCycleIdleMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 5_000L
        RunMode.WATCH -> 1_800L
        RunMode.RACE -> 650L
    }
''',
    '''    private fun listCycleIdleMs(): Long = when (prefs.mode) {
        RunMode.ECO -> 15_000L
        RunMode.WATCH -> 4_000L
        RunMode.RACE -> 1_200L
    }
    private fun scheduleFreshListScan(delayMs: Long) {
        if (!prefs.enabled) return
        val whenAt = SystemClock.elapsedRealtime() + delayMs
        nextActionAt = whenAt
        main.removeCallbacks(listWatchdogKick)
        main.postDelayed(listWatchdogKick, delayMs + 20L)
    }
'''
)

# Use direct gestures inside the known mushroom-card strip. Unity's exposed
# scrollable node is inconsistent and sometimes points at the parent Explore
# page, which is why V0.3.5 could stop instead of moving the carousel.
old_forward = '''    private fun swipeListReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            val root = rootInActiveWindow
            val scrollable = root?.let { findListScrollable(it) }
            if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
                return@post
            }
            val dm = resources.displayMetrics
            val yRatio = if (listSwipeCount % 2 == 0) 0.70f else 0.74f
            val path = Path().apply {
                moveTo(dm.widthPixels * 0.82f, dm.heightPixels * yRatio)
                lineTo(dm.widthPixels * 0.17f, dm.heightPixels * yRatio)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 230))
                .build()
            dispatchGesture(gesture, null, null)
            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
        }
    }
'''
new_forward = '''    private fun swipeListReliable(cooldownMs: Long) {
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
'''
replace_once(old_forward, new_forward)

old_backward = '''    private fun swipeListBackwardReliable(cooldownMs: Long) {
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
new_backward = '''    private fun swipeListBackwardReliable(cooldownMs: Long) {
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
'''
replace_once(old_backward, new_backward)

# Never let an old watchdog survive service shutdown.
replace_once(
    '''    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
''',
    '''    override fun onDestroy() {
        main.removeCallbacks(listWatchdogKick)
        main.removeCallbacksAndMessages(null)
'''
)

p.write_text(s, encoding="utf-8")
print("Applied Mushroom Helper V0.3.6 deterministic list-loop patch")
