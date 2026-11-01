# Capri

A small, cute AI that lives on your Android phone as a floating bubble (or at a click of a button). She chats, nags you (nicely) when you've been doomscrolling for an hour, and if you let her, she'll use your phone for you: open apps, scroll, tap, type, play a song from your library.

Made by me! [@gabrielmartinslascau-lsxnya](https://github.com/gabrielmartinslascau-lsxnya). Every line of code is public in this repo, so you can read exactly what she does.

> **Heads up:** Capri is sideloaded (not on the Play Store, obviously, since you're literally on GitHub right now reading this), needs some sensitive permissions, and phone control is still a work in progress. Read [Limitations](#limitations) before you install.

---

## Versions

| Version | Status | What's in it |
|---|---|---|
| **V6** | Released, the one most people have | Bubble, chat, reminders, cloud AI. Basic device commands. |
| **V10.x** | In development, not released yet | New phone agent with an on-screen cursor, on-device model option, better moods and reminders, game controls (still rough, it's in development, so what did you expect?). |

Still on V6? Totally fine! But V10 is where most of the work went. When a V10 release goes up, it'll be listed here.

### Why V6, and why 10.45

The first thing that got a public number was V6. Before that there were around 300 commits, and a lot of them were "okay, this works now" moments. Then every new feature broke something else, so every time the code changed the version went up by 0.1. That's how we got to 10.45. Nobody has ever been sure what 10.45 means, including me. Version numbers are a feeling at this point.

---

## What she does

- **The bubble.** A little dot that floats over other apps. Tap it to chat, drag it wherever.
- **Chat.** Ask her stuff, get help writing, or just talk.
- **Phone control.** Ask for something that takes a few steps ("open settings and turn on dark mode", "scroll down", "search Google for pizza", "go to the gallery and find a funny picture", or even the secret surprise... "impress me", and she WILL go rogue and do whatever she wants on your phone) and she takes over the screen. You'll see a cursor move and a STOP button at the top with an animation and her current state. Risky stuff like sending a text or deleting something makes her ask you first (sometimes she doesn't give a f\*\*\* and just does it anyway).

She's an agent, so she does what you ask on your screen. Some people will find that creepy. We think of it as an agent doing what it's told. The catch is that anything on screen while a task runs is part of that task, including photos, messages and notifications. See [Privacy](#privacy).

- **Reminders.** She watches which app is open and how long you've been in it. Doomscrolling a short-video app for too long? She'll say something about it. The line is about the app you're actually in, not a random quote.
- **Moods.** She can be pleased, neutral, lonely or judging, depending on how you use your phone. The mood only changes the lines she says when nobody's asked her anything. It never overrides a reminder about the app you're on.
- **Memory (this session only, for now).** While the app is running, she keeps the chat in memory, so she can follow what you said a few messages ago. Close the app or force-stop it and it's gone. Nothing is written to disk yet, so she won't remember you after a restart. A long-term memory ("remember that...") is planned, not in the build.
- **Macros.** Record a sequence of taps in an app (`record check my repos`, do the steps, `stop recording`), then just say the keyword to replay it.
- **Music.** She can search your local music library by title or artist and play it, or open your streaming apps.
- **Notification summary (optional).** If you give her notification access, she can tell you what's in your shade.
- **Default assistant (where supported).** On some phones she can be set as the default assistant, so the Power button can open her.

---

## How she thinks

She has two ways to answer, and you pick what fits you:

1. **Cloud AI.** Gemini, Anthropic or OpenRouter, with your own API key. This is the smartest option and the one that handles phone control best.
2. **On-device model.** Google's Gemma, downloaded once and run on your phone, so it works without internet. More on this below.

If your phone has internet and a key, she uses the cloud. If not, she falls back to the on-device model. If neither is set up, she tells you no brain is available and stops there.

> **Note:** offline commands like `open calculator` ran with plain rules and no AI at all in older builds. They aren't in V10 yet. The plan is to bring them back as a switch you turn on. The "say 'open <app name>'" hint in the no-brain message is a leftover, and it doesn't do anything in V10 right now.

### The on-device model (Gemma)

- Model: **Gemma 4 E2B**, the `.litertlm` file, about **2.6 GB**.
- Download it from the settings screen, on Wi-Fi, if you can.
- **Keep the app open until the download finishes.** If you close it or the phone sleeps, the download can stop and leave a broken file. If that happens, delete it and try again.
- It's slower than the cloud model and not as smart, but it works offline and it's free.

### Phone control, honestly

- With an API key that still has quota, it's fast enough to be useful. Portrait mode works well most of the time.
- When you run out of quota, things slow down or stop. That's the provider's limit, not something Capri can fix.
- It can still misread a screen or tap the wrong thing. Hit STOP if she goes off the rails.

---

## Personality

Capri is meant to feel like a small, slightly chaotic friend who lives in your phone, not a tool with a voice.

- Lowercase, playful, a bit of teasing, the odd `~`, a stutter when she gets flustered, and the occasional *choom*. (If you don't know what *choom* means, look up Cyberpunk.)
- Loyal to you, not scared of you. She says "I don't know" when she doesn't know.
- Her lines change with the app you're in. A feed sounds different from a chat app or a browser.
- She won't pretend she can do something she can't. If a permission is off, she'll tell you which setting it is.

The name and the vibe come from the chaotic Capri in *Cyberpunk: Edgerunners 2*. The code and the lines are original.

---

## Installing

You need Android 10 (API 29) or newer.

1. Grab the APK from [Releases](../../releases), or build it yourself in Android Studio (don't, seriously, don't recommend getting into this mess unless you want to contribute).
2. Let your phone install from that source.
3. Open Capri and follow the health screen. It shows each permission and how to turn it on.
4. **Android 13 and newer:** sideloaded apps get blocked from some settings by default. Go to *App info → Capri → ⋮ → Allow restricted settings*, then turn on the permission.
5. Pick your brain: add an API key, or download the on-device model. Offline commands aren't in V10 yet.

---

## Permissions

Capri asks for a few sensitive ones. Here's what each is for:

| Permission | Why | Can I say no? |
|---|---|---|
| Display over other apps | The bubble and the cursor | Yes, but no bubble without it |
| Accessibility service | Reading the screen and tapping, scrolling and typing during tasks | Yes. Phone-control tasks stop working |
| Notification access | Summarising your notification shade (optional) | Yes |
| Usage access | Knowing which app is open, for reminders | Yes. Reminders stop |
| Contacts | Calling and texting people by name | Yes |
| Audio / media read | Finding songs in your music library | Yes. Music search stops |
| Internet | Talking to the cloud AI you picked | Yes. Only the on-device model works offline |

Capri doesn't browse your files. She only searches your music library by name. The accessibility service reads the screen only while a task is running, so anything you open during a task (photos included) can be seen. It's designed not to read password fields.

---

## Privacy

- No account, no analytics, no developer backend. Nothing is sent to me.
- Your API key stays on your phone. It only goes to the provider you picked.
- **Nothing is written to disk for chat right now.** The chat lives in the app's memory while it's running. Force-stop the app, restart the phone, or clear the app data, and it's gone. Nothing gets "safely deleted" because nothing was saved in the first place.
- While the app runs, each request to your cloud provider includes the recent chat so she can follow the conversation. That's what leaves the phone. On the on-device model, nothing leaves the phone.
- **Screenshots for phone tasks go to your cloud provider** when a task needs them. A screenshot shows whatever is on screen at that moment: photos, messages, notifications, anything. Don't run a task while sensitive stuff is open.
- The AI provider doesn't get to see anything you haven't sent in a request. Check each provider's own policy for how long they keep requests.
- Don't want a trace? Force-stop the app, or uninstall it.

Every line of code is public. If you're worried about something, read it. If you find something sketchy, open an issue. Nothing is hidden. Nothing is guaranteed safe either, though. Don't trust what random people say, including me, so keep your own judgement on.

---

## Limitations

- **Phone control isn't always reliable (almost never is, actually).** It can tap the wrong thing, scroll the wrong way, or get stuck. STOP is there for that, and sometimes you can't even tap STOP, so you'll have to force-close it.
- **It reads what's on screen during a task.** If you ask her to find a picture in your gallery, she'll scroll and open pictures to look. That's the point of an agent, and it's also why you should watch what's on screen.
- **Chat is not remembered across restarts.** Long-term memory isn't in the build yet.
- **It depends on quota.** Cloud AI is fast until you run out. Then it's slow or it stops. You can set limits and keep track of which models you use, request latency, token usage and quotas.
- **The on-device model is slower and weaker** than the cloud one. Fine for chat and simple stuff.
- **Games are experimental.** Mining (Minecraft) and camera control are still being worked on: clicking, dragging, holding and so on.
- **Some phones or launchers hide the cursor overlay.** If the cursor never shows up, that's the reason, not your phone being broken.
- **It's sideloaded.** Only install from sources you trust, and check the code if you're not sure.
- **Battery.** Long tasks and the on-device model use more battery. The model itself isn't a heater: it's slow rather than heavy.

---

## What's different

- **It's built for the phone.** It's not a desktop agent shrunk to fit a small screen.
- **No server, no account.** You bring your own key, or you use the on-device model. There's no backend and no login.
- **Reminders tied to the app.** It's not a generic timer. A doomscroll reminder comes from the app that's actually open.
- **Moods don't override.** The mood changes the unprompted lines, never the app-specific reminders.
- **You can read everything.** Every line is on GitHub. Fork it, check it, tell me what's broken, or suggest something. It's hard being a solo dev and working at the same time.
- **Offline commands** (planned switch, see above). The goal is that she still does simple things with no internet at all.

---

## Where the idea came from

Capri was started from scratch in this repo. It's not a fork or a port of another project.

The idea of a small AI with a cursor and a personality came from an early video of a Windows version of Capri, a desktop project that's now popular (200+ GitHub stars). That video showed that a little AI with a cursor can feel like someone is there, not just a chat box. This phone version is a separate codebase. The Android code, the reminders, the phone agent and the on-device model setup were all written here. It's built around how a phone actually gets used: short sessions, notifications, app switching, a 6-inch screen, and the thing you carry everywhere.

---

## How AI was used to build it

Capri was built with several AI tools, not just one. Quota limits were a constant problem, so when one model ran out, I'd switch to another for debugging or code fixes. I'll explain which ones and why before you call it "vibecoded".

The tools I used:
- **Claude** (Anthropic), for planning, reviewing code, and writing most of the patches and "adding" features (rough code in patches or zip files, which I then copy, recreate, alter and review),
- **Gemini** (Gemini 3.5 and 3.6 Flash Lite), for the actual code: pasting a big block of code for them to apply in the right places, then auto-committing and running the app to check for compile errors in Android Studio,
- **DeepSeek R1**, for small sketches and ideas, not real code,
- **Kimi** (version not sure), the first AI I used. I gave up on it because of slow response times, but it's the one that got "v1" working with CMD-based responses and actions.

How it actually went:

- **I decided what the app should do.** Features, personality, what she should and shouldn't do, and what "working" means. The AIs don't get to make those calls.
- **The AIs wrote code and looked at it.** They drafted changes and pointed out problems. A lot of their suggestions were wrong, and I threw them out.
- **I tested everything on my phone, every day.** Most bugs were found by running the app and watching what she did. When a fix didn't work, I went back and asked again with the logs.
- **I read the code.** I can explain what each part does. If I didn't understand a change, I didn't keep it.
- **Some parts are still experimental.** The phone control and the game controls work for me, but not on every device or app. They're marked that way.

This wasn't "generate it and ship it". The AIs were tools, like a search engine or a linter. The decisions, the testing and the responsibility are mine.

---

## Contributing

Issues and pull requests are welcome, especially:
- bug reports with your phone model and Android version,
- phrases that go to the wrong place,
- reminder lines for apps you use (the more specific, the better).

Please don't ask for a Play Store release, a desktop version or a hosted server. Not happening, and I couldn't authorise it anyway.

---

## License

See [LICENSE](LICENSE).
