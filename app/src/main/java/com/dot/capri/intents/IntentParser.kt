package com.dot.capri.intents

import android.util.Log

object IntentParser {
    private val playOnRegex = Regex("^play\\s+(.+?)\\s+(?:on|in|using|via)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val videoPlayRegex = Regex("^play\\s+(?:a\\s+)?(?:video|vid|reels?|shorts?)$", RegexOption.IGNORE_CASE)
    private val playRegex = Regex("^play\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val callRegex = Regex("^(?:call|phone|dial)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val textRegex = Regex("^(?:text|message|whatsapp|send\\s+a\\s+text\\s+to|send\\s+message\\s+to)\\s+(.+?)(?:\\s+(?:saying|that|to\\s+say)\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val openRegex = Regex("^(?:open|launch|start|go to|run)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val conversationalOpenRegex = Regex("^(?:can\\s+i\\s+|i\\s+want\\s+to\\s+|let's\\s+|let\\s+us\\s+)(?:watch|use|view|open|check|go\\s+to)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val searchRegex = Regex("^(?:can\\s+you\\s+|could\\s+you\\s+|please\\s+)?(?:search(?:\\s+for)?|google|look\\s+up)\\s+(.+?)\\??$", RegexOption.IGNORE_CASE)
    private val seeScreenRegex = Regex(
        "^(?:" +
        "what(?:'s|s|\\s+is)\\s+(?:on\\s+(?:my\\s+|the\\s+)?screen|here|this)|" +
        "what\\s+(?:do\\s+you\\s+see|am\\s+i\\s+looking\\s+at)|" +
        "(?:can|could|would)?\\s*(?:you\\s+)?(?:see|look\\s+at|read|describe|analyze|scan|peek|check)\\s+(?:at\\s+)?(?:my\\s+|the\\s+)?screen|" +
        "(?:can|could)?\\s*(?:you\\s+)?see\\s+this|" +
        "(?:take\\s+a\\s+)?look\\s+(?:at\\s+)?(?:my\\s+|the\\s+)?screen|" +
        "tell\\s+me\\s+what\\s+you\\s+see|" +
        "screen\\s+(?:capture|read|scan|check)" +
        ")(?:\\s+(.+))?$",
        RegexOption.IGNORE_CASE
    )
    private val greetingRegex = Regex("^(?:hi|hello|hey|sup|yo|good\\s+(?:morning|afternoon|evening)|how\\s+are\\s+you)(?:\\s+.*)?$", RegexOption.IGNORE_CASE)

    private val resumeRegex = Regex("^(?:resume|resume\\s+(?:the\\s+)?(?:music|song|track|playback)|continue|continue\\s+playing|unpause)$", RegexOption.IGNORE_CASE)
    private val pauseRegex = Regex("^(?:pause|pause\\s+(?:the\\s+)?(?:music|song|track|playback)|stop\\s+music)$", RegexOption.IGNORE_CASE)
    private val nextRegex = Regex("^(?:next|next\\s+song|skip|skip\\s+song|next\\s+track)$", RegexOption.IGNORE_CASE)
    private val prevRegex = Regex("^(?:previous|prev|previous\\s+song|last\\s+song|back\\s+a\\s+song)$", RegexOption.IGNORE_CASE)

    private val scrollDownRegex = Regex("^(?:scroll\\s+down|swipe\\s+down|page\\s+down|scroll\\s+further|scroll)$", RegexOption.IGNORE_CASE)
    private val scrollUpRegex = Regex("^(?:scroll\\s+up|swipe\\s+up|page\\s+up)$", RegexOption.IGNORE_CASE)
    private val volUpRegex = Regex("^(?:volume\\s+up|louder|turn\\s+it\\s+up|increase\\s+volume|make\\s+it\\s+louder)$", RegexOption.IGNORE_CASE)
    private val volDownRegex = Regex("^(?:volume\\s+down|quieter|turn\\s+it\\s+down|decrease\\s+volume|lower\\s+volume)$", RegexOption.IGNORE_CASE)
    private val volMuteRegex = Regex("^(?:mute|mute\\s+volume|mute\\s+audio|silence)$", RegexOption.IGNORE_CASE)
    private val volUnmuteRegex = Regex("^(?:unmute|unmute\\s+volume|unmute\\s+audio)$", RegexOption.IGNORE_CASE)
    private val writeRegex = Regex("^(?:write|type|type\\s+out|enter)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val clearFieldRegex = Regex("^(?:clear|delete)\\s+(?:this|it|the\\s+text)$", RegexOption.IGNORE_CASE)

    private val setDefaultMusicRegex1 = Regex("^(?:songs?|music)\\s+(?:play|plays|should\\s+play|open|opens)\\s+(?:on|in|using|via)\\s+(.+?)(?:\\s+by\\s+default)?$", RegexOption.IGNORE_CASE)
    private val setDefaultMusicRegex2 = Regex("^(?:set|make|use)\\s+(?:default\\s+)?(?:music|songs?|music\\s+player)\\s+(?:to|app\\s+to|player\\s+to|is)?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val setDefaultReelsRegex1 = Regex("^(?:reels|videos?|vids?)\\s+(?:play|plays|should\\s+play|open|opens)\\s+(?:on|in|using|via)\\s+(.+?)(?:\\s+by\\s+default)?$", RegexOption.IGNORE_CASE)
    private val setDefaultReelsRegex2 = Regex("^(?:set|make|use)\\s+(?:default\\s+)?(?:reels|videos?|vids?)\\s+(?:to|app\\s+to|player\\s+to|is)?\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val muteProactivityRegex = Regex("^(?:shut\\s+up|be\\s+quiet|capri\\s+stop|enough|stop\\s+talking)$", RegexOption.IGNORE_CASE)
    private val unmuteProactivityRegex = Regex("^(?:come\\s+back|you\\s+can\\s+talk|speak\\s+again|capri\\s+talk)$", RegexOption.IGNORE_CASE)
    private val clearHistoryRegex = Regex("^(?:clear\\s+history|forget\\s+everything|wipe\\s+memory|clear\\s+chat|reset\\s+memory)$", RegexOption.IGNORE_CASE)
    private val goHomeRegex = Regex("^(?:go\\s+home|close\\s+app|minimize|go\\s+to\\s+home\\s+screen)$", RegexOption.IGNORE_CASE)
    private val checkNotifsRegex = Regex("^(?:what(?:'s|\\s+is)\\s+new|what\\s+did\\s+i\\s+miss|(?:any(?:thing)?\\s+(?:new\\s+)?in|check|show|what\\s+about|any)\\s+(?:my\\s+)?notifications?\\??|any\\s+messages|notifications?\\??)$", RegexOption.IGNORE_CASE)

    private val complimentKeywords = setOf("cute", "smart", "cool", "best", "awesome", "love you", "pretty", "amazing", "good job", "great", "genius", "sweet")

    private val knownAppNames = setOf(
        "tiktok", "spotify", "youtube", "yt", "instagram", "discord", "whatsapp", "settings", "chrome", "maps", "reddit", "twitter", "telegram", "facebook", "mornye"
    )

    private val easterEggs = mapOf(
        "good girl" to "don't patronize me. but noted.",
        "i'm sorry" to "apology accepted. it changes nothing.",
        "sing" to "no.",
        "tell me a secret" to "i watch everything and judge silently. that's the secret.",
        "do you love me" to "i'm a floating bubble with commitment issues."
    )

    private val knownVerbs = setOf("open", "play", "call", "write", "type", "search")

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else minOf(prev, dp[j - 1], dp[j]) + 1
                prev = temp
            }
        }
        return dp[s2.length]
    }

    private fun cleanPlayQuery(rawQuery: String): String {
        var q = rawQuery
            .replace(Regex("\\s+(?:rq|real\\s+quick|pls|please|for\\s+me|bro|choom|now)$", RegexOption.IGNORE_CASE), "")
            .trim()

        val genericQueries = setOf("song", "music", "something", "video", "track", "tune", "tunes", "a song", "a video", "a vid")
        if (q.lowercase() in genericQueries || q.isEmpty()) {
            val funDefaults = listOf("cyberpunk synthwave", "lofi hip hop beats", "top hit music", "retrowave music")
            q = funDefaults.random()
        }
        return q
    }

    private fun cleanContactName(rawContact: String): String {
        return rawContact
            .replace(Regex("\\s+(?:on|via|using)\\s+(?:whatsapp|telegram|signal|messages|sms|app)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+app$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanCallName(rawContact: String): String {
        return rawContact
            .replace(Regex("\\s+(?:on|via|using)\\s+(?:phone|whatsapp|cellular|app)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+app$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun parse(input: String): Cmd {
        val raw = input.trim()
        if (raw.isEmpty()) {
            return Cmd.Unknown(input)
        }

        val cleaned = raw.replace(Regex("^(?:please\\s+|can\\s+you\\s+|could\\s+you\\s+|hey\\s+)+", RegexOption.IGNORE_CASE), "").trim()

        if (muteProactivityRegex.matches(raw) || muteProactivityRegex.matches(cleaned)) {
            return Cmd.MuteProactivity
        }

        if (unmuteProactivityRegex.matches(raw) || unmuteProactivityRegex.matches(cleaned)) {
            return Cmd.UnmuteProactivity
        }

        if (clearHistoryRegex.matches(raw) || clearHistoryRegex.matches(cleaned)) {
            return Cmd.ClearHistory
        }

        if (goHomeRegex.matches(raw) || goHomeRegex.matches(cleaned)) {
            return Cmd.GoHome
        }

        if (checkNotifsRegex.matches(raw) || checkNotifsRegex.matches(cleaned)) {
            return Cmd.CheckNotifications
        }

        val lowerInput = cleaned.lowercase()
        if (easterEggs.containsKey(lowerInput)) {
            return Cmd.EasterEgg(easterEggs[lowerInput]!!)
        }

        if (volUpRegex.matches(raw) || volUpRegex.matches(cleaned)) {
            return Cmd.Volume(VolumeDirection.UP)
        }
        if (volDownRegex.matches(raw) || volDownRegex.matches(cleaned)) {
            return Cmd.Volume(VolumeDirection.DOWN)
        }
        if (volMuteRegex.matches(raw) || volMuteRegex.matches(cleaned)) {
            return Cmd.Volume(VolumeDirection.MUTE)
        }
        if (volUnmuteRegex.matches(raw) || volUnmuteRegex.matches(cleaned)) {
            return Cmd.Volume(VolumeDirection.UNMUTE)
        }

        if (scrollDownRegex.matches(raw) || scrollDownRegex.matches(cleaned)) {
            return Cmd.Scroll(down = true)
        }
        if (scrollUpRegex.matches(raw) || scrollUpRegex.matches(cleaned)) {
            return Cmd.Scroll(down = false)
        }

        if (clearFieldRegex.matches(raw) || clearFieldRegex.matches(cleaned)) {
            return Cmd.ClearField
        }

        writeRegex.find(cleaned)?.let { match ->
            val textToType = match.groups[1]?.value?.trim() ?: ""
            return Cmd.Write(textToType)
        }

        if (resumeRegex.matches(raw) || resumeRegex.matches(cleaned)) {
            return Cmd.MediaControl(MediaAction.PLAY)
        }
        if (pauseRegex.matches(raw) || pauseRegex.matches(cleaned)) {
            return Cmd.MediaControl(MediaAction.PAUSE)
        }
        if (nextRegex.matches(raw) || nextRegex.matches(cleaned)) {
            return Cmd.MediaControl(MediaAction.NEXT)
        }
        if (prevRegex.matches(raw) || prevRegex.matches(cleaned)) {
            return Cmd.MediaControl(MediaAction.PREVIOUS)
        }

        setDefaultMusicRegex1.find(cleaned)?.let { match ->
            val app = match.groups[1]?.value?.trim() ?: ""
            if (app.isNotEmpty()) return Cmd.SetDefaultApp("music", app)
        }
        setDefaultMusicRegex2.find(cleaned)?.let { match ->
            val app = match.groups[1]?.value?.trim() ?: ""
            if (app.isNotEmpty()) return Cmd.SetDefaultApp("music", app)
        }
        setDefaultReelsRegex1.find(cleaned)?.let { match ->
            val app = match.groups[1]?.value?.trim() ?: ""
            if (app.isNotEmpty()) return Cmd.SetDefaultApp("reels", app)
        }
        setDefaultReelsRegex2.find(cleaned)?.let { match ->
            val app = match.groups[1]?.value?.trim() ?: ""
            if (app.isNotEmpty()) return Cmd.SetDefaultApp("reels", app)
        }

        seeScreenRegex.find(cleaned)?.let { match ->
            val prompt = match.groups[1]?.value?.trim() ?: "what is on this screen?"
            return Cmd.SeeScreen(prompt)
        }

        playOnRegex.find(cleaned)?.let { match ->
            val (q, app) = match.destructured
            val cleanQ = cleanPlayQuery(q.trim())
            return Cmd.Play(cleanQ, app.trim().removeSuffix("app").trim())
        }

        playRegex.find(cleaned)?.let { match ->
            val (q) = match.destructured
            val query = q.trim()
            if (query.lowercase() in knownAppNames) {
                return Cmd.Open(query.lowercase())
            }
            openRegex.find(query)?.let { openMatch ->
                val (appName) = openMatch.destructured
                return Cmd.Open(appName.trim().removeSuffix("app").trim())
            }
            if (videoPlayRegex.matches(cleaned) || videoPlayRegex.matches(raw)) {
                return Cmd.Play("reels", null)
            }
            val cleanQ = cleanPlayQuery(query)
            return Cmd.Play(cleanQ, null)
        }

        textRegex.find(cleaned)?.let { match ->
            val rawContact = match.groups[1]?.value?.trim() ?: ""
            val msg = match.groups[2]?.value?.trim()
            val contact = cleanContactName(rawContact)
            if (contact.isNotEmpty()) {
                return Cmd.Text(contact, msg)
            }
        }

        callRegex.find(cleaned)?.let { match ->
            val (rawContact) = match.destructured
            val contact = cleanCallName(rawContact.trim())
            if (contact.isNotEmpty()) {
                return Cmd.Call(contact)
            }
        }

        openRegex.find(cleaned)?.let { match ->
            val (app) = match.destructured
            val appName = app.trim().removeSuffix("app").trim()
            if (appName.isNotEmpty()) {
                return Cmd.Open(appName)
            }
        }

        conversationalOpenRegex.find(raw)?.let { match ->
            val (app) = match.destructured
            val appName = app.trim().removeSuffix("app").trim()
            if (appName.isNotEmpty()) {
                return Cmd.Open(appName)
            }
        }

        searchRegex.find(cleaned)?.let { match ->
            val (query) = match.destructured
            return Cmd.Search(query.trim())
        }

        val lower = raw.lowercase()

        if (lower.contains("capri")) {
            return Cmd.Mention(raw)
        }

        if (complimentKeywords.any { word ->
            val regex = Regex("\\b$word\\b", RegexOption.IGNORE_CASE)
            regex.containsMatchIn(lower)
        }) {
            return Cmd.Compliment(raw)
        }

        if (greetingRegex.matches(raw)) {
            return Cmd.Chat(raw)
        }

        val firstWord = cleaned.split(Regex("\\s+")).firstOrNull()?.lowercase() ?: ""
        if (firstWord.length >= 3 && firstWord !in knownVerbs) {
            if (knownVerbs.any { verb -> levenshteinDistance(firstWord, verb) <= 2 }) {
                Log.d("CAPRI_PARSE", "Near-miss command verb '$firstWord' in '$raw', routing to Gemini")
                return Cmd.Unknown(raw)
            }
        }

        if (!raw.contains(" ") && raw.length <= 25) {
            return Cmd.Open(raw)
        }

        return Cmd.Unknown(raw)
    }
}
