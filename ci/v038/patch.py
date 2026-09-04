from pathlib import Path

p = Path("app/src/main/java/com/example/pikminhelper/automation/AutonomousRaceServiceV4.kt")
s = p.read_text(encoding="utf-8")
old = '''    private val listWatchdogKick = object : Runnable {\n'''
new = '''    private val listWatchdogKick: Runnable = object : Runnable {\n'''
count = s.count(old)
if count != 1:
    raise SystemExit(f"expected one watchdog declaration, found {count}")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")
print("Applied explicit Runnable type for V0.3.7 watchdog")
