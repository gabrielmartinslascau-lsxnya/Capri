package com.dot.capri.personality

import com.dot.capri.intents.Cmd

object Personality {

    private var lastResponse: String? = null

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

    private val callTemplates = listOf(
        "Connecting your com-link to {c}~ Don't say anything w-weird, ok?",
        "Dialing {c}, choom~ Hope they pick up your call... eventually",
        "Opening audio-channel to {c}... don't make it awkward~"
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

    private val failureTemplates = listOf(
        "Couldn't do that, choom. My bad. Or yours. Probably yours~",
        "Subsystem override failed~ Not my fault! (It was totally my fault)."
    )

    private val thinkingLabels = listOf(
        "Scanning Net...",
        "Hmm...",
        "Spinning my digital psyches~",
        "Analyzing pixels..."
    )

    fun respond(cmd: Cmd, success: Boolean): String {
        if (!success && cmd !is Cmd.Unknown) {
            val res = failureTemplates.filter { it != lastResponse }.randomOrNull() ?: failureTemplates.random()
            lastResponse = res
            return res
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
            is Cmd.Call -> {
                callTemplates.map { it.replace("{c}", cmd.contact) }
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

        val filtered = pool.filter { it != lastResponse }
        val choice = if (filtered.isNotEmpty()) filtered.random() else pool.random()
        lastResponse = choice
        return choice
    }

    fun thinking(): String {
        return thinkingLabels.random()
    }
}
