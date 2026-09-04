from pathlib import Path

p = Path('app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt')
s = p.read_text()

def one(old: str, new: str, label: str):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    s = s.replace(old, new, 1)

# A RACE session is not allowed to claim it has a respawn prediction until it
# has actually opened mushroom detail cards and read their finish ETA.
one(
'''    @Volatile private var nextEtaInspectionAt = 0L\n    @Volatile private var predictedFinishAt = 0L\n''',
'''    @Volatile private var nextEtaInspectionAt = 0L\n    @Volatile private var timingCensusRequired = true\n    @Volatile private var timingCensusCompletedAt = 0L\n    @Volatile private var predictedFinishAt = 0L\n''',
'timing census fields')

# Do not let ordinary Unity list mutations abort an in-progress timing census.
one(
'''        if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {\n            urgentListChange = false\n            etaInspectionActive = false\n            etaInspectionAdvancePending = false\n''',
'''        if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby && !etaInspectionActive) {\n            urgentListChange = false\n''',
'protect timing census from list mutation')

# Before ordinary target selection, force a complete detail-card census when
# there is no valid prediction. If not at card 1, rewind first.
one(
'''        if (etaInspectionActive) {\n            handleEtaInspectionList(frame, lines, reachedEnd, position)\n            return\n        }\n\n        if (position != null && isListPositionUnreachable(position.first)) {\n''',
'''        if (etaInspectionActive) {\n            handleEtaInspectionList(frame, lines, reachedEnd, position)\n            return\n        }\n\n        if (\n            prefs.mode == RunMode.RACE &&\n            timingCensusRequired &&\n            predictedSpawnAt <= 0L &&\n            !predictionRefreshPending &&\n            !targetMapLockActive &&\n            !targetMapLockPending\n        ) {\n            val atStart = position?.first == 1 || (position == null && listSwipeCount == 0)\n            if (!atStart) {\n                beginRewind(RewindResume.INSPECT)\n                return\n            }\n            etaInspectionActive = true\n            etaInspectionAdvancePending = false\n            etaInspectionCurrentWasLast = false\n            etaInspectionCurrentPosition = -1\n            etaInspectionCount = 0\n            listSwipeCount = 0\n            lastListPosition = position?.first ?: -1\n            stuckListPositionCount = 0\n            handleEtaInspectionList(frame, lines, reachedEnd, position)\n            return\n        }\n\n        if (position != null && isListPositionUnreachable(position.first)) {\n''',
'force initial timing census')

# Opening a detail card is our own navigation. Do not classify the resulting
# Unity window transition as a new-mushroom mutation.
one(
'''            etaInspectionCount++\n            listProgressGeneration++\n            tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)\n''',
'''            etaInspectionCount++\n            listProgressGeneration++\n            listContextActive = false\n            suppressListMutationEventsUntil =\n                SystemClock.elapsedRealtime() + ETA_TRANSITION_EVENT_SUPPRESS_MS\n            tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)\n''',
'suppress ETA detail open mutation')

# Same for returning from detail to list.
one(
'''        etaInspectionAdvancePending = true\n        listContextActive = false\n        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)\n''',
'''        etaInspectionAdvancePending = true\n        listContextActive = false\n        suppressListMutationEventsUntil =\n            SystemClock.elapsedRealtime() + ETA_TRANSITION_EVENT_SUPPRESS_MS\n        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)\n''',
'suppress ETA detail back mutation')

# Out-of-range cards still count as inspected and their back navigation must
# not cancel the census either.
one(
'''        etaInspectionAdvancePending = true\n        listContextActive = false\n        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)\n    }\n\n    private fun rejectTargetOutOfRange() {\n''',
'''        etaInspectionAdvancePending = true\n        listContextActive = false\n        suppressListMutationEventsUntil =\n            SystemClock.elapsedRealtime() + ETA_TRANSITION_EVENT_SUPPRESS_MS\n        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)\n    }\n\n    private fun rejectTargetOutOfRange() {\n''',
'suppress ETA out-of-range back mutation')

# A finished pass is what makes the timing table valid. If no ETA could be
# parsed at all, retry later instead of pretending a prediction exists.
one(
'''    private fun finishEtaInspection() {\n        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCurrentWasLast = false\n        etaInspectionCurrentPosition = -1\n        etaInspectionCount = 0\n        nextEtaInspectionAt = SystemClock.elapsedRealtime() + ETA_REINSPECTION_MS\n        phase = firstPhase()\n        if (predictedSpawnAt > 0L && predictedSourcePosition > 0) {\n            beginRewind(RewindResume.TARGET)\n        } else {\n            beginRewind(RewindResume.PARK)\n        }\n    }\n''',
'''    private fun finishEtaInspection() {\n        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCurrentWasLast = false\n        etaInspectionCurrentPosition = -1\n        etaInspectionCount = 0\n        val now = SystemClock.elapsedRealtime()\n        timingCensusCompletedAt = now\n        timingCensusRequired = false\n        nextEtaInspectionAt = now + ETA_REINSPECTION_MS\n        phase = firstPhase()\n        if (predictedSpawnAt > 0L && predictedSourcePosition > 0) {\n            beginRewind(RewindResume.TARGET)\n        } else {\n            // No readable ETA was found. Park safely, then require another\n            // real detail-card census instead of fabricating a spawn time.\n            timingCensusRequired = true\n            nextEtaInspectionAt = now + ETA_FAILED_RETRY_MS\n            beginRewind(RewindResume.PARK)\n        }\n    }\n''',
'finish census semantics')

# While parked without a prediction, turn the census back on at its retry
# deadline so the helper actually goes back into each card again.
one(
'''        val delay = when (prefs.mode) {\n            RunMode.RACE -> RACE_BACKUP_SWEEP_MS\n            RunMode.WATCH -> 8_000L\n            RunMode.ECO -> 30_000L\n        }\n''',
'''        if (prefs.mode == RunMode.RACE && predictedSpawnAt <= 0L && now >= nextEtaInspectionAt) {\n            timingCensusRequired = true\n            scheduleFreshListScan(180L)\n            return\n        }\n\n        val delay = when (prefs.mode) {\n            RunMode.RACE -> min(RACE_BACKUP_SWEEP_MS, (nextEtaInspectionAt - now).coerceAtLeast(500L))\n            RunMode.WATCH -> 8_000L\n            RunMode.ECO -> 30_000L\n        }\n''',
'parked census retry')

# A completed join invalidates all old finish times; the next list session must
# establish a fresh timing table.
one(
'''        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCount = 0\n        nextEtaInspectionAt = 0L\n        clearPrediction()\n''',
'''        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCount = 0\n        nextEtaInspectionAt = 0L\n        timingCensusRequired = true\n        timingCensusCompletedAt = 0L\n        clearPrediction()\n''',
'join invalidates timing census')

# Daily rollover also starts from a required census.
one(
'''            etaInspectionActive = false\n            etaInspectionAdvancePending = false\n            etaInspectionCount = 0\n            nextEtaInspectionAt = 0L\n            clearPrediction()\n''',
'''            etaInspectionActive = false\n            etaInspectionAdvancePending = false\n            etaInspectionCount = 0\n            nextEtaInspectionAt = 0L\n            timingCensusRequired = true\n            timingCensusCompletedAt = 0L\n            clearPrediction()\n''',
'daily rollover census reset')

# Add explicit retry/transition constants.
one(
'''        private const val ETA_DETAIL_BACK_COOLDOWN_MS = 180L\n        private const val ETA_INSPECTION_SWIPE_COOLDOWN_MS = 180L\n''',
'''        private const val ETA_DETAIL_BACK_COOLDOWN_MS = 180L\n        private const val ETA_INSPECTION_SWIPE_COOLDOWN_MS = 180L\n        private const val ETA_TRANSITION_EVENT_SUPPRESS_MS = 900L\n        private const val ETA_FAILED_RETRY_MS = 15_000L\n''',
'ETA census constants')

for required in (
    'timingCensusRequired',
    'timingCensusCompletedAt',
    'ETA_TRANSITION_EVENT_SUPPRESS_MS',
    'ETA_FAILED_RETRY_MS',
    'handleEtaInspectionDetail',
    'MushroomTiming.parseFinishEtaMillis',
    'triggerPredictionListRefresh',
    'tryBlindMapTargetTap',
):
    if required not in s:
        raise SystemExit(f'missing required symbol {required}')

p.write_text(s)
