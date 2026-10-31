#!/usr/bin/env python3
"""Checks that Capri v10.44 and the game-control patch are really in the code.
Usage: python3 verify.py <project_root>
Exit code 0 = everything present. 1 = something missing. Paste the full output back."""
import sys, pathlib

root = pathlib.Path(sys.argv[1]).resolve()
J = root / "app/src/main/java/com/dot/capri"

def read(rel):
    p = J / rel
    return p.read_text() if p.exists() else None

# (name, file, literal text that must appear, minimum count)
CHECKS = [
    # v10.44 (controls, loop guards, local screenshot)
    ("ControlRegions.kt exists", "agent/ControlRegions.kt", "object ControlRegions", 1),
    ("press_region action", "agent/PhoneAgent.kt", '"press_region" ->', 1),
    ("calibrate stores regions", "BubbleService.kt", "ControlRegions.set(this", 1),
    ("regions command", "BubbleService.kt", "ControlRegions.names(this)", 1),
    ("same-spot loop guard", "agent/PhoneAgent.kt", "spotRepeats >= 2", 1),
    ("no-change stop after 3", "agent/PhoneAgent.kt", "noChangeStreak >= 3", 1),
    ("local model gets screenshot", "agent/PhoneAgent.kt", "val shot = if (items.size < 5)", 1),
    ("CONTROL REGIONS in prompt", "agent/PhoneAgent.kt", "CONTROL REGIONS:", 1),
    ("local prompt exists", "agent/PhoneAgent.kt", "private val SYSTEM_LOCAL", 1),
    # v10.43 (mood and cleanup)
    ("trigger line used, not mood", "proactivity/ProactivityEngine.kt", "val comment = event.comment", 1),
    ("app-aware mood lines", "proactivity/ProactivityGenerator.kt", "fun generateMoodLine(", 1),
    # game control patch: PhoneControl
    ("dispatchStroke helper", "agent/PhoneControl.kt", "private suspend fun dispatchStroke(", 1),
    ("dragWithHold function", "agent/PhoneControl.kt", "suspend fun dragWithHold(", 1),
    ("look function", "agent/PhoneControl.kt", "suspend fun look(", 1),
    ("stroke chaining", "agent/PhoneControl.kt", "continueStroke(", 1),
    ("clampToScreen exists", "agent/PhoneControl.kt", "fun clampToScreen(", 1),
    ("dispatch exists", "agent/PhoneControl.kt", "suspend fun dispatch(", 1),
    # game control patch: PhoneAgent
    ("Act has holdMs", "agent/PhoneAgent.kt", "val holdMs: Long? = null", 1),
    ("Act has dx", "agent/PhoneAgent.kt", "val dx: Int? = null", 1),
    ("parse hold_ms", "agent/PhoneAgent.kt", 'holdMs = if (o.has("hold_ms"))', 1),
    ("drag branch", "agent/PhoneAgent.kt", '"drag" ->', 1),
    ("look branch", "agent/PhoneAgent.kt", '"look" ->', 1),
    ("spot key includes dx/dy", "agent/PhoneAgent.kt", "${a.dx}|${a.dy}", 1),
    ("repeatable actions", "agent/PhoneAgent.kt", "val repeatable = ", 1),
    ("long actions count as progress", "agent/PhoneAgent.kt", '"press_region", "look", "drag")', 1),
    ("API prompt: look", "agent/PhoneAgent.kt", '"do":"look"', 2),
    ("API prompt: drag", "agent/PhoneAgent.kt", '"do":"drag"', 2),
    ("aim-first rule", "agent/PhoneAgent.kt", "In first-person games, aim first", 1),
]

failed = 0
for name, rel, needle, minimum in CHECKS:
    text = read(rel)
    if text is None:
        print(f"FAIL  {name}: file missing ({rel})")
        failed += 1
        continue
    count = text.count(needle)
    if count >= minimum:
        print(f"PASS  {name}  (found {count})")
    else:
        print(f"FAIL  {name}: expected >= {minimum}, found {count} in {rel}")
        failed += 1

# Duplicates would not compile: each new function must be defined exactly once.
for fn in ["suspend fun dragWithHold(", "suspend fun look(", "private suspend fun dispatchStroke("]:
    text = read("agent/PhoneControl.kt") or ""
    if text.count(fn) > 1:
        print(f"FAIL  duplicate definition: {fn}")
        failed += 1

# Brace balance: a quick proxy for a broken edit.
for rel in ["agent/PhoneControl.kt", "agent/PhoneAgent.kt", "BubbleService.kt", "agent/ControlRegions.kt"]:
    text = read(rel)
    if text is None:
        continue
    opened, closed = text.count("{"), text.count("}")
    if opened == closed:
        print(f"PASS  braces balanced: {rel}")
    else:
        print(f"FAIL  braces unbalanced in {rel}: {{={opened} }}={closed}")
        failed += 1

print()
if failed == 0:
    print(f"ALL CHECKS PASSED ({len(CHECKS)} text checks + duplicate and brace checks)")
    sys.exit(0)
print(f"{failed} CHECK(S) FAILED")
sys.exit(1)
