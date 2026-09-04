from pathlib import Path

p = Path('app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt')
s = p.read_text()

repls = {
'''                        now - lastBlindTargetTapAt >= DIRECT_TARGET_TAP_INTERVAL_MS\n''': '''                        now - lastBlindTargetTapAt >= RaceTapPolicy.intervalMs(now, predictedSpawnAt)\n''',
'''        if (now - lastBlindTargetTapAt < DIRECT_TARGET_TAP_INTERVAL_MS) return false\n        if (blindTargetTapAttempts >= MAX_DIRECT_TARGET_TAPS) return false\n''': '''        if (now - lastBlindTargetTapAt < RaceTapPolicy.intervalMs(now, predictedSpawnAt)) return false\n''',
'''        private const val DIRECT_TARGET_TAP_INTERVAL_MS = 380L\n''': '',
'''        private const val MAX_DIRECT_TARGET_TAPS = 8\n''': '',
}

for old, new in repls.items():
    count = s.count(old)
    if count == 0:
        raise SystemExit(f'missing patch target: {old!r}')
    s = s.replace(old, new)

if 'MAX_DIRECT_TARGET_TAPS' in s:
    raise SystemExit('MAX_DIRECT_TARGET_TAPS still present')
if 'DIRECT_TARGET_TAP_INTERVAL_MS' in s:
    raise SystemExit('DIRECT_TARGET_TAP_INTERVAL_MS still present')
if s.count('RaceTapPolicy.intervalMs(now, predictedSpawnAt)') < 3:
    raise SystemExit('race tap policy not applied to all direct-tap paths')

p.write_text(s)
