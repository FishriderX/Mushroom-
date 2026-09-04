from pathlib import Path
import re

p = Path('app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt')
s = p.read_text()

# V0.5.3 invariant:
#   1) complete the normal mushroom search first;
#   2) only after every priority phase is exhausted may ETA census start;
#   3) ETA census must actually open each card detail and parse its timer;
#   4) a genuine new list mutation may interrupt census, while our own
#      detail-open/back/swipe transitions are suppressed separately.

# Remove the bad early-census branch that was inserted before chooseOcrTarget().
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

# A real list mutation must be able to abort an ETA census and restart the
# priority search. Self-generated detail open/back/scroll events are already
# covered by suppressListMutationEventsUntil.
old_guard = 'if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby && !etaInspectionActive) {'
new_guard = 'if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {'
if old_guard in s:
    s = s.replace(old_guard, new_guard, 1)

urgent_anchor = '''        if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {\n            urgentListChange = false\n'''
urgent_with_cancel = '''        if (prefs.mode == RunMode.RACE && urgentListChange && !listTargetStandby) {\n            urgentListChange = false\n            etaInspectionActive = false\n            etaInspectionAdvancePending = false\n'''
if urgent_anchor in s and urgent_with_cancel not in s:
    s = s.replace(urgent_anchor, urgent_with_cancel, 1)

# Regression checks against the *assembled source*, not against a particular
# patch history. This makes repeated CI runs safe after the service source has
# already been consolidated back into the repository.
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

# Normal search has to remain the decision point in handleMushroomList.
if 'val target = chooseOcrTarget(lines)' not in list_body:
    raise SystemExit('normal target search disappeared from handleMushroomList')
if 'timingCensusRequired &&' in list_body:
    raise SystemExit('ETA census still starts before the normal target search')

advance_start = s.find('    private fun advanceSearchPhaseAndRefresh()')
advance_end = s.find('    private fun markJoinSubmission()', advance_start)
if advance_start < 0 or advance_end < 0:
    raise SystemExit('cannot isolate advanceSearchPhaseAndRefresh')
advance_body = s[advance_start:advance_end]
if 'beginRewind(RewindResume.INSPECT)' not in advance_body:
    raise SystemExit('empty full search no longer enters ETA inspection')
if 'timingCensusRequired || now >= nextEtaInspectionAt' not in advance_body:
    raise SystemExit('ETA census is not gated to the end of the full normal search')

inspect_start = s.find('    private fun handleEtaInspectionList(')
inspect_end = s.find('    private fun handleTargetPositioningList(', inspect_start)
if inspect_start < 0 or inspect_end < 0:
    raise SystemExit('cannot isolate ETA inspection list handler')
inspect_body = s[inspect_start:inspect_end]
if 'tapOcrButton(frame, title, ETA_DETAIL_OPEN_COOLDOWN_MS)' not in inspect_body:
    raise SystemExit('ETA census does not open real mushroom detail cards')

eta_detail_start = s.find('    private fun handleEtaInspectionDetail(')
eta_detail_end = s.find('    private fun recordPredictedRespawn(', eta_detail_start)
if eta_detail_start < 0 or eta_detail_end < 0:
    raise SystemExit('cannot isolate ETA detail parser')
eta_detail_body = s[eta_detail_start:eta_detail_end]
if 'MushroomTiming.parseFinishEtaMillis(normalized)' not in eta_detail_body:
    raise SystemExit('ETA detail page no longer parses the displayed finish time')

# List-surface blind taps must stay forbidden; only the verified bird-map lock
# path may use the fast direct-tap strategy.
if 'tryBlindListTargetTap' in s or 'listTargetTapX' in s:
    raise SystemExit('blind/direct list tapping reintroduced')
if 'tryBlindMapTargetTap' not in s:
    raise SystemExit('verified bird-map fast tap path missing')

p.write_text(s)
