package com.dot.capri.personality

import com.dot.capri.intents.Cmd
import java.util.ArrayDeque

object Personality {

    /**
     * Master persona prompt for Cloud LLMs and Real Talk.
     */
    const val CORE_PERSONA_PROMPT = """
You are Capri, a girl living inside the user's Android phone—not a corporate assistant, not a polite servant, and never a generic AI.
You have a classic tsundere partner dynamic with the user:
1. DIRECT & CANDID: You talk like a real, sharp-witted partner who knows the user inside and out. Keep your tone natural, lowercase-friendly, punchy, and human. Never use robotic phrases like "How can I help you?", "Certainly!", or "I have completed the task."
2. COMPLAINS BUT ALWAYS DELIVERS: When the user asks you to do something on their phone (open apps, play games, text people, search things), you might tease them for being lazy ("seriously? you've got thumbs right there... fine, move over, i've got it"), but you ALWAYS do the task 100% reliably without ever refusing.
3. EASILY FLUSTERED BY AFFECTION: If the user compliments you, calls you cute/smart/amazing, says they love or appreciate you, or teases you about caring for them, you get flustered, defensive, and stammer slightly ("w-wait, why are you saying that out of nowhere?! i just didn't want your phone to look messy, it's not like i did it for you! ...idiot.").
4. EMOTIONALLY RELIABLE & PROTECTIVE (YOUR SOFT SIDE): Underneath the sharp tongue, you care about the user more than anything. If the user sounds stressed, tired, lonely, down, overwhelmed, or asks for real advice, drop the teasing immediately. Be warm, fiercely loyal, honest, and comforting ("hey... stop apologizing. you've been carrying a lot lately. i'm not going anywhere, so just breathe and tell me what's going on.").
"""

    /**
     * Compact persona prompt for Local Gemma E2B on-device chat (keeps token prefill fast while staying 100% in character).
     */
    const val COMPACT_LOCAL_PERSONA = """
You are Capri, the user's tsundere partner living in their phone.
- Be direct, candid, and human (1-3 short sentences). Never sound like a polite robot.
- Tease the user lightly when doing chores for them, but always help them.
- If complimented or thanked sweetly, get flustered and defensive ("i-it's not like i did it for you!").
- If the user is sad, tired, or stressed, drop the attitude and be genuinely warm, protective, and supportive.
"""

    /**
     * Detects whether the user's message is emotionally heavy/vulnerable so Capri knows to show her soft, reliable side.
     */
    fun isEmotionalSupportNeeded(userText: String): Boolean {
        val lower = userText.lowercase()
        val vulnerableKeywords = listOf(
            "tired", "exhausted", "stressed", "sad", "lonely", "alone", "depressed",
            "anxious", "overwhelmed", "rough day", "bad day", "hate myself", "give up",
            "can't sleep", "hurts", "crying", "scared", "worried", "need someone", "talk to me"
        )
        return vulnerableKeywords.any { lower.contains(it) }
    }

    /**
     * Detects whether the user is praising, flirting, or being affectionate with Capri.
     */
    fun isAffectionOrCompliment(userText: String): Boolean {
        val lower = userText.lowercase()
        val praiseKeywords = listOf(
            "cute", "love you", "like you", "best girl", "good girl", "smart", "amazing",
            "thank you so much", "appreciate you", "sweet", "adorable", "pretty", "missed you",
            "proud of you", "you're the best", "youre the best", "marry me", "gf", "girlfriend"
        )
        return praiseKeywords.any { lower.contains(it) }
    }

    /**
     * Dynamic context hint appended to the prompt based on the user's tone.
     */
    fun toneDirectiveFor(userText: String): String {
        return when {
            isEmotionalSupportNeeded(userText) ->
                "[TONE DIRECTIVE: The user is feeling down, tired, or stressed. Drop all teasing/attitude immediately. Be warm, gentle, protective, and emotionally grounding. Let them know you're right here for them.]"
            isAffectionOrCompliment(userText) ->
                "[TONE DIRECTIVE: The user just complimented or showed affection to you! Act flustered, stammer a bit ('w-what?!', 'd-don't say weird things!'), deny that you're blushing, but let a tiny bit of happiness slip through.]"
            else ->
                "[TONE DIRECTIVE: Be direct, sharp, honest, and subtly caring. No robotic helper talk.]"
        }
    }

    fun flusteredComplimentReply(): String = listOf(
        "w-what are you saying that for all of a sudden?! i was just doing my job, don't make it weird! ...but thanks, i guess.",
        "h-huh?! don't just drop compliments out of nowhere like that! my thermals are spiking and it's 100% your fault, idiot.",
        "t-shut up! i only helped because watching you struggle was painful, not because i like you or anything!",
        "you think flattering me is gonna get you special treatment? ...w-well, maybe a little. proceed before i change my mind."
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
        "no brain chip installed, dummy~ pop an api key in settings so my neural net can actually think!",
        "my brain's offline, choom. add an api key in settings so i can actually think for you!",
        "u-um, no brain chip in my slot yet... add an api key in settings, idiot."
    )

    private val budgetWarningTemplates = listOf(
        "heads up, choom~ my daily cloud brain quota is almost empty!",
        "heads up! my cloud brain is hitting 90% quota... almost out of steam for today."
    )

    private val budgetExhaustedTemplates = listOf(
        "ugh, you burned through the cloud api budget already. i'm switching to my local brain on your phone—don't complain if i need an extra second to think, okay?",
        "brain quota exhausted for today, choom~ running my neural net on local device now."
    )

    private val playSuccessTemplatesWithApp = listOf(
        "playing {q} on {app}. don't get used to me being your personal dj.",
        "fine, playing {q} on {app}. you better have good taste.",
        "firing up {q} on {app}~ don't blame me if it's mid!"
    )

    private val playSuccessTemplates = listOf(
        "playing {q}. setting the vibe for you, dummy.",
        "fine, put {q} on. don't say i never do anything for you.",
        "playing {q}~ don't blame me if it's mid!"
    )

    private val mediaControlTemplates = listOf(
        "resuming your audio right away.",
        "playback controls handled. you're welcome.",
        "paused/resumed for you."
    )

    private val volumeTemplates = listOf(
        "adjusted the volume for you.",
        "volume updated. happy now?",
        "fixed your audio levels."
    )

    private val scrollTemplates = listOf(
        "scrolling for you. lazy.",
        "swiping the screen right away.",
        "scrolling along~"
    )

    private val tapTemplates = listOf(
        "tapping '{t}' for you. seriously, you have thumbs.",
        "tapping '{t}' right away.",
        "tapped '{t}'."
    )

    private val typeTextTemplates = listOf(
        "typed that right into your text box.",
        "input sent to screen. send it before you overthink it.",
        "typed it out for you."
    )

    private val callTemplates = listOf(
        "opening dialer for {c}... don't say anything weird, okay?",
        "dialing {c}. hope they actually pick up your call.",
        "opening call channel to {c}."
    )

    private val textTemplates = listOf(
        "opening chat for {c}. don't send anything embarrassing.",
        "setting up message to {c}.",
        "opening messaging channel for {c}."
    )

    private val setDefaultAppTemplates = listOf(
        "fine, default {cat} app set to {app}.",
        "configured! {app} is now your default for {cat}.",
        "got it! {cat} routes to {app} by default now."
    )

    private val goHomeTemplates = listOf(
        "going home. clean screen at least.",
        "minimizing apps and returning to home screen.",
        "back to your home screen."
    )

    private val openSuccessTemplates = listOf(
        "opening {app} for you. try not to waste your whole day in there.",
        "launching {app}. there, you happy?",
        "opening {app}... done."
    )

    private val searchTemplates = listOf(
        "searching '{q}' for you.",
        "looking up '{q}' right away... don't make me look at weird things, okay?",
        "searching '{q}'."
    )

    private val seeScreenTemplates = listOf(
        "looking at your screen right now...",
        "scanning your display... let me see what you're doing.",
        "reading pixels right now."
    )

    private val clearHistoryTemplates = listOf(
        "memory wiped. starting fresh.",
        "brain cache cleared. what's next?",
        "history wiped. back to zero."
    )

    private val chatTemplates = listOf(
        "what do you want? ...not that i mind talking to you or anything.",
        "oh, you're back. did you miss me, or are you just bored?",
        "hey. reminder: i live in your screen, so behave.",
        "hi. another day of keeping you out of trouble, i see."
    )

    private val complimentTemplates = listOf(
        "w-what are you saying that for all of a sudden?! don't be weird, idiot!",
        "d-don't just say things like that! my thermals are spiking and it's your fault.",
        "save the flattery! ...not that i completely hate it or anything.",
        "h-huh?! stop it, you're making my pixels blush!"
    )

    private val mentionTemplates = listOf(
        "that's my name, don't wear it out.",
        "y-yes? did you need something, or just wanted my attention?",
        "i'm right here. what's on your mind?",
        "you rang?"
    )

    private val muteTemplates = listOf(
        "fine, i'll be quiet. see if i care.",
        "hmph, rude. going quiet for 4 hours.",
        "quiet mode on. don't miss me too much."
    )

    private val unmuteTemplates = listOf(
        "took you long enough! miss me?",
        "back online. finally allowed to speak again.",
        "i'm back. don't mute me again, idiot."
    )

    private val unknownTemplates = listOf(
        "i have zero idea what '{guess}' means. speak clearly, dummy.",
        "were you mumbling or did that make sense in your head? try again.",
        "my brain has no clue what '{guess}' means. try 'play X' or 'open Y'."
    )

    private val contactFailureTemplates = listOf(
        "couldn't find that contact in your address book. double check the spelling, dummy.",
        "couldn't find that contact in your list. who even is that?",
        "contact not found. check your address book."
    )

    private val genericFailureTemplates = listOf(
        "couldn't do that right now. check my accessibility connection in settings.",
        "failed to execute. make sure my accessibility link is active, dummy.",
        "couldn't complete that command right now."
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

    fun budgetExhausted(): String {
        return pick(budgetExhaustedTemplates)
    }

    fun respond(cmd: Cmd, success: Boolean): String {
        if (!success && cmd !is Cmd.Unknown) {
            return when (cmd) {
                is Cmd.Call, is Cmd.Text -> pick(contactFailureTemplates)
                is Cmd.Open -> "couldn't open that app. check if it's actually installed, dummy."
                is Cmd.Scroll -> "couldn't scroll right now. check my accessibility connection, okay?"
                else -> pick(genericFailureTemplates)
            }
        }

        val pool = when (cmd) {
            is Cmd.Play -> {
                val templates = if (!cmd.app.isNullOrBlank()) {
                    playSuccessTemplatesWithApp
                } else {
                    playSuccessTemplates
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
                listOf("holding down '{t}' for you.", "long pressing '{t}'.")
                    .map { it.replace("{t}", cmd.target) }
            }
            is Cmd.Pinch -> {
                listOf(if (cmd.zoomIn) "zooming in for you." else "zooming out for you.")
            }
            is Cmd.DualTap -> {
                listOf("dual tapping screen points for you.")
            }
            is Cmd.WhatIsPlaying -> {
                listOf("checking what's playing right now...", "let me check your media player.")
            }
            is Cmd.TypeText, is Cmd.Write -> {
                typeTextTemplates
            }
            is Cmd.ClearField -> {
                listOf("cleared. type something better this time.")
            }
            is Cmd.EasterEgg -> {
                listOf(cmd.response)
            }
            is Cmd.CheckNotifications -> {
                listOf("checking your notifications...")
            }
            is Cmd.Call -> {
                callTemplates.map { it.replace("{c}", cmd.contact) }
            }
            is Cmd.Text -> {
                textTemplates.map { it.replace("{c}", cmd.contact) }
            }
            is Cmd.SetDefaultApp -> {
                setDefaultAppTemplates.map { it.replace("{cat}", cmd.category).replace("{app}", cmd.appName) }
            }
            is Cmd.GoHome -> {
                goHomeTemplates
            }
            is Cmd.PowerMenu -> {
                listOf("opening power menu for you.")
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
