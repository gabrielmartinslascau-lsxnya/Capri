package com.dot.capri.intents

object IntentParser {
    private val playOnRegex = Regex("^play\\s+(.+?)\\s+(?:on|in|using|via)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val videoPlayRegex = Regex("^play\\s+(?:a\\s+)?(?:video|vid|reels?|shorts?)$", RegexOption.IGNORE_CASE)
    private val playRegex = Regex("^play\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val callRegex = Regex("^(?:call|phone|dial)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val textRegex = Regex("^(?:text|message|whatsapp|send\\s+a\\s+text\\s+to|send\\s+message\\s+to)\\s+(.+?)(?:\\s+(?:saying|that|to\\s+say)\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val openRegex = Regex("^(?:open|launch|start|go to|run)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val conversationalOpenRegex = Regex("^(?:can\\s+i\\s+|i\\s+want\\s+to\\s+|let's\\s+|let\\s+us\\s+)(?:watch|use|view|open|check|go\\s+to)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val searchRegex = Regex("^(?:search|google|look up)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val seeScreenRegex = Regex("^(?:what(?:'s|\\s+is)\\s+(?:on\\s+screen|here|what\\s+am\\s+i\\s+looking\\s+at)|look\\s+at\\s+(?:the\\s+)?screen|search\\s+what(?:'s|\\s+is)\\s+on\\s+screen|analyze\\s+screen|screen\\s+capture)(?:\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val greetingRegex = Regex("^(?:hi|hello|hey|sup|yo|good\\s+(?:morning|afternoon|evening)|how\\s+are\\s+you)(?:\\s+.*)?$", RegexOption.IGNORE_CASE)

    private val resumeRegex = Regex("^(?:resume|resume\\s+(?:the\\s+)?(?:music|song|track|playback)|continue|continue\\s+playing|unpause)$", RegexOption.IGNORE_CASE)
    private val pauseRegex = Regex("^(?:pause|pause\\s+(?:the\\s+)?(?:music|song|track|playback)|stop\\s+music)$", RegexOption.IGNORE_CASE)
    private val nextRegex = Regex("^(?:next|next\\s+song|skip|skip\\s+song|next\\s+track)$", RegexOption.IGNORE_CASE)
    private val prevRegex = Regex("^(?:previous|prev|previous\\s+song|last\\s+song|back\\s+a\\s+song)$", RegexOption.IGNORE_CASE)

    private val volUpRegex = Regex("^(?:volume\\s+up|louder|turn\\s+it\\s+up|increase\\s+volume|make\\s+it\\s+louder)$", RegexOption.IGNORE_CASE)
    private val volDownRegex = Regex("^(?:volume\\s+down|quieter|turn\\s+it\\s+down|decrease\\s+volume|lower\\s+volume)$", RegexOption.IGNORE_CASE)
    private val volMuteRegex = Regex("^(?:mute|mute\\s+volume|mute\\s+audio|silence)$", RegexOption.IGNORE_CASE)
    private val volUnmuteRegex = Regex("^(?:unmute|unmute\\s+volume|unmute\\s+audio)$", RegexOption.IGNORE_CASE)
    private val typeRegex = Regex("^(?:type|type\\s+out|enter|write)\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val setDefaultMusicRegex1 = Regex("^(?:songs?|music)\\s+(?:play|plays|should\\s+play|open|opens)\\s+(?:on|in|using|via)\\s+(.+?)(?:\\s+by\\s+default)?$", RegexOption.IGNORE_CASE)
    private val setDefaultMusicRegex2 = Regex("^(?:set|make|use)\\s+(?:default\\s+)?(?:music|songs?|music\\s+player)\\s+(?:to|app\\s+to|player\\s+to|is)?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val setDefaultReelsRegex1 = Regex("^(?:reels|videos?|vids?)\\s+(?:play|plays|should\\s+play|open|opens)\\s+(?:on|in|using|via)\\s+(.+?)(?:\\s+by\\s+default)?$", RegexOption.IGNORE_CASE)
    private val setDefaultReelsRegex2 = Regex("^(?:set|make|use)\\s+(?:default\\s+)?(?:reels|videos?|vids?)\\s+(?:to|app\\s+to|player\\s+to|is)?\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val muteProactivityRegex = Regex("^(?:shut\\s+up|be\\s+quiet|capri\\s+stop|enough|stop\\s+talking)$", RegexOption.IGNORE_CASE)
    private val unmuteProactivityRegex = Regex("^(?:come\\s+back|you\\s+can\\s+talk|speak\\s+again|capri\\s+talk)$", RegexOption.IGNORE_CASE)
    private val clearHistoryRegex = Regex("^(?:clear\\s+history|forget\\s+everything|wipe\\s+memory|clear\\s+chat|reset\\s+memory)$", RegexOption.IGNORE_CASE)
    private val goHomeRegex = Regex("^(?:go\\s+home|close\\s+app|minimize|go\\s+to\\s+home\\s+screen)$", RegexOption.IGNORE_CASE)

    private val complimentKeywords = setOf("cute", "smart", "cool", "best", "awesome", "love you", "pretty", "amazing", "good job", "great", "genius", "sweet")

    private val knownAppNames = setOf(
        "tiktok", "spotify", "youtube", "yt", "instagram", "discord", "whatsapp", "settings", "chrome", "maps", "reddit", "twitter", "telegram", "facebook", "mornye"
    )

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

        typeRegex.find(cleaned)?.let { match ->
            val textToType = match.groups[1]?.value?.trim() ?: ""
            if (textToType.isNotEmpty()) {
                return Cmd.TypeText(textToType)
            }
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

        if (!raw.contains(" ") && raw.length <= 25) {
            return Cmd.Open(raw)
        }

        return Cmd.Unknown(raw)
    }
}
