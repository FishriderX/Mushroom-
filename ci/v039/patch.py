from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"patch pattern expected once, found {count}: {old[:160]!r}")
    s = s.replace(old, new, 1)


# RACE no longer continuously patrols the carousel. It parks at card 1 and
# treats a real game-content change as higher priority than continuing a sweep.
replace_once(
    '''    @Volatile private var listContextActive = false
    @Volatile private var forceAdvanceOnList = false
''',
    '''    @Volatile private var listContextActive = false
    @Volatile private var raceSweepActive = false
    @Volatile private var raceBackupSweepAt = 0L
    @Volatile private var urgentListChange = false
    @Volatile private var suppressListMutationEventsUntil = 0L
    @Volatile private var lastUrgentListEventAt = 0L
    @Volatile private var forceAdvanceOnList = false
'''
)

# A list-content mutation immediately preempts normal throttles. Self-generated
# carousel gestures are suppressed for a short window so their Unity events do
# not look like newly spawned mushrooms.
old_event = '''            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val now = SystemClock.elapsedRealtime()
                if (prefs.mode == RunMode.RACE) burstUntil = now + EVENT_BURST_MS
                main.removeCallbacks(eventKick)
                val wait = max(0L, nextActionAt - now).coerceAtMost(120L)
                main.postDelayed(eventKick, wait)
            }
'''
new_event = '''            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val now = SystemClock.elapsedRealtime()
                if (prefs.mode == RunMode.RACE) {
                    burstUntil = now + EVENT_BURST_MS
                    val isMutation =
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    if (
                        listContextActive &&
                        isMutation &&
                        now >= suppressListMutationEventsUntil &&
                        now - lastUrgentListEventAt >= RACE_LIST_EVENT_DEBOUNCE_MS
                    ) {
                        lastUrgentListEventAt = now
                        urgentListChange = true
                        nextActionAt = 0L
                        lastFrameFingerprint = Long.MIN_VALUE
                        lastProcessedFrameAt = 0L
                        lastOcrAt = 0L
                        main.removeCallbacks(listWatchdogKick)
                    }
                }
                main.removeCallbacks(eventKick)
                val wait = if (urgentListChange) 0L
                    else max(0L, nextActionAt - now).coerceAtMost(120L)
                main.postDelayed(eventKick, wait)
            }
'''
replace_once(old_event, new_event)

# If new content arrives while a RACE sweep is away from the first card, abort
# the old sweep and return to card 1 before doing anything else. At card 1, the
# newly changed frame gets evaluated immediately.
replace_once(
    '''        val position = extractListPosition(normalized)
        val reachedEnd = updateListPositionAndCheckEnd(position)

        if (rewindListPending) {
''',
    '''        val position = extractListPosition(normalized)
        val reachedEnd = updateListPositionAndCheckEnd(position)

        if (prefs.mode == RunMode.RACE && urgentListChange) {
            urgentListChange = false
            val awayFromStart = (position?.first ?: if (listSwipeCount > 0) 2 else 1) > 1
            if (awayFromStart || rewindListPending) {
                raceSweepActive = false
                rewindListPending = true
                rewindSwipeCount = 1
                swipeListBackwardReliable(RACE_REWIND_COOLDOWN_MS)
                return
            }
            raceSweepActive = false
            raceBackupSweepAt = SystemClock.elapsedRealtime() + RACE_BACKUP_SWEEP_MS
        }

        if (rewindListPending) {
'''
)

# Once rewind actually reaches card 1, stay parked there in RACE. A real content
# event wakes it instantly; the scheduled full sweep is only a safety net.
replace_once(
    '''                rewindListPending = false
                rewindSwipeCount = 0
                listSwipeCount = 0
                lastListPosition = -1
                stuckListPositionCount = 0
                scheduleFreshListScan(listCycleIdleMs())
''',
    '''                rewindListPending = false
                rewindSwipeCount = 0
                listSwipeCount = 0
                lastListPosition = -1
                stuckListPositionCount = 0
                if (prefs.mode == RunMode.RACE) {
                    raceSweepActive = false
                    raceBackupSweepAt = SystemClock.elapsedRealtime() + RACE_BACKUP_SWEEP_MS
                    scheduleFreshListScan(RACE_BACKUP_SWEEP_MS)
                } else {
                    scheduleFreshListScan(listCycleIdleMs())
                }
'''
)

# If the current first card has no target, RACE stays put until the safety sweep
# is due. First entry into the list still performs one immediate complete sweep.
replace_once(
    '''        if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
            advanceSearchPhaseAndRefresh()
        } else {
            listSwipeCount++
            swipeListReliable(240)
        }
''',
    '''        if (prefs.mode == RunMode.RACE && !raceSweepActive) {
            val now = SystemClock.elapsedRealtime()
            if (raceBackupSweepAt == 0L) {
                raceSweepActive = true
            } else if (now < raceBackupSweepAt) {
                scheduleFreshListScan((raceBackupSweepAt - now).coerceAtLeast(200L))
                return
            } else {
                raceSweepActive = true
                raceBackupSweepAt = 0L
            }
        }
        if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {
            advanceSearchPhaseAndRefresh()
        } else {
            listSwipeCount++
            swipeListReliable(if (prefs.mode == RunMode.RACE) RACE_SWEEP_COOLDOWN_MS else 240L)
        }
'''
)

# Ending a sweep always transitions to rewind/park in RACE.
replace_once(
    '''        refreshPending = false
        rewindListPending = true
        rewindSwipeCount = 1
        swipeListBackwardReliable(220)
''',
    '''        refreshPending = false
        if (prefs.mode == RunMode.RACE) raceSweepActive = false
        rewindListPending = true
        rewindSwipeCount = 1
        swipeListBackwardReliable(if (prefs.mode == RunMode.RACE) RACE_REWIND_COOLDOWN_MS else 220L)
'''
)

# Any successful submission resets RACE patrol state. The post-join return will
# get one fresh complete scan, then park again if nothing is available.
replace_once(
    '''        rewindListPending = false
        rewindSwipeCount = 0
        detailCameFromBirdMap = false
''',
    '''        rewindListPending = false
        rewindSwipeCount = 0
        raceSweepActive = false
        raceBackupSweepAt = 0L
        urgentListChange = false
        detailCameFromBirdMap = false
'''
)

# Suppress Unity's own scroll animation events briefly after our gestures.
replace_once(
    '''            listContextActive = true
            listProgressGeneration++
            val dm = resources.displayMetrics
''',
    '''            listContextActive = true
            listProgressGeneration++
            suppressListMutationEventsUntil = SystemClock.elapsedRealtime() + RACE_SELF_GESTURE_EVENT_SUPPRESS_MS
            val dm = resources.displayMetrics
'''
)
replace_once(
    '''            listContextActive = true
            listProgressGeneration++
            val dm = resources.displayMetrics
''',
    '''            listContextActive = true
            listProgressGeneration++
            suppressListMutationEventsUntil = SystemClock.elapsedRealtime() + RACE_SELF_GESTURE_EVENT_SUPPRESS_MS
            val dm = resources.displayMetrics
'''
)

# Daily rollover starts cleanly rather than inheriting a parked timestamp from
# the previous day.
replace_once(
    '''            rewindListPending = false
            rewindSwipeCount = 0
            nextActionAt = 0L
''',
    '''            rewindListPending = false
            rewindSwipeCount = 0
            raceSweepActive = false
            raceBackupSweepAt = 0L
            urgentListChange = false
            nextActionAt = 0L
'''
)

# Clear race-list state when the service is torn down.
replace_once(
    '''    override fun onDestroy() {
        listContextActive = false
''',
    '''    override fun onDestroy() {
        listContextActive = false
        raceSweepActive = false
        urgentListChange = false
'''
)

replace_once(
    '''        private const val LIST_STALL_RETRY_MS = 180L
''',
    '''        private const val LIST_STALL_RETRY_MS = 180L
        private const val RACE_BACKUP_SWEEP_MS = 12_000L
        private const val RACE_SWEEP_COOLDOWN_MS = 150L
        private const val RACE_REWIND_COOLDOWN_MS = 120L
        private const val RACE_SELF_GESTURE_EVENT_SUPPRESS_MS = 420L
        private const val RACE_LIST_EVENT_DEBOUNCE_MS = 180L
'''
)

p.write_text(s, encoding="utf-8")
print("Applied Mushroom Helper V0.3.8 RACE park-first patch")
