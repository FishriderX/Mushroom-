from pathlib import Path

p = Path('app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt')
s = p.read_text()

# 1) Full-lobby failures get their own fast recovery before generic failures.
old = '''        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {\n            prepareFailureRecovery()\n            findNode(entries, "關閉")?.let {\n                clickNode(it.node, 220)\n                return true\n            }\n            goBack(220)\n            return true\n        }\n'''
new = '''        if (containsFullLobby(normalized)) {\n            prepareFullLobbyRecovery()\n            findNode(entries, "關閉")?.let {\n                clickNode(it.node, 140)\n                return true\n            }\n            goBack(140)\n            return true\n        }\n        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {\n            prepareFailureRecovery()\n            findNode(entries, "關閉")?.let {\n                clickNode(it.node, 220)\n                return true\n            }\n            goBack(220)\n            return true\n        }\n'''
if old not in s:
    raise SystemExit('missing node failure branch')
s = s.replace(old, new, 1)

old = '''        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {\n            prepareFailureRecovery()\n            findOcrLine(lines, "關閉")?.let {\n                tapOcrButton(frame, it, 220)\n            } ?: goBack(220)\n            return\n        }\n'''
new = '''        if (containsFullLobby(normalized)) {\n            prepareFullLobbyRecovery()\n            findOcrLine(lines, "關閉")?.let {\n                tapOcrButton(frame, it, 140)\n            } ?: goBack(140)\n            return\n        }\n        if (containsTicketWarning(normalized) || containsJoinFailure(normalized)) {\n            prepareFailureRecovery()\n            findOcrLine(lines, "關閉")?.let {\n                tapOcrButton(frame, it, 220)\n            } ?: goBack(220)\n            return\n        }\n'''
if old not in s:
    raise SystemExit('missing OCR failure branch')
s = s.replace(old, new, 1)

# 2) Accessibility tree must never press Join if it cannot establish a safe count.
old = '''        val participantCount = extractParticipantCountFromText(normalized)\n        if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {\n            rejectDetailAndAdvance()\n            return true\n        }\n        joinTapAttempts++\n'''
new = '''        val participantCount = extractParticipantCountFromText(normalized)\n        if (participantCount == null) {\n            // Unity often does not expose avatar rows as accessibility nodes.\n            // Do not click Join on an unknown count; let OCR/image verification\n            // make the final safety decision instead.\n            return false\n        }\n        if (!MushroomLobbyPolicy.canJoin(participantCount, FREE_SLOT_LIMIT)) {\n            markCurrentTargetBlocked(FULL_OR_UNKNOWN_POSITION_CACHE_MS)\n            rejectDetailAndAdvance()\n            return true\n        }\n        joinTapAttempts++\n'''
if old not in s:
    raise SystemExit('missing node participant branch')
s = s.replace(old, new, 1)

# 3) OCR/detail verification is strict: unknown is a reject, not a Join attempt.
old = '''        val participantCount =\n            extractParticipantCountFromText(normalized)\n                ?: estimateDetailParticipantCount(frame.bitmap, lines)\n        if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {\n            rejectDetailAndAdvance()\n            return\n        }\n        joinTapAttempts++\n'''
new = '''        val participantCount =\n            extractParticipantCountFromText(normalized)\n                ?: estimateDetailParticipantCount(frame.bitmap, lines)\n        if (!MushroomLobbyPolicy.canJoin(participantCount, FREE_SLOT_LIMIT)) {\n            markCurrentTargetBlocked(FULL_OR_UNKNOWN_POSITION_CACHE_MS)\n            rejectDetailAndAdvance()\n            return\n        }\n        joinTapAttempts++\n'''
if old not in s:
    raise SystemExit('missing OCR participant branch')
s = s.replace(old, new, 1)

# 4) Known-full list cards are blocked for the current short window immediately.
old = '''            if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {\n                if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {\n                    advanceSearchPhaseAndRefresh()\n                } else {\n                    listSwipeCount++\n                    swipeListReliable(searchSwipeCooldownMs())\n                }\n                return\n            }\n'''
new = '''            if (participantCount != null && participantCount >= FREE_SLOT_LIMIT) {\n                position?.first?.let { markListPositionBlocked(it, FULL_OR_UNKNOWN_POSITION_CACHE_MS) }\n                if (reachedEnd || listSwipeCount >= MAX_LIST_SWIPES) {\n                    advanceSearchPhaseAndRefresh()\n                } else {\n                    listSwipeCount++\n                    swipeListReliable(searchSwipeCooldownMs())\n                }\n                return\n            }\n'''
if old not in s:
    raise SystemExit('missing list full branch')
s = s.replace(old, new, 1)

# 5) Add helpers and fast full-lobby recovery.
old = '''    private fun prepareFailureRecovery() {\n        if (detailCameFromBirdMap) blacklistLastBirdMapPoint()\n        detailCameFromBirdMap = false\n        lastBirdMapTapKey = null\n        backOutStepsRemaining = 2\n        forceAdvanceOnList = true\n        refreshPending = true\n        reopenExploreAt = SystemClock.elapsedRealtime() + refreshDelayMs()\n        autoTapAttempts = 0\n        joinTapAttempts = 0\n    }\n'''
new = '''    private fun prepareFullLobbyRecovery() {\n        if (detailCameFromBirdMap) blacklistLastBirdMapPoint()\n        markCurrentTargetBlocked(FULL_OR_UNKNOWN_POSITION_CACHE_MS)\n        detailCameFromBirdMap = false\n        lastBirdMapTapKey = null\n        // One back step is enough after the optional dialog is dismissed.\n        // Do not run the slower generic two-step refresh path for an old full lobby.\n        backOutStepsRemaining = 1\n        forceAdvanceOnList = true\n        refreshPending = false\n        autoTapAttempts = 0\n        joinTapAttempts = 0\n    }\n\n    private fun prepareFailureRecovery() {\n        if (detailCameFromBirdMap) blacklistLastBirdMapPoint()\n        detailCameFromBirdMap = false\n        lastBirdMapTapKey = null\n        backOutStepsRemaining = 2\n        forceAdvanceOnList = true\n        refreshPending = true\n        reopenExploreAt = SystemClock.elapsedRealtime() + refreshDelayMs()\n        autoTapAttempts = 0\n        joinTapAttempts = 0\n    }\n'''
if old not in s:
    raise SystemExit('missing failure recovery function')
s = s.replace(old, new, 1)

old = '''    private fun markCurrentTargetUnreachable() {\n        val position = detailSourceListPosition\n        if (position > 0) {\n            unreachableListPositions[position] =\n                SystemClock.elapsedRealtime() + OUT_OF_RANGE_POSITION_CACHE_MS\n        }\n    }\n'''
new = '''    private fun markListPositionBlocked(position: Int, ttlMs: Long) {\n        if (position > 0) {\n            unreachableListPositions[position] = SystemClock.elapsedRealtime() + ttlMs\n        }\n    }\n\n    private fun markCurrentTargetBlocked(ttlMs: Long) {\n        markListPositionBlocked(detailSourceListPosition, ttlMs)\n    }\n\n    private fun markCurrentTargetUnreachable() {\n        markCurrentTargetBlocked(OUT_OF_RANGE_POSITION_CACHE_MS)\n    }\n'''
if old not in s:
    raise SystemExit('missing unreachable function')
s = s.replace(old, new, 1)

# 6) Add dedicated full-lobby text detector before generic join failure.
old = '''    private fun containsJoinFailure(normalized: String): Boolean {\n        return normalized.contains("人數已滿") ||\n            normalized.contains("已達上限") ||\n            normalized.contains("無法參加") ||\n            normalized.contains("無法加入") ||\n            normalized.contains("參加人數已滿")\n    }\n'''
new = '''    private fun containsFullLobby(normalized: String): Boolean {\n        return normalized.contains("人數已滿") ||\n            normalized.contains("參加人數已滿") ||\n            normalized.contains("已達人數上限")\n    }\n\n    private fun containsJoinFailure(normalized: String): Boolean {\n        return containsFullLobby(normalized) ||\n            normalized.contains("已達上限") ||\n            normalized.contains("無法參加") ||\n            normalized.contains("無法加入")\n    }\n'''
if old not in s:
    raise SystemExit('missing join failure detector')
s = s.replace(old, new, 1)

# 7) Reduce repeated Join attempts even after a known-safe count.
s = s.replace('        private const val MAX_JOIN_TAP_ATTEMPTS = 4\n',
              '        private const val MAX_JOIN_TAP_ATTEMPTS = 2\n', 1)
old = '        private const val OUT_OF_RANGE_POSITION_CACHE_MS = 5L * 60L * 1000L\n'
new = old + '        private const val FULL_OR_UNKNOWN_POSITION_CACHE_MS = 30_000L\n'
if old not in s:
    raise SystemExit('missing cache constant')
s = s.replace(old, new, 1)

checks = [
    'MushroomLobbyPolicy.canJoin',
    'prepareFullLobbyRecovery',
    'containsFullLobby',
    'FULL_OR_UNKNOWN_POSITION_CACHE_MS',
    'MAX_JOIN_TAP_ATTEMPTS = 2',
]
for c in checks:
    if c not in s:
        raise SystemExit(f'missing expected result: {c}')

p.write_text(s)
