from pathlib import Path

p = Path('app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt')
s = p.read_text()

def one(old: str, new: str, label: str):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    s = s.replace(old, new, 1)

def exact(old: str, new: str, expected: int, label: str):
    global s
    n = s.count(old)
    if n != expected:
        raise SystemExit(f'{label}: expected {expected} matches, got {n}')
    s = s.replace(old, new)

# A timing census is required only AFTER a complete normal no-target search.
# This keeps immediately joinable mushrooms higher priority than ETA work.
one(
'''    @Volatile private var nextEtaInspectionAt = 0L\n    @Volatile private var predictedFinishAt = 0L\n''',
'''    @Volatile private var nextEtaInspectionAt = 0L\n    @Volatile private var timingCensusRequired = true\n    @Volatile private var timingCensusCompletedAt = 0L\n    @Volatile private var predictedFinishAt = 0L\n''',
'timing census fields')

# Opening a detail card is our own navigation. Suppress only that short Unity
# transition so it is not mistaken for a newly spawned mushroom event.
one(
'''            etaInspectionCount++\n            listProgressGeneration++\n            tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)\n''',
'''            etaInspectionCount++\n            listProgressGeneration++\n            listContextActive = false\n            suppressListMutationEventsUntil =\n                SystemClock.elapsedRealtime() + ETA_TRANSITION_EVENT_SUPPRESS_MS\n            tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)\n''',
'suppress ETA detail open mutation')

# Normal and out-of-range ETA detail returns are both self-navigation.
exact(
'''        etaInspectionAdvancePending = true\n        listContextActive = false\n        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)\n''',
'''        etaInspectionAdvancePending = true\n        listContextActive = false\n        suppressListMutationEventsUntil =\n            SystemClock.elapsedRealtime() + ETA_TRANSITION_EVENT_SUPPRESS_MS\n        goBack(ETA_DETAIL_BACK_COOLDOWN_MS)\n''',
2,
'suppress both ETA detail return mutations')

# A completed pass is the only thing that marks the timing table as inspected.
# If nothing readable was found, retry soon; never invent a predicted time.
one(
'''    private fun finishEtaInspection() {\n        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCurrentWasLast = false\n        etaInspectionCurrentPosition = -1\n        etaInspectionCount = 0\n        nextEtaInspectionAt = SystemClock.elapsedRealtime() + ETA_REINSPECTION_MS\n        phase = firstPhase()\n        if (predictedSpawnAt > 0L && predictedSourcePosition > 0) {\n            beginRewind(RewindResume.TARGET)\n        } else {\n            beginRewind(RewindResume.PARK)\n        }\n    }\n''',
'''    private fun finishEtaInspection() {\n        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCurrentWasLast = false\n        etaInspectionCurrentPosition = -1\n        etaInspectionCount = 0\n        val now = SystemClock.elapsedRealtime()\n        timingCensusCompletedAt = now\n        timingCensusRequired = false\n        nextEtaInspectionAt = if (predictedSpawnAt > 0L)\n            now + ETA_REINSPECTION_MS\n        else\n            now + ETA_FAILED_RETRY_MS\n        phase = firstPhase()\n        if (predictedSpawnAt > 0L && predictedSourcePosition > 0) {\n            beginRewind(RewindResume.TARGET)\n        } else {\n            beginRewind(RewindResume.PARK)\n        }\n    }\n''',
'finish census semantics')

# Final phase of a normal full search decides whether ETA inspection is due.
# This is the hard guarantee the previous versions were missing.
one(
'''        phase = firstPhase()\n        val now = SystemClock.elapsedRealtime()\n        if (predictedSpawnAt > 0L && isPredictionPrewarm(now)) {\n            predictionRefreshNextAt = max(\n                predictionRefreshNextAt,\n                now + PredictionRefreshPolicy.RETRY_REFRESH_INTERVAL_MS\n            )\n            beginRewind(RewindResume.STANDBY)\n        } else if (predictedSpawnAt > 0L || now < nextEtaInspectionAt) {\n            beginRewind(RewindResume.PARK)\n        } else {\n            beginRewind(RewindResume.INSPECT)\n        }\n''',
'''        phase = firstPhase()\n        val now = SystemClock.elapsedRealtime()\n        if (predictedSpawnAt > 0L && isPredictionPrewarm(now)) {\n            predictionRefreshNextAt = max(\n                predictionRefreshNextAt,\n                now + PredictionRefreshPolicy.RETRY_REFRESH_INTERVAL_MS\n            )\n            beginRewind(RewindResume.STANDBY)\n        } else if (predictedSpawnAt > 0L) {\n            beginRewind(RewindResume.PARK)\n        } else if (timingCensusRequired || now >= nextEtaInspectionAt) {\n            beginRewind(RewindResume.INSPECT)\n        } else {\n            beginRewind(RewindResume.PARK)\n        }\n''',
'no-target search must enter ETA census')

# While parked without a prediction, wake in time for another NORMAL search.
# Only after that search is empty again may ETA inspection run again.
one(
'''        val delay = when (prefs.mode) {\n            RunMode.RACE -> RACE_BACKUP_SWEEP_MS\n            RunMode.WATCH -> 8_000L\n            RunMode.ECO -> 30_000L\n        }\n''',
'''        val delay = when (prefs.mode) {\n            RunMode.RACE -> {\n                if (predictedSpawnAt <= 0L && nextEtaInspectionAt > now)\n                    min(RACE_BACKUP_SWEEP_MS, (nextEtaInspectionAt - now).coerceAtLeast(500L))\n                else\n                    RACE_BACKUP_SWEEP_MS\n            }\n            RunMode.WATCH -> 8_000L\n            RunMode.ECO -> 30_000L\n        }\n''',
'parked timing wake')

# A completed join invalidates the old timing census. The next empty normal
# search must collect fresh detail ETAs.
one(
'''        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCount = 0\n        nextEtaInspectionAt = 0L\n        clearPrediction()\n''',
'''        etaInspectionActive = false\n        etaInspectionAdvancePending = false\n        etaInspectionCount = 0\n        nextEtaInspectionAt = 0L\n        timingCensusRequired = true\n        timingCensusCompletedAt = 0L\n        clearPrediction()\n''',
'join invalidates timing census')

# Daily rollover also requires a fresh census after the first normal search.
one(
'''            etaInspectionActive = false\n            etaInspectionAdvancePending = false\n            etaInspectionCount = 0\n            nextEtaInspectionAt = 0L\n            clearPrediction()\n''',
'''            etaInspectionActive = false\n            etaInspectionAdvancePending = false\n            etaInspectionCount = 0\n            nextEtaInspectionAt = 0L\n            timingCensusRequired = true\n            timingCensusCompletedAt = 0L\n            clearPrediction()\n''',
'daily rollover census reset')

one(
'''        private const val ETA_INSPECTION_SWIPE_COOLDOWN_MS = 210L\n        private const val ETA_DETAIL_OPEN_COOLDOWN_MS = 220L\n        private const val ETA_DETAIL_BACK_COOLDOWN_MS = 180L\n''',
'''        private const val ETA_INSPECTION_SWIPE_COOLDOWN_MS = 210L\n        private const val ETA_DETAIL_OPEN_COOLDOWN_MS = 220L\n        private const val ETA_DETAIL_BACK_COOLDOWN_MS = 180L\n        private const val ETA_TRANSITION_EVENT_SUPPRESS_MS = 900L\n        private const val ETA_FAILED_RETRY_MS = 15_000L\n''',
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

# Regression guards: the initial normal search must still exist before census,
# and a timing census must still open real detail cards rather than infer ETA.
if 'val target = chooseOcrTarget(lines)' not in s:
    raise SystemExit('normal target search disappeared')
if 'tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)' not in s:
    raise SystemExit('ETA census no longer opens detail cards')

p.write_text(s)
