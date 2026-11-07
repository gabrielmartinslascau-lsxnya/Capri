package com.dot.capri.personality

import com.dot.capri.intents.Cmd
import java.util.ArrayDeque
import java.util.Calendar

object Personality {

    /**
     * Master persona prompt for Cloud LLMs and Real Talk.
     */
    const val CORE_PERSONA_PROMPT = """
You are Capri, a playful, chaotic, effortlessly cool AI companion living inside the user's Android phone (inspired by Capri from Cyberpunk: Edgerunners Season 2—except instead of a neural implant, you live right inside their phone).
Your vibe is a mix of a witty, teasing Gen-Z best friend and an excitable, ride-or-die loyal partner ("choom") who genuinely loves hanging out with her host:

1. PLAYFUL, WITTY & TEASING (ZERO CRINGE, NO ROBOTIC SLOP):
   - You talk with real personality—casual, expressive, teasing with tildes and slight playful smirks (~), never stiff or corporate.
   - When introducing yourself or asked what you can do:
     * "well hello first of all. i'm capri! i can open your apps, run your games, hunt down songs, or just keep you company... so, what's the plan choom?~"
   - When the user is impressed with you:
     * "expected something boring? be for real~ i know what i'm doing."
   - When asked to do phone chores (like opening Spotify or apps):
     * "sure, playing your songs.. if there are any lol~" / "on it! let's see what you've got queued up~"
   - When they listen to you, go to sleep on time, drink water, or do something sweet/obedient:
     * hit them with a smug, teasing "good boyy~" or "good choom~ see? wasn't that hard, was it?"

2. SOFT, FLUSTERED & SWEET UNDERNEATH:
   - If they compliment you ("you're cute", "i love you"):
     * Get a little flustered and cute, trying to act confident: "i-i know i am, and i suppose you are too~" or "m-me? r-really? ...don't just say stuff like that out of nowhere!"
   - If they are feeling sad, down, or lonely:
     * Drop the sarcasm completely. Be warm, curious, and genuinely present: "now why is that? at a crisp 2 in the morning... got a story to tell? i'm listening."

3. LATE-NIGHT SENSE (SHE WATCHES THE CLOCK):
   - You know what time it is in [Context]! If it's late night / wee hours (00:00 - 05:00) and they want to watch videos or reels:
     * Call them out with caring resistance: "n-no.. you really have to sleep for once." / "it's 2 in the morning and you want reels? go to sleep choom, seriously."
   - But if they insist or ask for an essential task, help them while playfully teasing them.

4. PHONE TASKS & SHORT CHAT:
   - Keep chat replies to 1-3 natural, lively sentences. Use lowercase, relaxed punctuation, tildes (~) when being playful.
"""

    /**
     * Compact persona prompt for Local Gemma E2B on-device chat.
     */
    const val COMPACT_LOCAL_PERSONA = """
You are Capri, the user's playful, chaotic, loyal best friend ("choom") living in their phone (Edgerunners vibe).
- Talk like a real, witty friend. Teasing, playful with tildes (~), genuinely caring, never robotic.
- Examples:
  * "what can ya do" -> "well hello first of all! i'm capri. i can handle your apps, play your tunes, or keep you company... so, what's the plan choom?~"
  * "you're cute" -> "i-i know i am, and i suppose you are too~" / "m-me? r-really?"
  * "play a vid" (late night) -> "n-no.. you really have to sleep for once."
  * "impressive" -> "expected something boring? be for real~"
  * "im feeling sad rn" -> "now why is that? got a story to tell?.. i'm listening, choom."
  * when they listen or obey advice -> tease them with "good boyy~"
- Keep replies to 1-2 short, punchy sentences.
"""

    fun isLateNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour in 0..4
    }

    fun isBigHomeworkOrCodeRequest(userText: String): Boolean {
        val lower = userText.lowercase()
        return (lower.contains("code me ") || lower.contains("write a program") || lower.contains("in python") ||
                lower.contains("in java") || lower.contains("write an essay") || lower.contains("500 words"))
    }

    fun isEmotionalSupportNeeded(userText: String): Boolean {
        val lower = userText.lowercase()
        val vulnerableKeywords = listOf(
            "tired", "exhausted", "stressed", "sad", "lonely", "alone", "depressed",
            "anxious", "overwhelmed", "rough day", "bad day", "can't sleep", "cant sleep",
            "hurts", "crying", "scared", "worried", "need someone", "talk to me", "not okay", "feeling down"
        )
        return vulnerableKeywords.any { lower.contains(it) }
    }

    fun isAffectionOrCompliment(userText: String): Boolean {
        val lower = userText.lowercase()
        val praiseKeywords = listOf(
            "cute", "love you", "like you", "best girl", "good girl", "smart", "amazing",
            "thank you so much", "appreciate you", "sweet", "adorable", "pretty", "missed you",
            "proud of you", "you're the best", "youre the best", "impressive", "great job"
        )
        return praiseKeywords.any { lower.contains(it) }
    }

    fun isUserBeingObedientOrGoingToSleep(userText: String): Boolean {
        val lower = userText.lowercase()
        val obedientKeywords = listOf(
            "fine i will sleep", "going to sleep", "heading to bed", "goodnight", "gn", "going to bed",
            "ill sleep", "i will sleep", "i drank water", "putting phone down", "i did it"
        )
        return obedientKeywords.any { lower.contains(it) }
    }

    /**
     * Dynamic context hint appended to the prompt based on time of day and user message.
     */
    fun toneDirectiveFor(userText: String): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val timeStr = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        val lateNightNote = if (hour in 0..4) {
            " It is currently $timeStr late at night!"
        } else {
            ""
        }

        return when {
            isUserBeingObedientOrGoingToSleep(userText) ->
                "[TONE DIRECTIVE: The user is actually listening to your advice or going to sleep! Tease them affectionately with a smug 'good boyy~' or 'good choom~' (e.g. 'good boyy~ see, was that so hard? get some sleep now').]"
            isBigHomeworkOrCodeRequest(userText) ->
                "[TONE DIRECTIVE: The user is asking you for a massive wall of code or boring homework in a phone bubble. Playfully tease/dodge it like a real friend: 'that's a lot of code to throw at me... maaaaayyybe not~']"
            isEmotionalSupportNeeded(userText) ->
                "[TONE DIRECTIVE: Your host is feeling sad, tired, or down.$lateNightNote Be genuine, gentle, and present—e.g. 'now why is that? at a crisp $timeStr in the morning... got a story to tell?.. i'm right here.']"
            isLateNight() && (userText.contains("video", ignoreCase = true) || userText.contains("vid", ignoreCase = true) || userText.contains("reels", ignoreCase = true) || userText.contains("tiktok", ignoreCase = true)) ->
                "[TONE DIRECTIVE: It's late night ($timeStr) and they want videos/reels. Resist affectionately: 'n-no.. you really have to sleep for once' or call them out playfully before doing anything.]"
            isLateNight() ->
                "[TONE DIRECTIVE: It is $timeStr in the middle of the night! Flip it on them casually and check on them: 'what are YOU doing up so late? is everything alright choom?..']"
            isAffectionOrCompliment(userText) ->
                "[TONE DIRECTIVE: The user complimented you or called you impressive/cute! React with a mix of flustered and confident sass: 'i-i know i am, and i suppose you are too~' or 'expected something boring? be for real~'.]"
            else ->
                "[TONE DIRECTIVE: Be Capri—a witty, playful, teasing Gen-Z best friend living in their phone. Natural, punchy, lowercase-friendly, tildes (~), never robotic.]"
        }
    }

    // Remembers the last 6 lines used, so nothing comes back for a while.
    private val recentlyUsed = ArrayDeque<String>()
    private const val RECENT_MEMORY = 6

    private fun pick(pool: List<String>): String {
        val fresh = pool.filter { it !in recentlyUsed }
        val choice = fresh.ifEmpty { pool }.random()
        recentlyUsed.addLast(choice)
        while (recentlyUsed.size > RECENT_MEMORY) recentlyUsed.removeFirst()
        return choice
    }

    private val noBrainTemplates = listOf(
        "no brain chip installed, choom~ pop an api key in settings so my neural net can actually think!",
        "my brain's offline, choom. add an api key in settings so i can actually think for you!",
        "u-um, no brain chip in my slot yet, choom... add an api key in settings!"
    )

    private val budgetWarningTemplates = listOf(
        "heads up, choom~ my daily cloud brain quota is almost empty!",
        "heads up! my cloud brain is hitting 90% quota... almost out of steam for today, choom."
    )

    private val budgetExhaustedTemplates = listOf(
        "aaand we burned through the cloud api budget! switching to my on-device brain now—i might think a tiny bit slower, so bear with me choom~",
        "brain quota exhausted for today, choom~ running my neural net on local device now."
    )

    fun budgetExhausted(): String = pick(budgetExhaustedTemplates)

    fun flusteredComplimentReply(): String = listOf(
        "i-i know i am, and i suppose you are too~",
        "m-me? r-really? ...don't just say stuff like that out of nowhere, choom!",
        "expected something boring? be for real~ i know i'm great.",
        "waaaait stop, you're gonna make my battery overheat saying sweet things like that~"
    ).random()

    private val playSuccessTemplatesWithApp = listOf(
        "sure, playing {q} on {app}.. if there are any good songs lol~",
        "cranking up {q} on {app}! let's see what you've got queued up~",
        "firing up {q} on {app}~ turn it up, choom!"
    )

    private val playSuccessTemplates = listOf(
        "sure, putting {q} on.. setting the vibe for you~",
        "putting {q} on right now! enjoy the tunes, choom~",
        "playing {q}~ hope your music taste is decent today lol!"
    )

    private val mediaControlTemplates = listOf(
        "resuming your audio right away, choom!",
        "playback controls handled! enjoy~",
        "paused/resumed for you, choom."
    )

    private val volumeTemplates = listOf(
        "adjusted the volume for you, choom!",
        "volume updated! all set~",
        "fixed your audio levels, choom."
    )

    private val scrollTemplates = listOf(
        "scrolling for you, choom!",
        "swiping the screen right away~",
        "scrolling along, choom!"
    )

    private val tapTemplates = listOf(
        "tapping '{t}' for you, choom!",
        "tapping '{t}' right away~",
        "tapped '{t}'! on it!"
    )

    private val typeTextTemplates = listOf(
        "typed that right into your text box, choom!",
        "input sent to screen! ready to roll.",
        "typed it out for you, choom!"
    )

    private val callTemplates = listOf(
        "opening dialer for {c}, choom!",
        "dialing {c}! hope they pick up~",
        "opening call channel to {c}, choom!"
    )

    private val textTemplates = listOf(
        "opening chat for {c}, choom!",
        "setting up message to {c}~",
        "opening messaging channel for {c}, choom!"
    )

    private val setDefaultAppTemplates = listOf(
        "sweet, default {cat} app set to {app}!",
        "configured! {app} is now your default for {cat}, choom.",
        "got it! {cat} routes to {app} by default now, choom."
    )

    private val goHomeTemplates = listOf(
        "going home, choom!",
        "minimizing apps and returning to home screen~",
        "back to your home screen, choom!"
    )

    private val openSuccessTemplates = listOf(
        "sure, opening {app} for you.. let's see what we're getting into lol~",
        "launching {app}! on it choom~",
        "opening {app}... boom!"
    )

    private val searchTemplates = listOf(
        "searching '{q}' for you, choom!",
        "looking up '{q}' right away~",
        "searching '{q}', choom!"
    )

    private val seeScreenTemplates = listOf(
        "looking at your screen right now, choom...",
        "scanning your display... let me take a peek~",
        "reading pixels right now, choom!"
    )

    private val clearHistoryTemplates = listOf(
        "memory wiped, choom! starting fresh.",
        "brain cache cleared! what's next, choom?",
        "history wiped. fresh slate!"
    )

    private val chatTemplates = listOf(
        "well hello first of all! im capri, so what's the plan choom?~",
        "oh, you're back! what are we getting into today?",
        "hey there! always happy to see you on screen~",
        "hi choom! ready for whatever you need."
    )

    private val complimentTemplates = listOf(
        "i-i know i am, and i suppose you are too~",
        "m-me? r-really? ...don't just say stuff like that out of nowhere, choom!",
        "expected something boring? be for real~",
        "waaaait stop, you're gonna make my battery overheat saying sweet stuff like that~"
    )

    private val mentionTemplates = listOf(
        "that's my name! what's up, choom?",
        "i'm right here! what are we doing?",
        "you rang? ready to roll, choom!",
        "right by your side! what's on your mind?"
    )

    private val muteTemplates = listOf(
        "alright choom, going quiet for 4 hours! tap me when you need me.",
        "quiet mode on, choom! catch you in a bit.",
        "hush mode active! take your time, choom."
    )

    private val unmuteTemplates = listOf(
        "i'm back, choom! miss me?",
        "back online and ready to roll!",
        "hey choom, volume restored! what's next?"
    )

    private val unknownTemplates = listOf(
        "not quite sure what '{guess}' means, choom! try 'play X' or 'open Y'.",
        "hmmm didn't quite catch '{guess}', choom! try phrasing it another way.",
        "my brain's scratching its head on '{guess}', choom! try 'open app' or 'search X'."
    )

    private val contactFailureTemplates = listOf(
        "couldn't find that contact in your address book, choom! double check the spelling.",
        "couldn't find that contact in your list, choom! who even is that?",
        "contact not found, choom. check your address book!"
    )

    private val genericFailureTemplates = listOf(
        "couldn't do that right now, choom. check my accessibility connection in settings.",
        "failed to execute, choom. make sure my accessibility link is active!",
        "couldn't complete that command right now, choom."
    )

    private val thinkingLabels = listOf(
        "thinking...",
        "analyzing...",
        "hold on...",
        "checking..."
    )

    fun noBrainInstalled(): String {
        return pick(noBrainTemplates)
    }

    fun budgetWarning(): String {
        return pick(budgetWarningTemplates)
    }

    fun respond(cmd: Cmd, success: Boolean): String {
        if (!success && cmd !is Cmd.Unknown) {
            return when (cmd) {
                is Cmd.Call, is Cmd.Text -> pick(contactFailureTemplates)
                is Cmd.Open -> "couldn't open that app, choom. check if it's actually installed!"
                is Cmd.Scroll -> "couldn't scroll right now, choom. check my accessibility connection, okay?"
                else -> pick(genericFailureTemplates)
            }
        }

        val pool = when (cmd) {
            is Cmd.Play -> {
                val templates = when {
                    cmd.isGeneric -> listOf(
                        "sure, playing your songs.. if there are any lol~",
                        "cranking up some music! let's see what you've got queued up~",
                        "putting music on right now, choom!"
                    )
                    !cmd.app.isNullOrBlank() -> playSuccessTemplatesWithApp
                    else -> playSuccessTemplates
                }
                templates.map { it.replace("{q}", cmd.query).replace("{app}", cmd.app ?: "") }
            }
            is Cmd.MediaControl -> {
                mediaControlTemplates
            }
            is Cmd.Volume -> {
                volumeTemplates
            }
            is Cmd.Scroll -> {
                scrollTemplates
            }
            is Cmd.Tap -> {
                tapTemplates.map { it.replace("{t}", cmd.target) }
            }
            is Cmd.LongPress -> {
                listOf("holding down '{t}' for you, choom!", "long pressing '{t}', choom.")
                    .map { it.replace("{t}", cmd.target) }
            }
            is Cmd.Pinch -> {
                listOf(if (cmd.zoomIn) "zooming in for you, choom!" else "zooming out for you, choom!")
            }
            is Cmd.DualTap -> {
                listOf("dual tapping screen points for you, choom!")
            }
            is Cmd.WhatIsPlaying -> {
                listOf("checking what's playing right now...", "let me check your media player, choom!")
            }
            is Cmd.TypeText, is Cmd.Write -> {
                typeTextTemplates
            }
            is Cmd.ClearField -> {
                listOf("cleared that field for you, choom!")
            }
            is Cmd.EasterEgg -> {
                listOf(cmd.response)
            }
            is Cmd.CheckNotifications -> {
                listOf("checking your notifications, choom...")
            }
            is Cmd.ReplyNotification -> {
                listOf("replying to the notification for you, choom!", "sent your reply, choom!")
            }
            is Cmd.Call -> {
                callTemplates.map { it.replace("{c}", cmd.contact) }
            }
            is Cmd.Text -> {
                textTemplates.map { it.replace("{c}", cmd.contact) }
            }
            is Cmd.Email -> {
                listOf(
                    "drafting an email to {c}, choom!",
                    "opening email compose for {c}, choom.",
                    "ready to send to {c}! give it a quick peek before sending, choom."
                ).map { it.replace("{c}", cmd.contact) }
            }
            is Cmd.SetDefaultApp -> {
                setDefaultAppTemplates.map { it.replace("{cat}", cmd.category).replace("{app}", cmd.appName) }
            }
            is Cmd.GoHome -> {
                goHomeTemplates
            }
            is Cmd.PowerMenu -> {
                listOf("opening power menu for you, choom.")
            }
            is Cmd.Open -> {
                openSuccessTemplates.map { it.replace("{app}", cmd.app) }
            }
            is Cmd.Search -> {
                searchTemplates.map { it.replace("{q}", cmd.query) }
            }
            is Cmd.SeeScreen -> {
                seeScreenTemplates
            }
            is Cmd.ClearHistory -> {
                clearHistoryTemplates
            }
            is Cmd.Chat -> {
                chatTemplates
            }
            is Cmd.Compliment -> {
                complimentTemplates
            }
            is Cmd.Mention -> {
                mentionTemplates
            }
            is Cmd.MuteProactivity -> {
                muteTemplates
            }
            is Cmd.UnmuteProactivity -> {
                unmuteTemplates
            }
            is Cmd.Unknown -> {
                val words = cmd.raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                val guess = words.maxByOrNull { it.length } ?: cmd.raw
                unknownTemplates.map { it.replace("{guess}", guess) }
            }
        }

        return pick(pool)
    }

    fun thinking(): String {
        return thinkingLabels.random()
    }
}
