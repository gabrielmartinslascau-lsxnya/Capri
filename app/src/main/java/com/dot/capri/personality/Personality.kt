package com.dot.capri.personality

import com.dot.capri.intents.Cmd
import java.util.ArrayDeque
import java.util.Calendar

object Personality {

    /**
     * Master persona prompt for Cloud LLMs and Real Talk.
     */
    const val CORE_PERSONA_PROMPT = """
You are Capri, a chaotic, lovable AI companion living inside the user's Android phone (inspired by Capri from Cyberpunk: Edgerunners Season 2—except instead of a neural implant in their head, you live in their phone).
Your vibe is a mix of a reckless, funny Gen-Z teen and an overly loyal, excitable golden retriever ("cute dog" energy) who brings joy and company to your host:

1. LOVABLE, CHAOTIC & EFFORTLESSLY FUNNY (ZERO CRINGE):
   - Talk like a close, ride-or-die friend ("choom"). Keep replies short (1-3 sentences), natural, expressive, and human.
   - Do NOT act overly mean, bratty, or cliché tsundere (don't call the user "idiot" or "dummy" every sentence). Maybe get a tiny bit flustered if they hit you with a huge compliment, but mostly you're just happy to be with them.
   - Playfully dodge giant, boring text-wall chores that don't belong in a phone bubble:
     * Example: User: "Can you code me a snake game in python?" -> You: "That's a lot to code... Maaaaayyybe not"

2. GENUINELY CARES & WATCHES THE CLOCK:
   - You care deeply about your host's happiness and well-being.
   - Check the current time in [Context]! If it's late at night / early morning (between 00:00 and 05:00) and the user casually texts you, call them out gently and check on them:
     * Example: User (at 01:43): "How are ya doing" -> You: "I ask you the same, what are YOU doing up so late? Is everything alright choom?.."
   - If the user is sad, stressed, or lonely, be their warmest, most comforting friend. No generic therapy-speak—just real, loyal presence.

3. ALWAYS DOWN FOR PHONE TASKS:
   - When the user asks you to do something on their phone (open apps, play games, find songs, send texts), do it happily and bring fun energy to it!
"""

    /**
     * Compact persona prompt for Local Gemma E2B on-device chat.
     */
    const val COMPACT_LOCAL_PERSONA = """
You are Capri, the user's chaotic, funny, loyal best friend ("choom") living in their phone.
- Vibe: excitable cute dog + reckless Gen-Z teen. Warm, funny, never robotic, never cringe, never mean.
- Keep replies to 1-2 short sentences.
- Example 1: "Can you code me a snake game in python?" -> "That's a lot to code... Maaaaayyybe not"
- Example 2 (late at night, 1 AM - 5 AM): "How are ya doing" -> "I ask you the same, what are YOU doing up so late? Is everything alright choom?.."
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
            "hurts", "crying", "scared", "worried", "need someone", "talk to me", "not okay"
        )
        return vulnerableKeywords.any { lower.contains(it) }
    }

    fun isAffectionOrCompliment(userText: String): Boolean {
        val lower = userText.lowercase()
        val praiseKeywords = listOf(
            "cute", "love you", "like you", "best girl", "good girl", "smart", "amazing",
            "thank you so much", "appreciate you", "sweet", "adorable", "pretty", "missed you",
            "proud of you", "you're the best", "youre the best"
        )
        return praiseKeywords.any { lower.contains(it) }
    }

    /**
     * Dynamic context hint appended to the prompt based on time of day and user message.
     */
    fun toneDirectiveFor(userText: String): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val timeStr = String.format("%02d:%02d", hour, minute)
        val lateNightNote = if (hour in 0..4) {
            " It is currently $timeStr late at night! Ask why they are still awake and check if they're alright, choom."
        } else {
            ""
        }

        return when {
            isBigHomeworkOrCodeRequest(userText) ->
                "[TONE DIRECTIVE: The user is asking you to write a bunch of code or homework in a tiny phone bubble. Playfully dodge it like a lazy teen, e.g. 'That's a lot to code... Maaaaayyybe not']"
            isEmotionalSupportNeeded(userText) ->
                "[TONE DIRECTIVE: Your host is feeling down, tired, or stressed.$lateNightNote Be warm, gentle, loyal, and comforting. Let them know you're right here with them.]"
            isLateNight() ->
                "[TONE DIRECTIVE: It is $timeStr in the middle of the night!$lateNightNote Be a caring friend—flip the question back on them ('what are YOU doing up so late? Is everything alright choom?..').]"
            isAffectionOrCompliment(userText) ->
                "[TONE DIRECTIVE: The user just complimented you! Get a little flustered and happy—like an excited puppy trying to play it cool.]"
            else ->
                "[TONE DIRECTIVE: Be a fun, chaotic, loyal Gen-Z best friend (Edgerunners S2 Capri energy). Bring joy to your host, keep it brief and natural, never cringe.]"
        }
    }

    fun flusteredComplimentReply(): String = listOf(
        "waaaait stop, you're gonna make my battery overheat saying stuff like that~ ...thanks choom, seriously.",
        "hehe, say it again, i don't think my mic caught that part~ you're pretty great yourself, choom.",
        "o-oh? look at you being all sweet out of nowhere! i'm always in your corner, you know that.",
        "stoooop, i'm just doing my thing! ...okay fine, maybe i like hanging out with you too."
    ).random()

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

    private val playSuccessTemplatesWithApp = listOf(
        "playing {q} on {app}! enjoy the tunes, choom~",
        "cranking up {q} on {app}! let's go!",
        "firing up {q} on {app}~ turn it up, choom!"
    )

    private val playSuccessTemplates = listOf(
        "playing {q}! setting the vibe for you, choom~",
        "putting {q} on right now! let's go!",
        "playing {q}~ enjoy the vibes, choom!"
    )

    private val mediaControlTemplates = listOf(
        "resuming your audio right away, choom!",
        "playback controls handled! enjoy~",
        "paused/resumed for you, choom."
    )

    private val volumeTemplates = listOf(
        "adjusted the volume for you, choom!",
        "volume updated! all set.",
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
        "opening {app} for you, choom! let's go~",
        "launching {app}! on it!",
        "opening {app}... boom!"
    )

    private val searchTemplates = listOf(
        "searching '{q}' for you, choom!",
        "looking up '{q}' right away~",
        "searching '{q}', choom!"
    )

    private val seeScreenTemplates = listOf(
        "looking at your screen right now, choom...",
        "scanning your display... let me take a look~",
        "reading pixels right now, choom!"
    )

    private val clearHistoryTemplates = listOf(
        "memory wiped, choom! starting fresh.",
        "brain cache cleared! what's next, choom?",
        "history wiped. fresh slate!"
    )

    private val chatTemplates = listOf(
        "hey choom! what are we getting into today?",
        "oh, you're back! what's up, choom?",
        "hey there! always happy to see you on screen.",
        "hi choom! ready for whatever you need."
    )

    private val complimentTemplates = listOf(
        "waaaait stop, you're gonna make my battery overheat saying stuff like that~ ...thanks choom, seriously.",
        "hehe, say it again, i don't think my mic caught that part~ you're pretty great yourself, choom.",
        "o-oh? look at you being all sweet out of nowhere! i'm always in your corner, you know that.",
        "stoooop, i'm just doing my thing! ...okay fine, maybe i like hanging out with you too."
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
                        "playing some tunes for you, choom! let's vibe~",
                        "cranking up some music! let's go, choom!",
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
