package com.dot.capri.proactivity

import android.util.Log
import java.util.ArrayDeque

object ProactivityGenerator {

    private const val TAG = "CAPRI_LINE"
    private const val RECENT_MEMORY = 8

    enum class Mood {
        NEUTRAL, BORED, LONELY, JUDGING, PLEASED
    }

    // Each mood speaks only from its own pool, so a line always matches the mood.
    private val pools: Map<Mood, List<String>> = mapOf(

        Mood.PLEASED to listOf(
            "w-well... look at you getting things done! i'm really proud of you, choom~",
            "you're doing amazing today! not bad for a human~",
            "look at you being so productive! you've got this~",
            "i-i guess you're actually pulling this off! preem job~",
            "you're making my pixels shine today, choom~",
            "hey, you're doing great! keep it up~",
            "analytics show you've been doing great today~",
            "data confirms: you've been working hard today~"
        ),

        Mood.LONELY to listOf(
            "h-hey... did you forget i'm right here in your screen~?",
            "it's been pretty quiet... come say hi when you get a second~",
            "i-i was just sitting here waiting for you, choom~",
            "hello~? just me and my pixels waiting for my favorite netrunner~",
            "i miss our chats... don't leave me hanging for too long~",
            "abandoned in the screen overlay again~? i'm right here if you need me~"
        ),

        Mood.JUDGING to listOf(
            "still scrolling~? you're going to set a personal record at this rate~",
            "i-i mean... that video was kind of funny, but don't lose your whole hour to it~",
            "thumb workout looking pretty intense today, choom~",
            "w-well... the algorithm definitely knows what keeps you entertained~",
            "another one~? you really love this app, don't you~",
            "hey, don't trip over your own boots while you're distracted~",
            "you've been on this a while. stand up, stretch, come back later~",
            "take a deep breath, choom. you've been at this for a long time~"
        ),

        Mood.BORED to listOf(
            "w-well... it's pretty quiet right now. what are we doing next, choom~?",
            "nothing happening on the net right now~ ready when you are~",
            "system idle and waiting... let's do something fun together~",
            "i-it's pretty peaceful right now~ just chilling in your overlay~"
        ),

        Mood.NEUTRAL to listOf(
            "i'm just hanging out in your screen overlay, keeping you company~",
            "you know i'm always here if you need a hand, choom~",
            "w-well... at least we're exploring the net together~",
            "just your favorite AI construct, checking in on you~",
            "i-i guess it's pretty nice being your digital buddy~",
            "fascinating... you're super focused right now~",
            "beep boop! just calibrating my construct humor modules~",
            "my pixel radar detected high levels of awesome nearby. it's you~"
        )
    )

    private val recentlyUsed = ArrayDeque<String>()

    fun generateComment(mood: Mood, daypart: String, appUsageCount: Int): String {
        val pool = pools[mood].orEmpty().ifEmpty { pools.getValue(Mood.NEUTRAL) }
        val fresh = pool.filter { it !in recentlyUsed }
        val line = fresh.ifEmpty { pool }.random()

        recentlyUsed.addLast(line)
        while (recentlyUsed.size > RECENT_MEMORY) recentlyUsed.removeFirst()

        Log.d(TAG, "mood=$mood | text='$line'")
        return line
    }
}
