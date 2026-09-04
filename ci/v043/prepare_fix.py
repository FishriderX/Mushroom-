from pathlib import Path

p = Path("ci/v043/fix.py")
s = p.read_text(encoding="utf-8")
start_marker = '''replace_once(\n    \'\'\'        private const val PREDICTION_SWEEP_INTERVAL_MS = 1_500L'''
end_marker = '''\np.write_text(s, encoding="utf-8")'''
start = s.find(start_marker)
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("could not locate v043 constants patch block")
replacement = '''replace_once(\n    \'\'\'        private val FULL_TEAM_REGEX = Regex("(\\\\d{1,3})/\\\\1")\n\'\'\',\n    \'\'\'        private const val TARGET_MAP_CENTER_X = 0.50f\n        private const val TARGET_MAP_CENTER_Y = 0.46f\n        private const val TARGET_MAP_ANCHOR_RADIUS_NORM_SQ = 0.040f\n        private val GO_TO_MAP_TEXTS = listOf("前往這裡", "前往此處", "前往該處")\n        private val FULL_TEAM_REGEX = Regex("(\\\\d{1,3})/\\\\1")\n\'\'\'\n)\n'''
s = s[:start] + replacement + s[end:]
p.write_text(s, encoding="utf-8")
print("Prepared V0.4.3 patch script for current V0.4.2 source")
