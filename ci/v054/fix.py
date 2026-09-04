from pathlib import Path

p = Path('app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt')
s = p.read_text()

def one(old: str, new: str, label: str):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    s = s.replace(old, new, 1)

# Prediction-list refresh state. Blind list tap coordinates are removed entirely.
one(
'''    @Volatile private var listTargetStandby = false\n    @Volatile private var listTargetTapX = 0f\n    @Volatile private var listTargetTapY = 0f\n    @Volatile private var lastBlindTargetTapAt = 0L\n''',
'''    @Volatile private var listTargetStandby = false\n    @Volatile private var predictionRefreshPending = false\n    @Volatile private var predictionRefreshSawExplore = false\n    @Volatile private var predictionRefreshNextAt = 0L\n    @Volatile private var predictionRefreshExitAt = 0L\n    @Volatile private var predictionRefreshCount = 0\n    @Volatile private var lastBlindTargetTapAt = 0L\n''',
'prediction refresh fields')

# Direct repeated taps are forbidden on list surfaces. Only verified bird-map lock may use them.
one(
'''                    if (\n                        listTargetStandby &&\n                        listContextActive &&\n                        isMutation &&\n                        now >= suppressListMutationEventsUntil &&\n                        now >= predictedSpawnAt - DIRECT_TAP_LEAD_MS &&\n                        now <= predictionWindowUntil &&\n                        now - lastBlindTargetTapAt >= RaceTapPolicy.intervalMs(now, predictedSpawnAt)\n                    ) {\n                        tryBlindListTargetTap(now)\n                    }\n''',
'',
'remove list event blind tap')

one(
'''        if (listTargetStandby && isPredictionPrewarm(now)) {\n            if (handleAccessibilityTree(root)) return\n            if (now < raceTapVerificationUntil) {\n                requestOcrFallback()\n                return\n            }\n            if (now >= predictedSpawnAt - DIRECT_TAP_LEAD_MS && tryBlindListTargetTap(now)) return\n        }\n''',
'''        if (listTargetStandby && isPredictionPrewarm(now)) {\n            if (PredictionRefreshPolicy.shouldRefresh(\n                    now, predictedSpawnAt, predictionWindowUntil, predictionRefreshNextAt\n                )\n            ) {\n                triggerPredictionListRefresh(now)\n                return\n            }\n        }\n''',
'replace list standby fast path')

# When refresh has exited to the Explore home, immediately re-enter Explore; don't honor generic refresh delay.
one(
'''        findExactNode(entries, "探險")?.let {\n            val now = SystemClock.elapsedRealtime()\n            if (refreshPending && now < reopenExploreAt) {\n                nextActionAt = reopenExploreAt\n                return true\n            }\n            listSwipeCount = 0\n            lastListPosition = -1\n            stuckListPositionCount = 0\n            refreshPending = false\n            clickNode(it.node, 220)\n            return true\n        }\n''',
'''        findExactNode(entries, "探險")?.let {\n            val now = SystemClock.elapsedRealtime()\n            if (predictionRefreshPending) {\n                predictionRefreshSawExplore = true\n                listSwipeCount = 0\n                lastListPosition = -1\n                stuckListPositionCount = 0\n                refreshPending = false\n                clickNode(it.node, PREDICTION_REENTER_COOLDOWN_MS)\n                return true\n            }\n            if (refreshPending && now < reopenExploreAt) {\n                nextActionAt = reopenExploreAt\n                return true\n            }\n            listSwipeCount = 0\n            lastListPosition = -1\n            stuckListPositionCount = 0\n            refreshPending = false\n            clickNode(it.node, 220)\n            return true\n        }\n''',
'node re-enter branch')

one(
'''        findExactOcrLine(lines, "探險")?.let {\n            val now = SystemClock.elapsedRealtime()\n            if (refreshPending && now < reopenExploreAt) {\n                nextActionAt = reopenExploreAt\n                return\n            }\n            listSwipeCount = 0\n            lastListPosition = -1\n            stuckListPositionCount = 0\n            refreshPending = false\n            tapOcrButton(frame, it, 220)\n        }\n''',
'''        findExactOcrLine(lines, "探險")?.let {\n            val now = SystemClock.elapsedRealtime()\n            if (predictionRefreshPending) {\n                predictionRefreshSawExplore = true\n                listSwipeCount = 0\n                lastListPosition = -1\n                stuckListPositionCount = 0\n                refreshPending = false\n                tapOcrButton(frame, it, PREDICTION_REENTER_COOLDOWN_MS)\n                return\n            }\n            if (refreshPending && now < reopenExploreAt) {\n                nextActionAt = reopenExploreAt\n                return\n            }\n            listSwipeCount = 0\n            lastListPosition = -1\n            stuckListPositionCount = 0\n            refreshPending = false\n            tapOcrButton(frame, it, 220)\n        }\n''',
'OCR re-enter branch')

# A confirmed re-entry is the only thing that starts the refreshed race sweep.
one(
'''        val now = SystemClock.elapsedRealtime()\n        val position = extractListPosition(normalized)\n        val reachedEnd = updateListPositionAndCheckEnd(position)\n\n        if (targetPositioning) {\n''',
'''        val now = SystemClock.elapsedRealtime()\n        val position = extractListPosition(normalized)\n\n        if (predictionRefreshPending) {\n            if (!predictionRefreshSawExplore) {\n                // Back did not actually leave the list. Retry the exit instead\n                // of pretending the stale list was refreshed.\n                if (now - predictionRefreshExitAt >= PREDICTION_EXIT_RETRY_MS) {\n                    predictionRefreshExitAt = now\n                    listContextActive = false\n                    goBack(PREDICTION_EXIT_COOLDOWN_MS)\n                } else {\n                    scheduleFreshListScan(PREDICTION_EXIT_RETRY_MS)\n                }\n                return\n            }\n            predictionRefreshPending = false\n            predictionRefreshSawExplore = false\n            listTargetStandby = false\n            parkedAtStart = false\n            phase = firstPhase()\n            raceSweepActive = true\n            raceBackupSweepAt = 0L\n            urgentListChange = false\n            listSwipeCount = 0\n            lastListPosition = -1\n            stuckListPositionCount = 0\n            predictionLastSweepAt = now\n        }\n\n        val reachedEnd = updateListPositionAndCheckEnd(position)\n\n        if (targetPositioning) {\n''',
'fresh list entry gate')

# Do not erase the full/out-of-range cache just because Unity emitted a mutation event.
one(
'''            urgentListChange = false\n            unreachableListPositions.clear()\n            etaInspectionActive = false\n''',
'''            urgentListChange = false\n            etaInspectionActive = false\n''',
'preserve blocked positions')

# STANDBY means card-1 timing wait now, not repositioning to an old card.
one(
'''                RewindResume.STANDBY -> {\n                    parkedAtStart = false\n                    listTargetStandby = true\n                    targetPositioning = true\n                    targetAdvanceRemaining = (predictedSourcePosition - 1).coerceAtLeast(0)\n                    scheduleFreshListScan(140L)\n                }\n''',
'''                RewindResume.STANDBY -> {\n                    parkedAtStart = false\n                    listTargetStandby = true\n                    targetPositioning = false\n                    targetAdvanceRemaining = 0\n                    val now = SystemClock.elapsedRealtime()\n                    if (predictionRefreshNextAt <= 0L) {\n                        predictionRefreshNextAt = PredictionRefreshPolicy.firstRefreshAt(predictedSpawnAt)\n                    }\n                    scheduleFreshListScan((predictionRefreshNextAt - now).coerceAtLeast(180L))\n                }\n''',
'standby rewind semantics')

# Old target-card capture path is no longer allowed to prepare list blind taps.
one(
'''        if (listTargetStandby) {\n            val box = title.boundingBox\n            if (box != null) {\n                listTargetTapX = box.exactCenterX() * frame.scaleX\n                listTargetTapY = box.exactCenterY() * frame.scaleY\n            } else {\n                listTargetTapX = resources.displayMetrics.widthPixels * 0.50f\n                listTargetTapY = resources.displayMetrics.heightPixels * 0.62f\n            }\n            blindTargetTapAttempts = 0\n            lastBlindTargetTapAt = 0L\n            scheduleFreshListScan(\n                (predictionReadyAt - SystemClock.elapsedRealtime()).coerceAtLeast(180L)\n            )\n            return\n        }\n''',
'''        if (listTargetStandby) {\n            // List standby never blind-taps a card. Return to card 1 and use a\n            // real exit/re-enter refresh at the predicted time instead.\n            beginRewind(RewindResume.STANDBY)\n            return\n        }\n''',
'target-card blind preparation')

# Fallback waits at card 1 and initializes the first refresh deadline.
one(
'''        listTargetStandby = true\n        blindTargetTapAttempts = 0\n        lastBlindTargetTapAt = 0L\n        raceTapVerificationUntil = 0L\n''',
'''        listTargetStandby = true\n        predictionRefreshPending = false\n        predictionRefreshSawExplore = false\n        predictionRefreshNextAt = PredictionRefreshPolicy.firstRefreshAt(predictedSpawnAt)\n        predictionRefreshExitAt = 0L\n        predictionRefreshCount = 0\n        blindTargetTapAttempts = 0\n        lastBlindTargetTapAt = 0L\n        raceTapVerificationUntil = 0L\n''',
'fallback refresh init')

# Replace list standby implementation and delete list blind-tap routine.
start = s.find('    private fun handleListTargetStandby(\n')
end = s.find('    private fun tryBlindMapTargetTap(now: Long): Boolean {\n', start)
if start < 0 or end < 0:
    raise SystemExit('standby function block not found')
new_block = '''    private fun handleListTargetStandby(\n        frame: OcrFrame,\n        lines: List<Text.Line>,\n        position: Pair<Int, Int>?\n    ) {\n        val now = SystemClock.elapsedRealtime()\n        if (predictedSpawnAt <= 0L || now > predictionWindowUntil) {\n            listTargetStandby = false\n            clearPrediction()\n            beginRewind(RewindResume.PARK)\n            return\n        }\n\n        // Wait at card 1. Never use repeated/direct taps on the list surface.\n        if (position != null && position.first != 1) {\n            beginRewind(RewindResume.STANDBY)\n            return\n        }\n\n        if (predictionRefreshNextAt <= 0L) {\n            predictionRefreshNextAt = PredictionRefreshPolicy.firstRefreshAt(predictedSpawnAt)\n        }\n        if (PredictionRefreshPolicy.shouldRefresh(\n                now, predictedSpawnAt, predictionWindowUntil, predictionRefreshNextAt\n            )\n        ) {\n            triggerPredictionListRefresh(now)\n            return\n        }\n\n        val wait = (predictionRefreshNextAt - now).coerceIn(180L, 900L)\n        scheduleFreshListScan(wait)\n    }\n\n    private fun triggerPredictionListRefresh(now: Long) {\n        if (predictedSpawnAt <= 0L || now > predictionWindowUntil) return\n        if (predictionRefreshPending) return\n        predictionRefreshPending = true\n        predictionRefreshSawExplore = false\n        predictionRefreshExitAt = now\n        predictionRefreshCount++\n        predictionRefreshNextAt = now + PredictionRefreshPolicy.RETRY_REFRESH_INTERVAL_MS\n        listTargetStandby = false\n        parkedAtStart = false\n        raceSweepActive = false\n        urgentListChange = false\n        listContextActive = false\n        main.removeCallbacks(listWatchdogKick)\n        goBack(PREDICTION_EXIT_COOLDOWN_MS)\n    }\n\n'''
s = s[:start] + new_block + s[end:]

# After a refreshed complete no-target pass, wait briefly then exit/re-enter again.
one(
'''        phase = firstPhase()\n        val now = SystemClock.elapsedRealtime()\n        if (predictedSpawnAt > 0L || now < nextEtaInspectionAt) {\n            beginRewind(RewindResume.PARK)\n        } else {\n            beginRewind(RewindResume.INSPECT)\n        }\n''',
'''        phase = firstPhase()\n        val now = SystemClock.elapsedRealtime()\n        if (predictedSpawnAt > 0L && isPredictionPrewarm(now)) {\n            predictionRefreshNextAt = max(\n                predictionRefreshNextAt,\n                now + PredictionRefreshPolicy.RETRY_REFRESH_INTERVAL_MS\n            )\n            beginRewind(RewindResume.STANDBY)\n        } else if (predictedSpawnAt > 0L || now < nextEtaInspectionAt) {\n            beginRewind(RewindResume.PARK)\n        } else {\n            beginRewind(RewindResume.INSPECT)\n        }\n''',
'retry refresh after no target')

# Clear all refresh state with the prediction.
one(
'''        listTargetStandby = false\n        listTargetTapX = 0f\n        listTargetTapY = 0f\n        lastBlindTargetTapAt = 0L\n''',
'''        listTargetStandby = false\n        predictionRefreshPending = false\n        predictionRefreshSawExplore = false\n        predictionRefreshNextAt = 0L\n        predictionRefreshExitAt = 0L\n        predictionRefreshCount = 0\n        lastBlindTargetTapAt = 0L\n''',
'clear refresh state')

# Add timing constants for real refresh navigation.
one(
'''        private const val DIRECT_TAP_LEAD_MS = 800L\n        private const val DIRECT_TAP_VERIFY_MS = 260L\n        private const val DIRECT_STANDBY_POLL_MS = 180L\n''',
'''        private const val DIRECT_TAP_LEAD_MS = 800L\n        private const val DIRECT_TAP_VERIFY_MS = 260L\n        private const val PREDICTION_EXIT_COOLDOWN_MS = 120L\n        private const val PREDICTION_EXIT_RETRY_MS = 420L\n        private const val PREDICTION_REENTER_COOLDOWN_MS = 140L\n''',
'refresh navigation constants')

# The old list-poll constant must not survive.
s = s.replace('        private const val DIRECT_STANDBY_POLL_MS = 180L\n', '')

# Reset refresh state on destroy as well.
one(
'''        targetMapEnterDeadline = 0L\n        listTargetStandby = false\n        main.removeCallbacks(listWatchdogKick)\n''',
'''        targetMapEnterDeadline = 0L\n        listTargetStandby = false\n        predictionRefreshPending = false\n        predictionRefreshSawExplore = false\n        main.removeCallbacks(listWatchdogKick)\n''',
'destroy refresh reset')

for forbidden in ('tryBlindListTargetTap', 'listTargetTapX', 'listTargetTapY', 'DIRECT_STANDBY_POLL_MS'):
    if forbidden in s:
        raise SystemExit(f'forbidden list blind-tap symbol remains: {forbidden}')

required = [
    'triggerPredictionListRefresh',
    'PredictionRefreshPolicy.shouldRefresh',
    'predictionRefreshSawExplore',
    'PREDICTION_REENTER_COOLDOWN_MS',
    'tryBlindMapTargetTap',
    'MushroomLobbyPolicy.canJoin',
]
for item in required:
    if item not in s:
        raise SystemExit(f'missing expected symbol: {item}')

p.write_text(s)
