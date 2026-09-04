from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, got {count}: {old[:140]!r}")
    s = s.replace(old, new, 1)

# Track whether an injected list gesture is actually in progress. This prevents
# a watchdog tick or loop scan from dispatching another gesture early and
# cancelling the first one on Unity screens.
replace_once(
    '''    @Volatile private var listProgressGeneration = 0L
    @Volatile private var listContextActive = false
    @Volatile private var raceSweepActive = false
''',
    '''    @Volatile private var listProgressGeneration = 0L
    @Volatile private var listContextActive = false
    @Volatile private var listGestureInFlight = false
    @Volatile private var raceSweepActive = false
'''
)

# A list watchdog must never race an injected gesture. Wait for the Android
# GestureResultCallback instead of relying on a guessed timer.
replace_once(
    '''            if (ocrBusy.get()) {
                main.postDelayed(this, LIST_BUSY_RETRY_MS)
                return
            }

            nextActionAt = 0L
''',
    '''            if (listGestureInFlight) {
                main.postDelayed(this, LIST_GESTURE_BUSY_RETRY_MS)
                return
            }
            if (ocrBusy.get()) {
                main.postDelayed(this, LIST_BUSY_RETRY_MS)
                return
            }

            nextActionAt = 0L
'''
)

old_swipes = '''    private fun swipeListReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            listContextActive = true
            listProgressGeneration++
            suppressListMutationEventsUntil = SystemClock.elapsedRealtime() + RACE_SELF_GESTURE_EVENT_SUPPRESS_MS
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
    private fun swipeListBackwardReliable(cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            listContextActive = true
            listProgressGeneration++
            suppressListMutationEventsUntil = SystemClock.elapsedRealtime() + RACE_SELF_GESTURE_EVENT_SUPPRESS_MS
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
new_swipes = '''    private fun swipeListReliable(cooldownMs: Long) {
        dispatchListSwipe(forward = true, cooldownMs = cooldownMs)
    }

    private fun swipeListBackwardReliable(cooldownMs: Long) {
        dispatchListSwipe(forward = false, cooldownMs = cooldownMs)
    }

    private fun dispatchListSwipe(forward: Boolean, cooldownMs: Long) {
        if (!prefs.enabled) return
        main.post {
            if (!prefs.enabled) return@post
            if (listGestureInFlight) {
                main.removeCallbacks(listWatchdogKick)
                main.postDelayed(listWatchdogKick, LIST_GESTURE_BUSY_RETRY_MS)
                return@post
            }

            listContextActive = true
            listProgressGeneration++
            listGestureInFlight = true
            suppressListMutationEventsUntil =
                SystemClock.elapsedRealtime() + RACE_SELF_GESTURE_EVENT_SUPPRESS_MS

            val dm = resources.displayMetrics
            val y = dm.heightPixels * 0.705f
            val fromX = dm.widthPixels * if (forward) 0.86f else 0.14f
            val toX = dm.widthPixels * if (forward) 0.14f else 0.86f
            val path = Path().apply {
                moveTo(fromX, y)
                lineTo(toX, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, LIST_GESTURE_DURATION_MS))
                .build()

            nextActionAt = SystemClock.elapsedRealtime() + cooldownMs
            main.removeCallbacks(listWatchdogKick)

            val accepted = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        listGestureInFlight = false
                        armListAfterGesture(LIST_GESTURE_SETTLE_MS)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        listGestureInFlight = false
                        armListAfterGesture(LIST_GESTURE_CANCEL_RETRY_MS)
                    }
                },
                main
            )

            if (!accepted) {
                listGestureInFlight = false
                armListAfterGesture(LIST_GESTURE_CANCEL_RETRY_MS)
            } else {
                // Hard fallback in case a device/Unity combination delays the
                // normal callback. The watchdog sees in-flight and waits rather
                // than injecting a second gesture on top of this one.
                main.postDelayed(listWatchdogKick, LIST_GESTURE_HARD_TIMEOUT_MS)
            }
        }
    }

    private fun armListAfterGesture(delayMs: Long) {
        if (!prefs.enabled || !listContextActive) return
        lastFrameFingerprint = Long.MIN_VALUE
        lastProcessedFrameAt = 0L
        lastOcrAt = 0L
        nextActionAt = SystemClock.elapsedRealtime() + delayMs
        main.removeCallbacks(listWatchdogKick)
        main.postDelayed(listWatchdogKick, delayMs)
    }
'''
replace_once(old_swipes, new_swipes)

# Service shutdown must clear any stale gesture state.
replace_once(
    '''        listContextActive = false
        raceSweepActive = false
''',
    '''        listContextActive = false
        listGestureInFlight = false
        raceSweepActive = false
'''
)

replace_once(
    '''        private const val LIST_STALL_RETRY_MS = 180L
        private const val DECOR_GUARD_RETRY_MS = 350L
''',
    '''        private const val LIST_STALL_RETRY_MS = 180L
        private const val DECOR_GUARD_RETRY_MS = 350L
        private const val LIST_GESTURE_DURATION_MS = 190L
        private const val LIST_GESTURE_SETTLE_MS = 90L
        private const val LIST_GESTURE_CANCEL_RETRY_MS = 140L
        private const val LIST_GESTURE_BUSY_RETRY_MS = 80L
        private const val LIST_GESTURE_HARD_TIMEOUT_MS = 850L
'''
)

p.write_text(s, encoding="utf-8")
print("Applied V0.4.2 gesture completion continuation fix")
