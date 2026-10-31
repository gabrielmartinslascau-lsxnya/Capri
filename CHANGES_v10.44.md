# Capri v10.44: game control

## Why she clicked randomly, repeated, and never held
1. A stuck action repeated forever. The no-change check reset after four identical steps
   ("never give up on static screens"), so a loop only ended when the step budget did.
   -> Same action on the same spot twice in a row stops the run. Three steps with no change stop the run.
2. The local model was blind. v10.43 stopped sending it screenshots, so in a game it only had
   an empty element list. -> Screenshot restored for the local model.
3. The grid is 6 columns by 10 rows. A block in a game is far smaller than a cell.
   -> Named control regions (below) are the precise option; cells stay for coarse targets.
4. Holds were fixed-length strokes. A hold is now a named action with a duration.

## New
- `press_region` action: holds or taps a calibrated button for `duration_ms`.
  Holds above 400 ms use a single long stroke (the system caps one gesture at its
  maximum duration; longer holds need several strokes, not added here).
- Calibrate buttons from chat, as fractions of the screen:
    calibrate attack 0.90 0.55
    calibrate jump 0.92 0.85
    calibrate move 0.15 0.70
  `regions` lists them. Calibrate in the orientation you play in.
- The prompt lists the calibrated regions, so the model picks names instead of pixels.

## Not in this version
- Controller or keyboard injection. A normal app can't inject key events. It needs
  Shizuku, root, or an adb shell. Buttons (KEYCODE_BUTTON_A, DPAD) can be sent with
  `input keyevent`; analogue sticks need a virtual device (uinput) and root.
  Not applied: it depends on the user's device setup and I can't test it here.
- Only fixed-position controls. Minecraft's first-person look (turning the camera)
  needs drags relative to the current view, which is not implemented.
