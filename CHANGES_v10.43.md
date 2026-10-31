# Capri v10.43: changes from the review

Only the points below changed. Everything else in v10.42 is untouched.

## Stops overriding app interactions with moods
- ProactivityEngine.evaluate(): a trigger's own app-specific line is now posted as-is.
  Previously generateComment() replaced it with a mood line.
- Mood-only comments use ProactivityGenerator.generateMoodLine(mood, app): lines are chosen by
  app family (feed, music, chat, web), so they don't repeat the same sentence in every app.
- Recent-line memory raised from 8 to 16.

## Keyword commands removed from the normal path
- BubbleService.processCommand(): without a brain, she says so (noBrainReply).
  IntentParser/IntentExecutor are no longer run from chat.

## Android 14 to 17
- Foreground service: declares only specialUse on API 34+. mediaPlayback removed
  (Android 15+ restricts it, and the app doesn't play media).
- Manifest foregroundServiceType is now "specialUse".
- targetSdk stays at 34. Raising it changes edge-to-edge and background behaviour; test first.

## Agent
- Local on-device model gets SYSTEM_LOCAL (element ids and labels only) and no screenshot.
  The screenshot was the slow step on-device.
- [ACTION:TASK:...] takes everything up to the last ']' (the goal can contain brackets).

## Macros
- Replayed typing no longer presses Enter in every field (submit = false).
