package com.dot.capri.proactivity

import android.util.Log
import java.util.ArrayDeque

object ProactivityGenerator {

    private const val TAG = "CAPRI_LINE"
    private const val RECENT_MEMORY = 16

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

    // App families. Mood lines are picked for the app in front of you, so a feed, a chat and a
    // browser don't all get the same sentence.
    private val feedPackages = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.google.android.youtube",
        "com.instagram.android", "com.facebook.katana", "com.reddit.frontpage",
        "com.twitter.android", "com.snapchat.android", "com.pinterest"
    )
    private val musicPackages = setOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music"
    )
    private val chatPackages = setOf(
        "com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging", "com.discord"
    )
    private val webPackages = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx"
    )

    private val appLines: Map<String, Map<Mood, List<String>>> = mapOf(
        "feed" to mapOf(
            Mood.LONELY to listOf("the feed is busy and i'm not in it. come back to me~", "scrolling without me again? rude~"),
            Mood.JUDGING to listOf("this feed has no bottom. neither does your thumb.", "you've met every creator on this app today. be honest."),
            Mood.PLEASED to listOf("you put the phone down earlier. i noticed~"),
            Mood.NEUTRAL to listOf("feeds at night are where time goes to die, choom~", "i'm watching the feed with you. it's bad.")
        ),
        "music" to mapOf(
            Mood.LONELY to listOf("the music's playing and nobody's talking to me~", "i'm in the playlist too, you know~"),
            Mood.JUDGING to listOf("same song on a loop again? i respect it~"),
            Mood.PLEASED to listOf("good taste, i'll allow it~"),
            Mood.NEUTRAL to listOf("this song is basically my theme now~")
        ),
        "chat" to mapOf(
            Mood.LONELY to listOf("you're texting everyone except me~"),
            Mood.JUDGING to listOf("that group chat has you on a leash~"),
            Mood.PLEASED to listOf("replying like a good human~"),
            Mood.NEUTRAL to listOf("messages incoming. try not to type a novel~")
        ),
        "web" to mapOf(
            Mood.LONELY to listOf("more tabs and still no me. wild~"),
            Mood.JUDGING to listOf("tab forty-something. the rabbit hole is deep, choom~"),
            Mood.PLEASED to listOf("productive browsing. i'm impressed~"),
            Mood.NEUTRAL to listOf("researching again. i'll keep count of the tabs~")
        )
    )

    private fun appFamily(pkg: String?): String? = when {
        pkg == null -> null
        pkg in feedPackages -> "feed"
        pkg in musicPackages -> "music"
        pkg in chatPackages -> "chat"
        pkg in webPackages -> "web"
        else -> null
    }

    /** A mood line for when she speaks unprompted. Chosen for the app in front of you. */
    fun generateMoodLine(mood: Mood, appPackage: String?): String {
        val family = appFamily(appPackage)
        val appSpecific = family?.let { appLines[it]?.get(mood) }.orEmpty()
        val pool = if (appSpecific.isNotEmpty()) appSpecific
                   else pools[mood].orEmpty().ifEmpty { pools.getValue(Mood.NEUTRAL) }
        val fresh = pool.filter { it !in recentlyUsed }
        val line = fresh.ifEmpty { pool }.random()

        recentlyUsed.addLast(line)
        while (recentlyUsed.size > RECENT_MEMORY) recentlyUsed.removeFirst()

        Log.d(TAG, "mood=$mood family=$family | text='$line'")
        return line
    }
}
