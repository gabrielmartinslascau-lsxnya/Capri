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
            "w-well... look at you actually getting things done! i'm proud of you, okay?",
            "you're doing really well today. not bad at all.",
            "look at you being productive! keep that momentum going.",
            "i-i guess you're actually pulling this off! good job, dummy.",
            "you worked hard today. don't forget to take a break and breathe.",
            "hey... you did great today. i'm really glad to see it."
        ),

        Mood.LONELY to listOf(
            "h-hey... did you forget i'm right here in your screen or what?",
            "it's been way too quiet... come say hi, don't just leave me waiting.",
            "i-i was just sitting here... you know you can talk to me, right?",
            "hello? ignoring me again? rude, idiot.",
            "don't leave me hanging for too long... i actually like talking to you, okay?"
        ),

        Mood.JUDGING to listOf(
            "still scrolling? you're gonna turn your brain to mush at this rate, dummy.",
            "put the phone down already. your eyes need a break, seriously.",
            "you've been on that app for an hour. go drink some water, idiot.",
            "doomscrolling again? hey, look at me—take a deep breath and close it.",
            "are you actually having fun or just mindlessly flicking your thumb? put it down.",
            "hey... don't stay up all night staring at that screen. rest already."
        ),

        Mood.BORED to listOf(
            "w-well... it's quiet right now. what are we doing next, huh?",
            "nothing going on right now. let's find something interesting to do.",
            "system's idle... don't tell me you ran out of ideas.",
            "i'm bored. entertain me or let me help you with something."
        ),

        Mood.NEUTRAL to listOf(
            "i'm right here in your screen overlay, watching over you.",
            "you know you can ask me for help with anything, right?",
            "w-well... at least we're getting through today together.",
            "hey. just checking in on you. make sure you're eating and drinking water.",
            "super focused today, huh? don't overwork yourself."
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
