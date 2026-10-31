# Capri game control patch (on top of v10.44)

Goal: she can look around, drag, hold, double-tap and click in landscape games, using
calibrated regions so she doesn't guess pixels. Built for Minecraft (mining, looking around)
and Plants vs Zombies (card drags, grid placement).

Apply the parts in order. Each part names the file and the exact place to change.

---

## Part 0: what is calibrated, and by whom

- **Calibration is manual.** You type fractions of the screen into chat:
  `calibrate attack 0.90 0.55`. Capri never finds buttons by itself.
- Fractions are 0 to 1, measured from the top-left of the screen, in the orientation you play in.
- Suggested names for Minecraft mobile in landscape:
  - `move` (the left joystick centre), `attack` (the break/mine button), `jump`, `use` (place/use),
    `look` (an empty area on the right where you drag to turn the camera).
- Suggested names for PvZ: use cells for the lawn; calibrate `shovel` and the seed cards if
  you want them without grid guessing.
- Check each one: after calibrating, run `task: press attack for 2 seconds` and watch.

---

## Part 1: Act class (PhoneAgent.kt)

Add four fields to the end of `private class Act(...)`, after `region`:

```kotlin
        val region: String? = null,
        val holdMs: Long? = null,
        val dx: Int? = null,
        val dy: Int? = null
```

In `parseAct(...)`, inside the `Act(...)` constructor call, after `region = ...`, add:

```kotlin
                holdMs = if (o.has("hold_ms")) o.optLong("hold_ms") else null,
                dx = if (o.has("dx")) o.optInt("dx") else null,
                dy = if (o.has("dy")) o.optInt("dy") else null
```

---

## Part 2: PhoneControl.kt, new gesture functions

Add these inside `object PhoneControl`. They use `clampToScreen` and `dispatch`, which already exist.

```kotlin
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

    /**
     * Press at A, hold for holdMs, move to B over moveMs, release.
     * Used for PvZ card drags: pick the card, wait, move to the lawn cell, let go.
     * The finger stays down between the two strokes (StrokeDescription.continueStroke, API 26+).
     */
    suspend fun dragWithHold(ax: Int, ay: Int, bx: Int, by: Int, holdMs: Long, moveMs: Long): Boolean {
        val (sax, say) = clampToScreen(ax, ay)
        val (sbx, sby) = clampToScreen(bx, by)
        val max = GestureDescription.getMaxGestureDuration()
        CapriCursor.moveTo(sax, say)
        CapriCursor.pulse()

        val holdPath = Path().apply { moveTo(sax.toFloat(), say.toFloat()); lineTo(sax.toFloat(), say.toFloat()) }
        val movePath = Path().apply { moveTo(sax.toFloat(), say.toFloat()); lineTo(sbx.toFloat(), sby.toFloat()) }

        val first = GestureDescription.StrokeDescription(
            holdPath, 0L, holdMs.coerceIn(1L, max - 1), /* willContinue = */ true
        )
        if (!dispatchStroke(first)) return false
        val second = first.continueStroke(movePath, 0L, moveMs.coerceIn(50L, max - 1), /* willContinue = */ false)
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
```

Note: `first.continueStroke(...)` is the Android call that chains the two strokes. If the second
stroke is rejected, the finger lifts at the end of the hold. That's the failure to look for in tests.

---

## Part 3: PhoneAgent.kt, the loop guard (IMPORTANT)

v10.44 built the repeat key from `doWhat|id|x|y|region`. That breaks games:
- `look` has no id or coordinates, so every camera turn looks the same. The third turn stops the run.
- Holding a button for a long time is the same `press_region` again and again. The second press stops the run.

Change the spot key and exempt the actions that are supposed to repeat:

Find:

```kotlin
                val spot = "${a.doWhat}|${a.id}|${a.x}|${a.y}|${a.region}"
                if (a.doWhat != "wait" && spot == lastSpot) spotRepeats++ else { spotRepeats = 0; lastSpot = spot }
```

Replace with:

```kotlin
                val spot = "${a.doWhat}|${a.id}|${a.x}|${a.y}|${a.x2}|${a.y2}|${a.region}|${a.dx}|${a.dy}|${a.durationMs}"
                val repeatable = a.doWhat in setOf("wait", "press_region", "hold", "look", "drag", "double_tap")
                if (!repeatable && spot == lastSpot) spotRepeats++ else { spotRepeats = 0; lastSpot = spot }
```

Repeatable actions are still limited by the no-change rule in Part 3b.

### Part 3b: keep the no-change rule for game actions

The no-change check already ignores `hold`, `long_press`, `swipe`, `scroll`, `multitap`, `multi_touch`, `wait`.
Add the new actions to that set, so a long hold isn't counted as "nothing happened":

Find:

```kotlin
                val isContinuousAction = a.doWhat in setOf("hold", "long_press", "swipe", "scroll", "multitap", "multi_touch", "wait")
```

Replace with:

```kotlin
                val isContinuousAction = a.doWhat in setOf(
                    "hold", "long_press", "swipe", "scroll", "multitap", "multi_touch", "wait",
                    "press_region", "look", "drag"
                )
```

---

## Part 4: PhoneAgent.kt, the new action branches

Add these branches inside `when (a.doWhat) { ... }`, directly before `"wait" -> log.add("waited")`:

```kotlin
                    "drag" -> {
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
```

`press_region` (from v10.44) stays as it is. It handles holds up to the length of one gesture. For
longer holds, use `hold` with `duration_ms`, which already chains through `holdAt`.

---

## Part 5: the prompts

In the API prompt `SYSTEM` (the `private val SYSTEM = """ ... """` in PhoneAgent.kt), add these lines
after the `press_region` line:

```
{"do":"drag","cell":"C4","cell2":"F4","hold_ms":300,"duration_ms":600} press cell C4, hold hold_ms, then move to cell F4 over duration_ms and release. Use it for dragging a plant card onto the lawn or moving items.
{"do":"look","dx":120,"dy":-40,"duration_ms":250} turn the camera by dx,dy pixels from the look zone. Positive dx turns right, negative turns left, positive dy turns down. Use it to aim at a block or a target.
```

Add these rules under `Direct Instructions:`:

```
- In first-person games, aim first: use look until the target is at the centre of the screen, then press_region attack. Never press attack while the target is off-centre.
- Mining or breaking a block: press_region attack with a duration long enough to finish, then check the screen. Do not repeat attack in short bursts.
- Placing cards in PvZ: use drag from the card cell to the lawn cell.
- Keep CONTROL REGIONS names exactly as listed.
```

In `SYSTEM_LOCAL`, add these lines after the `press_region` line:

```
{"do":"look","dx":120,"dy":-40}
{"do":"drag","cell":"C4","cell2":"F4"}
```

---

## Part 6: tests, in this order

Stop at the first failure and send the `CAPRI_AGENT` lines for it.

1. **Calibration:** `calibrate attack 0.9 0.55` then `regions`. It should list `attack`.
2. **Hold:** `task: press attack for 3 seconds`. The log should say `pressed attack for 3000ms`, and the
   action should last about 3 seconds.
3. **Look:** `task: turn right a little`. The camera should turn right. If it turns left, check the
   sign of `dx` in the response; the code does not invert it.
4. **Look repeatedly:** `task: look around`. She should turn several times and not stop after the third turn.
5. **Long hold:** `task: hold attack for 20 seconds`. It should hold the whole time, with no release in between.
   If it releases after the first stroke, the `continueStroke` chain is not working on your device. Report it.
6. **Drag:** in PvZ, `task: put a sunflower on the first lawn cell`. The card should stay picked up for
   the hold, then move to the cell.
7. **No loops:** `task: mine that block` in a game where nothing happens. It should try a few
   times and then stop with a message, not repeat forever.

---

## Known limits (not solved here)

- **Reacting during a hold.** A hold is one long gesture. She doesn't look at the screen until it ends.
  For "hold until the block breaks", she needs short holds with a check in between. That is the next
  phase: chained strokes with a screen check every 1 to 2 seconds. It is not in this patch.
- **Relative camera control is approximate.** `look` moves the camera by a fixed pixel amount.
  Sensitivity differs between phones and game settings. Tune the amounts in the prompt or add a
  `look_scale` setting.
- **Speed.** Each decision costs one model call, usually several seconds. Real-time play needs
  the reaction loop above.
- **Continuous gestures across strokes are not verified** on Android. `continueStroke` is the
  documented way to do this, but the gap between two dispatched strokes may be visible in some games.
