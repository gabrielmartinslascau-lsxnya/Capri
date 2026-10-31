#!/usr/bin/env python3
"""Applies the game-control patch on top of Capri v10.44. All-or-nothing.
Usage: python3 apply.py <project_root>"""
import re, sys, pathlib

root = pathlib.Path(sys.argv[1]).resolve()
J = root / "app/src/main/java/com/dot/capri"
files = {}

def load(rel):
    if rel not in files:
        p = J / rel
        if not p.exists():
            sys.exit(f"NOT APPLIED: missing file {rel}. Nothing was written.")
        files[rel] = p.read_text()
    return files[rel]

def replace_once(rel, old, new, label):
    s = load(rel)
    if new.strip() in s:
        print("already present:", label)
        return
    n = s.count(old)
    if n != 1:
        sys.exit(f"NOT APPLIED ({label}): anchor found {n} times in {rel}. Nothing was written.")
    files[rel] = s.replace(old, new)
    print("applied:", label)

# 1) New gesture helpers, inserted at the top of object PhoneControl.
HELPERS = '''
    /** One stroke, dispatched on its own. Used for strokes that must continue into the next one. */
    private suspend fun dispatchStroke(stroke: GestureDescription.StrokeDescription): Boolean {
        val s = service() ?: return false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val accepted = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }
    }

    /** Press at A, hold holdMs, move to B over moveMs, release. The finger stays down between strokes. */
    suspend fun dragWithHold(ax: Int, ay: Int, bx: Int, by: Int, holdMs: Long, moveMs: Long): Boolean {
        val (sax, say) = clampToScreen(ax, ay)
        val (sbx, sby) = clampToScreen(bx, by)
        val max = GestureDescription.getMaxGestureDuration()
        CapriCursor.moveTo(sax, say)
        CapriCursor.pulse()
        val holdPath = Path().apply { moveTo(sax.toFloat(), say.toFloat()); lineTo(sax.toFloat(), say.toFloat()) }
        val movePath = Path().apply { moveTo(sax.toFloat(), say.toFloat()); lineTo(sbx.toFloat(), sby.toFloat()) }
        val first = GestureDescription.StrokeDescription(holdPath, 0L, holdMs.coerceIn(1L, max - 1), true)
        if (!dispatchStroke(first)) return false
        val second = first.continueStroke(movePath, 0L, moveMs.coerceIn(50L, max - 1), false)
        return dispatchStroke(second)
    }

    /** Turns the camera: a drag from (zoneX, zoneY) by (dx, dy) pixels. */
    suspend fun look(zoneX: Int, zoneY: Int, dx: Int, dy: Int, ms: Long = 250L): Boolean {
        val (sx, sy) = clampToScreen(zoneX, zoneY)
        val (ex, ey) = clampToScreen(zoneX + dx, zoneY + dy)
        CapriCursor.moveTo(sx, sy)
        CapriCursor.pulse()
        val path = Path().apply { moveTo(sx.toFloat(), sy.toFloat()); lineTo(ex.toFloat(), ey.toFloat()) }
        return dispatch(path, ms)
    }
'''
replace_once("agent/PhoneControl.kt", "object PhoneControl {\n", "object PhoneControl {\n" + HELPERS,
             "gesture helpers in PhoneControl")

# 2) Act gets holdMs, dx, dy.
replace_once("agent/PhoneAgent.kt",
"        val region: String? = null\n    )",
"        val region: String? = null,\n        val holdMs: Long? = null,\n        val dx: Int? = null,\n        val dy: Int? = null\n    )",
"Act fields holdMs/dx/dy")

# 3) parseAct reads them.
replace_once("agent/PhoneAgent.kt",
'                region = o.optString("region").ifEmpty { null }\n            )',
'                region = o.optString("region").ifEmpty { null },\n'
'                holdMs = if (o.has("hold_ms")) o.optLong("hold_ms") else null,\n'
'                dx = if (o.has("dx")) o.optInt("dx") else null,\n'
'                dy = if (o.has("dy")) o.optInt("dy") else null\n            )',
"parseAct reads hold_ms/dx/dy")

# 4) Loop guard: spot key includes the new fields, and long actions may repeat.
replace_once("agent/PhoneAgent.kt",
'                val spot = "${a.doWhat}|${a.id}|${a.x}|${a.y}|${a.region}"\n'
'                if (a.doWhat != "wait" && spot == lastSpot) spotRepeats++ else { spotRepeats = 0; lastSpot = spot }',
'                val spot = "${a.doWhat}|${a.id}|${a.x}|${a.y}|${a.x2}|${a.y2}|${a.region}|${a.dx}|${a.dy}|${a.durationMs}"\n'
'                val repeatable = a.doWhat in setOf("wait", "press_region", "hold", "look", "drag", "double_tap")\n'
'                if (!repeatable && spot == lastSpot) spotRepeats++ else { spotRepeats = 0; lastSpot = spot }',
"loop guard: spot key and repeatable actions")

# 5) Long actions count as progress, not as "no change".
s = load("agent/PhoneAgent.kt")
pat = re.compile(r'(val isContinuousAction = a\.doWhat in setOf\()[^)]*\)')
if '"press_region", "look", "drag")' in s:
    print("already present: continuous actions")
elif len(pat.findall(s)) != 1:
    sys.exit("NOT APPLIED (continuous actions): anchor not found exactly once. Nothing was written.")
else:
    files["agent/PhoneAgent.kt"] = pat.sub(
        r'\g<1>"hold", "long_press", "swipe", "scroll", "multitap", "multi_touch", "wait", "press_region", "look", "drag")',
        s)
    print("applied: continuous actions")

# 6) New branches, placed before the wait branch.
BRANCHES = '''                    "drag" -> {
                        val fx = a.x; val fy = a.y; val tx = a.x2; val ty = a.y2
                        if (fx == null || fy == null || tx == null || ty == null) {
                            log.add("drag: missing coordinates (use cell, cell2 or x,y,x2,y2)")
                        } else {
                            CapriCursor.setStatus("dragging...")
                            val ok = PhoneControl.dragWithHold(
                                fx, fy, tx, ty,
                                holdMs = a.holdMs ?: 300L,
                                moveMs = a.durationMs ?: 600L
                            )
                            log.add("dragged ($fx,$fy) to ($tx,$ty)" + if (ok) "" else " (failed)")
                        }
                    }
                    "look" -> {
                        CapriCursor.setStatus("looking...")
                        val dx = a.dx ?: 0
                        val dy = a.dy ?: 0
                        val origin: Pair<Int, Int> = ControlRegions.resolve(context, "look")
                            ?: PhoneControl.screenSize()?.let { (w, h) -> Pair(w * 3 / 4, h / 2) }
                            ?: Pair(900, 600)
                        val ok = PhoneControl.look(origin.first, origin.second, dx, dy, a.durationMs ?: 250L)
                        log.add("looked ($dx,$dy)" + if (ok) "" else " (failed)")
                    }
'''
replace_once("agent/PhoneAgent.kt", '                    "wait" -> log.add("waited")',
             BRANCHES + '                    "wait" -> log.add("waited")', "drag and look branches")

# 7) API prompt: the two new actions, after the press_region line.
s = load("agent/PhoneAgent.kt")
anchor = "press a calibrated CONTROL REGION"
i = s.find(anchor)
if i < 0:
    sys.exit("NOT APPLIED (API prompt): press_region line not found. Nothing was written.")
line_end = s.index("\n", i)
ACTIONS = ('{"do":"drag","cell":"C4","cell2":"F4","hold_ms":300,"duration_ms":600} press cell C4, hold hold_ms, '
           'move to cell F4 over duration_ms, release. Use for dragging a plant card onto the lawn or moving items.\n'
           '{"do":"look","dx":120,"dy":-40,"duration_ms":250} turn the camera by dx,dy pixels from the look zone. '
           'Positive dx turns right, negative left, positive dy turns down. Use it to aim at a block or target.\n')
if '"do":"look"' in s:
    print("already present: API prompt actions")
else:
    files["agent/PhoneAgent.kt"] = s[:line_end + 1] + ACTIONS + s[line_end + 1:]
    print("applied: API prompt actions")

# 8) API prompt: aim-first rules, after the first "Rules:" inside SYSTEM.
s = load("agent/PhoneAgent.kt")
sys_start = s.find('private val SYSTEM = """')
rules_at = s.find("Rules:\n", sys_start) if sys_start >= 0 else -1
RULES = ('- In first-person games, aim first: use look until the target is at the centre of the screen, '
         'then press_region attack. Never press attack while the target is off-centre.\n'
         '- Mining or breaking a block: press_region attack with a duration long enough to finish, then check the screen. '
         'Do not repeat attack in short bursts.\n'
         '- Placing cards in PvZ: use drag from the card cell to the lawn cell.\n'
         '- Use CONTROL REGIONS names exactly as listed.\n')
if "In first-person games, aim first" in s:
    print("already present: aim-first rules")
elif rules_at < 0:
    sys.exit("NOT APPLIED (rules): 'Rules:' not found in SYSTEM. Nothing was written.")
else:
    cut = rules_at + len("Rules:\n")
    files["agent/PhoneAgent.kt"] = s[:cut] + RULES + s[cut:]
    print("applied: aim-first rules")

# 9) Local prompt: look and drag.
s = load("agent/PhoneAgent.kt")
local_anchor = '{"do":"press_region","region":"attack","duration_ms":3000}'
if s.count(local_anchor) != 1:
    sys.exit(f"NOT APPLIED (local prompt): anchor found {s.count(local_anchor)} times. Nothing was written.")
if '{"do":"drag","cell":"C4","cell2":"F4"}' in s:
    print("already present: local prompt actions")
else:
    files["agent/PhoneAgent.kt"] = s.replace(local_anchor,
        local_anchor + '\n{"do":"look","dx":120,"dy":-40}\n{"do":"drag","cell":"C4","cell2":"F4"}', 1)
    print("applied: local prompt actions")

# Brace balance before anything is written.
for rel, text in files.items():
    if text.count("{") != text.count("}"):
        sys.exit(f"NOT APPLIED: braces unbalanced in {rel} after edits. Nothing was written.")

for rel, text in files.items():
    (J / rel).write_text(text)
print("\nAPPLIED. Now run verify.py again.")
