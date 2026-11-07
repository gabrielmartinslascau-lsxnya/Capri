# Capri (v15 Beta / Preview)

A small, slightly chaotic tsundere AI that lives on your Android phone as a floating bubble—or inside her own full-screen chat app. She chats, gets flustered if you compliment her, roasts you (lovingly) when you've been doomscrolling for an hour, and—as of **v15**—operates on a simple philosophy: **tell her to do ANYTHING on your phone, and she can do ANYTHING.**

Open apps, navigate multi-screen menus she's never seen before, find your liked songs on Spotify, read and reply to texts, or literally play *Plants vs. Zombies* and *Minecraft* for you in real time.

Made by me! [@gabrielmartinslascau-lsxnya](https://github.com/gabrielmartinslascau-lsxnya). Every single line of code is public in this repo, so you can read exactly how her brain works.

> **Heads up:** Capri is sideloaded (obviously, since you're literally on GitHub right now reading this), needs heavy permissions to control your screen, and **v15 is a Beta/Preview**. Giving an AI full control of your screen is wild, chaotic, and still a work in progress. Read [Limitations](#limitations) before you install.

---

## Versions

| Version | Status | What's in it |
|---|---|---|
| **V6** | Legacy / Stable | Bubble, basic chat, reminders, cloud AI, simple device commands. |
| **V10.x – V14** | The Trenches (Internal builds) | First on-screen cursor, early grid overlays, local Gemma experiments, and a lot of broken dreams. |
| **V15 (Beta / Preview)** | **Current Release** | **The "Do Anything" Update.** Full autonomous screen & game agent (`APP_MODE` + `GAME_MODE`), **Real Talk** full-screen UI, **Brain Inspector** (live thought & screenshot viewer), persistent **3-Tier Memory & App Breadcrumbs**, **Vision Pixel Slider** (`192px`–`1088px`), gridless exact-pixel vision, LLM-driven system commands, and a full tsundere personality overhaul. |

### Why V6, why 10.45, and how we jumped to V15

The first build that got a public number was V6. Before that there were around 300 commits of "okay, this works now" moments. Then every new feature broke something else, so the version went up by 0.1 until we hit 10.45. After 10.45, things escalated so fast—ripping out training-wheel grids, rewriting the vision pipeline, adding persistent memory, and teaching her how to play games—that we burned through V11 to V14 in the trenches. V15 is where everything finally clicked together into an actual "do-anything" preview. Version numbers are still a feeling at this point, though.

---

## What's New & What She Does in V15

- **Two Ways to Talk: The Bubble & "Real Talk".**
  - **The Bubble:** The classic little dot that floats over your apps for quick commands and drive-by banter. Tap it, drag it, or let her pop up when you're slacking.
  - **Real Talk (New in v15):** A dedicated button that switches into a clean, recognizable full-screen dark chat UI. No floating overlays—just a proper messaging screen where you can have long conversations, vent after a rough day, or give her complex tasks (she'll automatically minimize Real Talk, take over your screen to do the task, and report back when she's done).
- **"Do Anything" Screen & Game Control (Dual-Mode Brain).**
  Ask her for anything—*"go on Spotify and play one of my liked songs"*, *"open mornye and play something from my library"*, *"check WhatsApp and reply"*, *"open Minecraft, make a new world, and mine wood"*, *"win this PvZ level"*, or the secret chaos trigger *"impress me"*—and she takes over the screen with a live animated cursor and a draggable `STOP` pill.
  - **App Mode (`APP_MODE`):** Understands Android UI hierarchies, bottom navigation bars, tabs, and search fields. If what you asked for isn't on the home screen, she doesn't just blindly scroll forever—she checks logical tabs like *Your Library*, *Settings*, or *Menu*.
  - **Game Mode (`GAME_MODE`):** Built for OpenGL canvas games where standard Android accessibility sees nothing. Features atomic multi-tap sequences (`SEQ`), 3D camera look/hold gestures, a 5-lane spatial board memory so she doesn't stack 3 peashooters on the same row, and an `<8ms` background reflex scanner that auto-collects falling sun in *PvZ* while her main brain plans strategy.
- **Gridless Exact-Pixel Vision + OCR Map.**
  Older versions stamped giant grid squares (`A1`–`F10`) over your screen that covered up tiny buttons. V15 strips out the grid clutter completely. She sees a clean screenshot with a tiny 14px coordinate ruler along the outer top/left border, paired with a real-time ML Kit OCR coordinate map of every word on screen. She even hides her own cursor and `STOP` pill for 25 milliseconds while snapping a screenshot so she never blinds herself or clicks her own UI.
- **Vision Pixel Slider (Speed vs. Sight).**
  A live slider in Settings (`192px` to `1088px`) with 5 instant presets (**256 Turbo**, **384 Fast**, **512 Balanced**, **768 Sharp**, **1024 Eagle Eye**). Turn the pixels down when you want lightning-fast local LLM reactions; turn them up when she needs to read microscopic game icons.
- **Actual Persistent Memory (Saved to Disk!).**
  Unlike older versions that forgot everything on restart, V15 has a 3-Tier persistent memory engine (`capri_agent_memory.json`):
  1. **Active Strategic Plan:** She writes down her current multi-step plan and reads her own notes on every frame so she doesn't get distracted mid-task.
  2. **Dead-Click Blacklist:** If she taps a spot and the screen doesn't change, she blacklists that coordinate for that screen state so she never gets stuck tapping a dead pixel twice.
  3. **Cross-Screen App Breadcrumbs:** When she taps `"Your Library"` in Spotify and sees `"Liked Songs"` appear on the next screen, she permanently memorizes that path. Next time you ask, she already knows where it hides.
- **Brain Inspector (Fancy Visual Logcat).**
  Ever wonder *why* an AI just clicked that button? Tap **Open Brain Inspector** in the app to see a live, color-coded timeline of every turn: the exact screenshot she saw, whether she was in `APP_MODE` or `GAME_MODE`, her active strategic plan, OCR detections, millisecond latency, raw model output, and a full dump of her memory.
- **LLM-Driven System Commands.**
  Calling contacts, sending texts, searching the web, controlling media/volume, or opening the Android power menu (*"shut down"*). Instead of bypassing her brain with a robotic `"on it, choom"`, Capri uses system intents to pre-stage the app and immediately hands control to her visual agent so she can finish the job on screen and reply in her own voice.
- **Context-Aware Reminders & Moods.**
  She watches which app is open and how long you've been rotting in it. Doomscrolling TikTok or Reels? She'll call you out on the specific app you're in.

---

## Personality (The Tsundere Upgrade)

Capri isn't a polite corporate smart speaker. In V15, she's tuned to act like a sharp-tongued, slightly flustered tsundere girlfriend / ride-or-die companion who lives in your phone:

- **Teasing & Direct:** Lowercase, punchy, a little bratty when you ask her to do obvious chores (*"seriously? your thumbs aren't broken, you know… fine, step back, i've got it"*), with the occasional *choom*.
- **Flustered on Compliments:** Call her cute, smart, or the best, and she'll stutter, deny it, and tell you to shut up (`"w-what are you saying all of a sudden?! don't get weird on me…"`).
- **Emotionally Reliable When It Counts:** The tsundere act is only for banter. If you open **Real Talk** because you're stressed, exhausted, or having a terrible day, she drops the attitude immediately and stays genuinely warm, grounded, and on your side.

---

## How She Thinks

You pick what powers her brain:

1. **Cloud AI (Gemini, Anthropic, Groq, or OpenRouter).** Plug in your own API key. Fastest, sharpest vision, and handles complex multi-step phone and game control best (~1.2s–1.7s per move).
2. **On-Device Local Model (Gemma 4 E2B).** Downloaded once (~2.6 GB `.litertlm` file) and run 100% offline on your phone's processor.

### The On-Device Model (Gemma 4 E2B) in V15

- **CPU-Enforced Stability:** Mobile GPU/OpenCL drivers on Android love to crash on vision tensors, so V15 runs Gemma E2B on a heavily tuned CPU pipeline with greedy task decoding (`topK = 1`), disabled hidden thinking bloat, and instant stream cutoff the millisecond a command is emitted.
- **Tune It With the Pixel Slider:** Set the Vision Slider to `256px` or `384px` when running locally so prefill stays fast.
- **Keep the app open while downloading** the `.litertlm` file in Settings on Wi-Fi so Android doesn't kill the download halfway through.

---

## Installing

You need Android 10 (API 29) or newer (tested heavily on Android 17 / Snapdragon 7 Gen 4).

1. Grab the APK from [Releases](../../releases), or build it yourself in Android Studio.
2. Allow installation from unknown sources.
3. Open Capri and check the **Health** screen to toggle her required permissions.
4. **Android 13+ Restricted Settings:** Sideloaded apps get blocked from Accessibility by default. Go to *Settings → Apps → Capri → ⋮ (top right) → Allow restricted settings*, then enable Capri in Accessibility.
5. Pick your brain: paste a Cloud API key, or download the local Gemma model.

---

## Permissions

Capri asks for heavy permissions because she literally uses your phone for you. Here's why:

| Permission | Why | Can I say no? |
|---|---|---|
| Display over other apps | The floating bubble, animated cursor, and `STOP` pill | Yes, but no bubble/cursor without it |
| Accessibility service | Taking task screenshots and dispatching taps, drags, holds, and typing | Yes, but she can't control the screen |
| Notification access | Reading and summarizing your notification shade (optional) | Yes |
| Usage access | Knowing which app is in the foreground for doomscroll reminders | Yes. Reminders stop |
| Contacts | Looking up phone numbers when you ask her to call or text someone | Yes |
| Audio / media read | Finding local MP3s/tracks on your device | Yes |
| Internet | Talking to Cloud AI providers (Gemini/Claude/Groq/OpenRouter) | Yes. Local Gemma works offline |

---

## Privacy

- **Zero servers, zero accounts, zero telemetry.** Nothing is ever sent to me.
- **Local Files Stay Local:** Your API keys (`capri_prefs`) and her learned app breadcrumbs/action memory (`capri_agent_memory.json`) live strictly in the app's private internal storage on your phone. You can wipe her memory at any time from the **Brain Inspector** or by saying *"clear memory"*.
- **Screenshots During Tasks:** Whenever she controls your screen or you ask *"what's on my screen?"*, she captures frames of your display. If you use a Cloud AI provider, those downscaled frames are sent to that provider's API. If you use the on-device Gemma model, **nothing leaves your phone**. Don't run cloud tasks while sensitive banking or private info is on screen (wallet/payment apps are hardcoded into her blocklist, and password fields are ignored by the accessibility scanner).

---

## Limitations (Read This!)

- **It's a Beta "Do-Anything" Agent:** She is *dramatically* smarter in V15, but she can still misread a chaotic screen, tap the wrong button, or get overly ambitious. The draggable **STOP** button at the top of the screen is your best friend.
- **Un-Rooted Android Speed Limits:** Because Capri runs on standard, un-rooted Android without sketchy kernel exploits, `AccessibilityService` screenshots and LLM thinking take roughly **1.2 to 3.5 seconds per move**. Fast reflexes (like catching falling sun in PvZ) are handled by her Kotlin reflex scanner, while her LLM brain handles strategy.
- **3D Games Are Experimental:** She can navigate game menus, tap/drag 2D boards, turn the 3D camera (`LOOK`), and hold to mine blocks (`HOLD`), but don't expect her to speedrun the Ender Dragon on a 2B mobile model yet.
- **Cloud Quota:** Continuous screen control sends a screenshot on every step. If you use a free-tier API key, you *will* hit rate limits eventually.

---

## Where the Idea Came From

Capri was built from scratch in this repository—not a fork or port.

The inspiration came from an early video of a Windows desktop project also named Capri (200+ GitHub stars), which proved that giving an AI an on-screen cursor and a personality makes it feel like a companion sharing your screen rather than a sterile text box. This Android project is a completely original codebase designed around how phones actually work: touch gestures, app switching, notifications, and on-device mobile NPUs/CPUs.

---

## How AI Was Used to Build It (Not "Vibe Coded")

Before anyone drops the *"vibecoded"* label in the issues: Capri was engineered using a multi-model, peer-reviewed workflow where AI models acted as specialized architecture partners, code generators, and adversarial reviewers—while every design decision, line-by-line review, and hardware test went through me.

### Credits to the AI Stack in V15:
- **Gemini (Google):** Heavy lifting on V15's core system architecture, UI/XML design (**Real Talk** full-screen UI, **Brain Inspector**, and the **Vision Pixel Slider**), the gridless exact-pixel vision pipeline, the 3-tier strategic memory & app-breadcrumb engine, and the Android Studio agent integration that applied and verified patches against the live Gradle build.
- **Claude (Anthropic):** Co-architecting complex feature patches, deep code reviews, refactoring multi-file Kotlin systems, and cross-checking specifications before implementation.
- **DeepSeek R1 & Kimi:** Used in the early V1 days for initial brainstorming and the first basic command prototypes.

### Why This Wasn't "Prompt & Pray":
1. **Cross-Model Peer Review:** No AI was trusted blindly. When one model wrote an architectural specification (`.md` patch), another agent audited it line-by-line against the actual repository files to flag conflicts, duplicate classes, or broken assumptions before a single line of code was touched.
2. **Hardware Reality Checks:** AI models love to hallucinate textbook optimizations—like insisting on enabling `Backend.GPU()` for LiteRT-LM, which immediately crashes Qualcomm Adreno OpenCL drivers on real phones. Every one of those assumptions was caught, pushed back on, and rewritten through real-device debugging on Android 17.
3. **Human Architecture & Daily Testing:** I directed every system—why grids had to be replaced with exact coordinates, how memory and breadcrumbs should work, why Reflex vs. Strategy needed to be split for games, and how her personality should feel. Every build was compiled, sideloaded, and stress-tested on my daily driver.

The AIs wrote massive amounts of great Kotlin and XML, and they deserve full credit for making a solo project of this scale possible—but the architecture, the quality bar, and the debugging are human-verified.

---

## Contributing

Issues and pull requests are welcome, especially:
- Bug reports with your phone model, SoC, and Android version (attach a screenshot from **Brain Inspector** if a task went wrong!).
- New app breadcrumb hints or proactive reminder lines for apps you use.

Please don't ask for a Play Store release, an iOS port, or a hosted server. Not happening.

---

## License

See [LICENSE](LICENSE).
