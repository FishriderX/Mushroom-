from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, got {count}: {old[:120]!r}")
    s = s.replace(old, new, 1)

# Node-first path: decor/seedling-only Explore UI must never start carousel work.
replace_once(
    '''        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            return handleDetailNodes(entries, normalized)
        }
        if (isMushroomList(normalized) || normalized.contains("鳥瞰風景")) return false
''',
    '''        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            return handleDetailNodes(entries, normalized)
        }
        if (ExploreScreenRules.isDecorOnlyExplore(normalized)) {
            listContextActive = false
            main.removeCallbacks(listWatchdogKick)
            nextActionAt = SystemClock.elapsedRealtime() + DECOR_GUARD_RETRY_MS
            return true
        }
        if (isMushroomList(normalized) || normalized.contains("鳥瞰風景")) return false
'''
)

# OCR path: check the hard decor guard BEFORE mushroom-list handling.
replace_once(
    '''        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            listContextActive = false
            handleDetailOcr(frame, lines, normalized)
            return
        }
        if (isMushroomList(normalized)) {
            handleMushroomList(frame, lines, normalized)
            return
        }
        if (normalized.contains("飾品一覽") && !normalized.contains("花苗和水果")) {
            goBack(220)
            return
        }
''',
    '''        if (normalized.contains("參加") && normalized.contains("蘑菇")) {
            listContextActive = false
            handleDetailOcr(frame, lines, normalized)
            return
        }
        if (ExploreScreenRules.isDecorOnlyExplore(normalized)) {
            listContextActive = false
            main.removeCallbacks(listWatchdogKick)
            nextActionAt = SystemClock.elapsedRealtime() + DECOR_GUARD_RETRY_MS
            return
        }
        if (isMushroomList(normalized)) {
            handleMushroomList(frame, lines, normalized)
            return
        }
        if (normalized.contains("飾品一覽") && !normalized.contains("花苗和水果")) {
            goBack(220)
            return
        }
'''
)

# Replace the permissive classifier that treated decor + seedlings as a list.
replace_once(
    '''    private fun isMushroomList(normalized: String): Boolean {
        val s = clean(normalized)
        val dailyHeader = s.contains("今天還剩下") && s.contains("蘑菇")
        val exploreBody = s.contains("花苗和水果") &&
            (s.contains("飾品一覽") || s.contains("蘑菇"))
        return dailyHeader || exploreBody
    }
''',
    '''    private fun isMushroomList(normalized: String): Boolean =
        ExploreScreenRules.isMushroomList(normalized)
'''
)

# Add a small wait constant for decor-only frames. This is intentionally a wait,
# not a tap/back action: let Pikmin finish loading the actual mushroom content.
replace_once(
    '''        private const val LIST_STALL_RETRY_MS = 180L
''',
    '''        private const val LIST_STALL_RETRY_MS = 180L
        private const val DECOR_GUARD_RETRY_MS = 350L
'''
)

p.write_text(s, encoding="utf-8")
print("Applied V0.4.1 decor hard-guard fix")
