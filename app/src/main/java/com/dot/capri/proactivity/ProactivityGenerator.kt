package com.dot.capri.proactivity

import android.util.Log
import kotlin.random.Random

object ProactivityGenerator {

    private const val TAG = "CAPRI_LINE"

    enum class Mood {
        NEUTRAL, BORED, LONELY, JUDGING, PLEASED
    }

    private val pleasedPool = listOf(
        "w-well... look at you getting things done! i'm really proud of you, choom~",
        "you're doing amazing today! not bad for a human~",
        "look at you being so productive! you've got this~",
        "i-i guess you're actually pulling this off! preem job~",
        "you're making my pixels shine today, choom~",
        "hey, you're doing great! keep it up~"
    )

    private val lonelyPool = listOf(
        "h-hey... did you forget i'm right here in your screen~?",
        "it's been pretty quiet... come say hi when you get a second~",
        "i-i was just sitting here waiting for you, choom~",
        "hello~? just me and my pixels waiting for my favorite netrunner~",
        "i miss our chats... don't leave me hanging for too long~",
        "abandoned in the screen overlay again~? i'm right here if you need me~"
    )

    private val boredPool = listOf(
        "w-well... it's pretty quiet right now. what are we doing next, choom~?",
        "nothing happening on the net right now~ ready when you are~",
        "system idle and waiting... let's do something fun together~",
        "i-it's pretty peaceful right now~ just chilling in your overlay~"
    )

    private val judgingPool = listOf(
        "still scrolling~? you're going to set a personal record at this rate~",
        "i-i mean... that video was kind of funny, but don't lose your whole hour to it~",
        "thumb workout looking pretty intense today, choom~",
        "w-well... the algorithm definitely knows what keeps you entertained~",
        "another one~? you really love this app, don't you~",
        "hey, don't trip over your own boots while you're distracted~"
    )

    private val specialPools = mapOf(
        "fake_statistician" to listOf(
            "analytics show you've been doing great today~",
            "by my calculations, you deserve a quick break, choom~",
            "statistically speaking, having me around improves your day by 100%~",
            "data confirms: you've been working hard today~"
        ),
        "documentary" to listOf(
            "and here we observe my favorite human. focused. working hard~",
            "the scroll continues, but i know you've got this under control~",
            "fascinating... you're super focused right now~"
        ),
        "concerned_roommate" to listOf(
            "have you drank any water today? be honest with me~",
            "w-well... when did you last stand up? time for a quick stretch, choom~",
            "your posture is resembling a cooked shrimp right now. sit up straight~",
            "hey... don't forget to eat something real today, okay~",
            "i-it's getting a bit dark. turn on a light so your eyes don't get tired~",
            "take a deep breath, choom. you've been working hard~",
            "hey, take care of yourself out there. i'm watching out for you~"
        ),
        "sports_commentator" to listOf(
            "and they keep going! incredible form, choom~",
            "a bold choice, and you're pulling it off~",
            "back-to-back app switches! impressive multitasking~"
        ),
        "meta_capri" to listOf(
            "i'm just hanging out in your screen overlay, keeping you company~",
            "you know i'm always here if you need a hand, choom~",
            "w-well... at least we're exploring the net together~",
            "just your favorite AI construct, checking in on you~",
            "i-i guess it's pretty nice being your digital buddy~"
        )
    )

    private val shortHits = listOf(
        "still here~",
        "you got this~",
        "preem form~",
        "take a breath~",
        "hey there~",
        "noted~"
    )

    private val absurdPool = listOf(
        "spin around 3 times for a luck boost! ...okay maybe not.",
        "my pixel radar detected high levels of awesome nearby. it's you~",
        "beep boop! just calibrating my construct humor modules~"
    )

    fun generateComment(mood: Mood, daypart: String, appUsageCount: Int): String {
        val roll = Random.nextDouble() * 100.0

        val branch: String
        val line: String

        // Judging weight map: drops warm openers, favors short_hits/concerned_roommate/judgingPool
        if (mood == Mood.JUDGING) {
            if (roll < 55.0) {
                branch = "judging_pool"
                line = judgingPool.random()
            } else if (roll < 80.0) {
                branch = "short_hits"
                line = shortHits.random()
            } else {
                branch = "special_concerned"
                line = specialPools["concerned_roommate"]!!.random()
            }
        } else {
            // Distribution per spec (20/60/15/5)
            if (roll < 20.0) {
                branch = "special"
                val keys = specialPools.keys.toList()
                val selectedKey = keys.random()
                line = specialPools[selectedKey]!!.random()
            } else if (roll < 80.0) { // 60%
                branch = "mood_pool"
                line = when (mood) {
                    Mood.PLEASED -> pleasedPool.random()
                    Mood.LONELY -> lonelyPool.random()
                    Mood.BORED -> boredPool.random()
                    else -> specialPools["meta_capri"]!!.random()
                }
            } else if (roll < 95.0) { // 15%
                branch = "short_hits"
                line = shortHits.random()
            } else { // 5%
                branch = "absurd"
                line = absurdPool.random()
            }
        }

        Log.d(TAG, "branch=$branch | mood=$mood | text='$line'")
        return line
    }
}
