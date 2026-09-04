from pathlib import Path
import re

p = Path('app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt')
s = p.read_text()

# Stable RACE flow:
#   normal priority search -> no target -> per-card ETA census -> standby.
# ETA may never run before the normal search. A real list mutation can abort
# census and restart priority search. Only verified bird-view standby may use
# blind/direct repeated taps.

# 1) Remove the bad early-census branch if it is still present.
early_census = re.compile(
    r'''\n        if \(\n'''
    r'''            prefs\.mode == RunMode\.RACE &&\n'''
    r'''            timingCensusRequired &&\n'''
    r'''            predictedSpawnAt <= 0L &&\n'''
    r'''            !predictionRefreshPending &&\n'''
    r'''            !targetMapLockActive &&\n'''
    r'''            !targetMapLockPending\n'''
    r'''        \) \{\n'''
    r'''            val atStart = .*?\n'''
    r'''            if \(!atStart\) \{.*?\n'''
    r'''            \}\n'''
    r'''            etaInspectionActive = true\n'''
    r'''            etaInspectionAdvancePending = false\n'''
    r'''            etaInspectionCurrentWasLast = false\n'''
    r'''            etaInspectionCurrentPosition = -1\n'''
    r'''            etaInspectionCount = 0\n'''
    r'''            listSwipeCount = 0\n'''
    r'''            lastListPosition = .*?\n'''
    r'''            stuckListPositionCount = 0\n'''
    r'''            handleEtaInspectionList\(frame, lines, reachedEnd, position\)\n'''
    r'''            return\n'''
    r'''        \}\n''',
    re.S,
)
s, removed = early_census.subn('\n', s, count=1)
if removed > 1:
    raise SystemExit(f'early ETA census: expected at most 1 block, got {removed}')

# 2) Genuine Pikmin list mutation interrupts ETA census. Our own detail
# open/back/list swipe transitions are separately suppressed for a short time.
old_guard = 'if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby && !etaInspectionActive) {'
new_guard = 'if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {'
if old_guard in s:
    s = s.replace(old_guard, new_guard, 1)

anchor = '''        if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {\n            urgentListChange = false\n'''
with_cancel = '''        if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {\n            urgentListChange = false\n            etaInspectionActive = false\n            etaInspectionAdvancePending = false\n'''
if anchor in s and with_cancel not in s:
    s = s.replace(anchor, with_cancel, 1)

# 3) Normalize completion semantics. A finished census stays valid until its
# retry deadline. If no <=10m ETA was readable, retry after 15s, but only after
# another full normal search.
finish_pattern = re.compile(
    r'''    private fun finishEtaInspection\(\) \{.*?\n    \}\n\n    private fun parkAtStart\(\) \{''',
    re.S,
)
finish_replacement = '''    private fun finishEtaInspection() {
        etaInspectionActive = false
        etaInspectionAdvancePending = false
        etaInspectionCurrentWasLast = false
        etaInspectionCurrentPosition = -1
        etaInspectionCount = 0
        val now = SystemClock.elapsedRealtime()
        timingCensusCompletedAt = now
        timingCensusRequired = false
        nextEtaInspectionAt = if (predictedSpawnAt > 0L)
            now + ETA_REINSPECTION_MS
        else
            now + ETA_FAILED_RETRY_MS
        phase = firstPhase()
        if (predictedSpawnAt > 0L && predictedSourcePosition > 0) {
            beginRewind(RewindResume.TARGET)
        } else {
            beginRewind(RewindResume.PARK)
        }
    }

    private fun parkAtStart() {'''
s, n = finish_pattern.subn(finish_replacement, s, count=1)
if n != 1:
    raise SystemExit(f'finishEtaInspection normalization: expected 1 function, got {n}')

# 4) When ETA expires without a prediction, wake a *normal* search first.
# Only if that whole search is still empty will advanceSearchPhaseAndRefresh()
# enter INSPECT again.
old_park_retry = '''        if (prefs.mode == RunMode.RACE && predictedSpawnAt <= 0L && now >= nextEtaInspectionAt) {
            timingCensusRequired = true
            scheduleFreshListScan(180L)
            return
        }
'''
new_park_retry = '''        if (
            prefs.mode == RunMode.RACE &&
            predictedSpawnAt <= 0L &&
            !timingCensusRequired &&
            nextEtaInspectionAt > 0L &&
            now >= nextEtaInspectionAt
        ) {
            timingCensusRequired = true
            parkedAtStart = false
            raceSweepActive = true
            raceBackupSweepAt = 0L
            listSwipeCount = 0
            lastListPosition = -1
            stuckListPositionCount = 0
            scheduleFreshListScan(180L)
            return
        }
'''
if old_park_retry in s:
    s = s.replace(old_park_retry, new_park_retry, 1)
elif new_park_retry not in s:
    raise SystemExit('park retry state not found')

# 5) Final phase of the normal search is the ONLY place that may start a fresh
# ETA census. Make this explicit and idempotent.
old_advance = '''        phase = firstPhase()
        val now = SystemClock.elapsedRealtime()
        if (predictedSpawnAt > 0L && isPredictionPrewarm(now)) {
            predictionRefreshNextAt = max(
                predictionRefreshNextAt,
                now + PredictionRefreshPolicy.RETRY_REFRESH_INTERVAL_MS
            )
            beginRewind(RewindResume.STANDBY)
        } else if (predictedSpawnAt > 0L || now < nextEtaInspectionAt) {
            beginRewind(RewindResume.PARK)
        } else {
            beginRewind(RewindResume.INSPECT)
        }
'''
new_advance = '''        phase = firstPhase()
        val now = SystemClock.elapsedRealtime()
        if (predictedSpawnAt > 0L && isPredictionPrewarm(now)) {
            predictionRefreshNextAt = max(
                predictionRefreshNextAt,
                now + PredictionRefreshPolicy.RETRY_REFRESH_INTERVAL_MS
            )
            beginRewind(RewindResume.STANDBY)
        } else if (predictedSpawnAt > 0L) {
            beginRewind(RewindResume.PARK)
        } else if (timingCensusRequired) {
            beginRewind(RewindResume.INSPECT)
        } else {
            beginRewind(RewindResume.PARK)
        }
'''
if old_advance in s:
    s = s.replace(old_advance, new_advance, 1)
elif new_advance not in s:
    raise SystemExit('advanceSearchPhaseAndRefresh state not found')

# 6) Verify actual assembled-source behavior, independent of patch history.
required = (
    'timingCensusRequired',
    'timingCensusCompletedAt',
    'ETA_TRANSITION_EVENT_SUPPRESS_MS',
    'ETA_FAILED_RETRY_MS',
    'handleEtaInspectionDetail',
    'MushroomTiming.parseFinishEtaMillis',
    'triggerPredictionListRefresh',
    'tryBlindMapTargetTap',
    'MushroomLobbyPolicy.canJoin',
    'prepareFullLobbyRecovery',
    'GestureResultCallback',
)
for symbol in required:
    if symbol not in s:
        raise SystemExit(f'missing required symbol: {symbol}')

start = s.find('    private fun handleMushroomList(')
end = s.find('    private fun searchSwipeCooldownMs()', start)
if start < 0 or end < 0:
    raise SystemExit('cannot isolate handleMushroomList')
list_body = s[start:end]
if 'val target = chooseOcrTarget(lines)' not in list_body:
    raise SystemExit('normal target search disappeared')
if 'timingCensusRequired &&' in list_body:
    raise SystemExit('ETA census still starts before normal target search')

advance_start = s.find('    private fun advanceSearchPhaseAndRefresh()')
advance_end = s.find('    private fun markJoinSubmission()', advance_start)
if advance_start < 0 or advance_end < 0:
    raise SystemExit('cannot isolate advanceSearchPhaseAndRefresh')
advance_body = s[advance_start:advance_end]
if 'else if (timingCensusRequired)' not in advance_body:
    raise SystemExit('ETA census is not gated to end-of-search')
if 'beginRewind(RewindResume.INSPECT)' not in advance_body:
    raise SystemExit('empty full search no longer enters ETA census')

inspect_start = s.find('    private fun handleEtaInspectionList(')
inspect_end = s.find('    private fun handleTargetPositioningList(', inspect_start)
if inspect_start < 0 or inspect_end < 0:
    raise SystemExit('cannot isolate ETA list handler')
inspect_body = s[inspect_start:inspect_end]
if 'tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)' not in inspect_body:
    raise SystemExit('ETA census does not open real detail cards')

eta_detail_start = s.find('    private fun handleEtaInspectionDetail(')
eta_detail_end = s.find('    private fun recordPredictedRespawn(', eta_detail_start)
if eta_detail_start < 0 or eta_detail_end < 0:
    raise SystemExit('cannot isolate ETA detail parser')
eta_detail_body = s[eta_detail_start:eta_detail_end]
if 'MushroomTiming.parseFinishEtaMillis(normalized)' not in eta_detail_body:
    raise SystemExit('ETA detail page no longer parses displayed finish time')

if 'tryBlindListTargetTap' in s or 'listTargetTapX' in s:
    raise SystemExit('blind/direct list tapping reintroduced')
if 'tryBlindMapTargetTap' not in s:
    raise SystemExit('verified bird-map fast tap path missing')

p.write_text(s)
