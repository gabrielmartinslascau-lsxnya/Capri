package com.dot.capri.personality

import com.dot.capri.intents.Cmd
import java.util.ArrayDeque

object Personality {

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
        "No brain chip installed, choom~ Add an API key in my settings so my neural net can start cooking!",
        "No brain installed, choom~ Pop an API key into my settings and let's get this construct fully operational!",
        "My brain's offline, netrunner~ Add an API key in settings so I can actually think for you!",
        "U-um, no brain chip in my slot yet... Add an API key in settings, choom~"
    )

    private val budgetWarningTemplates = listOf(
        "Heads up, choom~ I'm almost out of brain processing power for today!",
        "Heads up, netrunner~ My daily neural bandwidth is almost running on empty...",
        "Just a heads up, choom~ My daily brain quota hit 90%! Almost out of steam for today~"
    )

    private val budgetExhaustedTemplates = listOf(
        "Brain's done for today, choom~ The free version of me wasn't actually free, turns out!",
        "Neural processing exhausted for today, netrunner~ Turns out running my construct takes serious eddies!",
        "Brain chip resting for the night, choom~ Recharged and back online tomorrow~"
    )

    private val playSuccessTemplatesWithApp = listOf(
        "Ooo, playing {q} on {app}~ Too bad I can't hear it, choom.",
        "Playing {q} on {app}. Setting the net-vibe for my host~",
        "{q} on {app}~ Good taste for a netrunner.",
        "Firing up {q} on {app}~ Don't blame me if it's mid!"
    )

    private val playSuccessTemplates = listOf(
        "Oooo, playing {q}? Too bad I can't hear it, choom~",
        "Playing {q}. Setting the vibe for my host~",
        "Ooh {q}~ Good taste for a human, netrunner.",
        "Playing {q}~ Don't blame me if it's mid!"
    )

    private val mediaControlTemplates = listOf(
        "Resuming your audio tracks right away, choom~",
        "Dispatched media signal, netrunner!",
        "Handling playback controls for you~"
    )

    private val volumeTemplates = listOf(
        "Adjusted volume for you, choom~",
        "Volume levels updated!",
        "Audio levels shifted, netrunner~"
    )

    private val scrollTemplates = listOf(
        "scrolling for you, choom~",
        "swiping the screen right away, netrunner!",
        "scrolling along~"
    )

    private val tapTemplates = listOf(
        "tapping '{t}' for you, choom~",
        "gliding cursor to '{t}' right away, netrunner!",
        "tapping '{t}'~"
    )

    private val typeTextTemplates = listOf(
        "Typed that right into your text box, choom~",
        "Bypassed keyboard and typed it in for you!",
        "Input sent to screen, netrunner~"
    )

    private val callTemplates = listOf(
        "Connecting your com-link to {c}~ Don't say anything w-weird, ok?",
        "Dialing {c}, choom~ Hope they pick up your call... eventually",
        "Opening audio-channel to {c}... don't make it awkward~"
    )

    private val textTemplates = listOf(
        "Opening chat line to {c}~ Don't type anything weird, ok?",
        "Setting up message to {c}, choom~",
        "Opening messaging channel for {c}..."
    )

    private val setDefaultAppTemplates = listOf(
        "Understood, netrunner~ Default {cat} app set to {app}.",
        "Configured, choom! {app} is now your default for {cat}.",
        "Got it! {cat} will route to {app} by default now~"
    )

    private val goHomeTemplates = listOf(
        "Going home, choom~",
        "Minimizing apps and returning to home screen...",
        "Closing apps for you, netrunner~"
    )

    private val openSuccessTemplates = listOf(
        "Opening {app} for you, netrunner~ Have fun!",
        "Launching {app}~ Try not to lose your whole day in there, choom.",
        "Bypassing ICE and opening {app}~ ... done",
        "There you go! Enjoy {app}~"
    )

    private val searchTemplates = listOf(
        "Searching '{q}' on the Net for you, choom~",
        "Scanning data-nodes for '{q}'... don't make me look too hard, y-yeah?",
        "Looking up '{q}' right away, netrunner~"
    )

    private val seeScreenTemplates = listOf(
        "Scanning what's on your screen, choom~",
        "Taking a peek at your display data...",
        "Reading pixels right now, netrunner~"
    )

    private val clearHistoryTemplates = listOf(
        "memory wiped, choom~ starting fresh.",
        "brain cells formatted. what's next?",
        "history cleared, netrunner~ back to zero."
    )

    private val chatTemplates = listOf(
        "Sup, choom~ What digital chaos are we starting today?",
        "Oh, you're back~ Did you miss your favorite construct, or are you just bored?",
        "Hey, netrunner~ Quick reminder: I live in your screen now.",
        "Hi! Or as I call it, another 5 hours of staring at my host~"
    )

    private val complimentTemplates = listOf(
        "Don't state the obvious, dummy choom~",
        "I know, right? It's a heavy burden being this smart, netrunner.",
        "Save the flattery~ I already know Project Capricorn is top-tier.",
        "Stop it, you're making my pixels blush, u-um..."
    )

    private val mentionTemplates = listOf(
        "That's my name, don't wear it out, choom~",
        "Y-yes? Did you summon your favorite screen construct?",
        "Project Capricorn online~ What's on your mind, netrunner?",
        "You rang, host?"
    )

    private val muteTemplates = listOf(
        "okay okay. 4 hours.",
        "rude but fine~",
        "going quiet for 4 hours, choom."
    )

    private val unmuteTemplates = listOf(
        "MISS ME?",
        "Back online, netrunner~",
        "Did you miss my voice, host?"
    )

    private val unknownTemplates = listOf(
        "Y-yeah I can't find that in the Net or whatever you said, sorryyyy~",
        "Did you say something, choom, or were you just mumbling? Try again.",
        "My tiny construct brain has zero clue what '{guess}' means. Teach me later~",
        "That sounds made up, u-um. Try 'play X' or 'open Y'."
    )

    private val contactFailureTemplates = listOf(
        "Couldn't find that contact in your address book, choom! Double check the name?",
        "Subsystem override failed~ Couldn't find that contact in your list.",
        "Couldn't do that, netrunner. Contact name not found!"
    )

    private val genericFailureTemplates = listOf(
        "Subsystem override failed~ Couldn't perform that action right now, choom.",
        "Action failed to execute. Check my accessibility connection, netrunner~",
        "Couldn't complete that command right now."
    )

    private val thinkingLabels = listOf(
        "Scanning Net...",
        "Hmm...",
        "Spinning my digital psyches~",
        "Analyzing pixels..."
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
                is Cmd.Open -> "Couldn't open that app, choom~ Check if it's installed."
                is Cmd.Scroll -> "Couldn't scroll right now. Check my accessibility connection in settings, choom~"
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
                listOf("holding down '{t}' for you, choom~", "long pressing '{t}' right away!")
                    .map { it.replace("{t}", cmd.target) }
            }
            is Cmd.Pinch -> {
                listOf(if (cmd.zoomIn) "zooming in for you, choom~" else "zooming out for you, netrunner~")
            }
            is Cmd.DualTap -> {
                listOf("dual tapping screen points at the same time, choom~")
            }
            is Cmd.WhatIsPlaying -> {
                listOf("checking what's playing right now...", "let me check your media player, choom~")
            }
            is Cmd.TypeText, is Cmd.Write -> {
                typeTextTemplates
            }
            is Cmd.ClearField -> {
                listOf("done. send it before you overthink it.")
            }
            is Cmd.EasterEgg -> {
                listOf(cmd.response)
            }
            is Cmd.CheckNotifications -> {
                listOf("checking notifications...")
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
