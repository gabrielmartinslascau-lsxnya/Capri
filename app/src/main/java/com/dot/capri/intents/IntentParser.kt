package com.dot.capri.intents

object IntentParser {
    private val playOnRegex = Regex("^play\\s+(.+?)\\s+on\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val playRegex = Regex("^play\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val callRegex = Regex("^(?:call|phone|dial)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val textRegex = Regex("^(?:text|message|whatsapp|send\\s+a\\s+text\\s+to|send\\s+message\\s+to)\\s+(.+?)(?:\\s+(?:saying|that|to\\s+say)\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val openRegex = Regex("^(?:open|launch|start|go to|run)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val conversationalOpenRegex = Regex("^(?:can\\s+i\\s+|i\\s+want\\s+to\\s+|let's\\s+|let\\s+us\\s+)(?:watch|use|view|open|check|go\\s+to)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val searchRegex = Regex("^(?:search|google|look up)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val seeScreenRegex = Regex("^(?:what(?:'s|\\s+is)\\s+(?:on\\s+screen|here|what\\s+am\\s+i\\s+looking\\s+at)|look\\s+at\\s+(?:the\\s+)?screen|search\\s+what(?:'s|\\s+is)\\s+on\\s+screen|analyze\\s+screen|screen\\s+capture)(?:\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val greetingRegex = Regex("^(?:hi|hello|hey|sup|yo|good\\s+(?:morning|afternoon|evening)|how\\s+are\\s+you)(?:\\s+.*)?$", RegexOption.IGNORE_CASE)

    private val muteProactivityRegex = Regex("^(?:shut\\s+up|be\\s+quiet|capri\\s+stop|enough|stop\\s+talking)$", RegexOption.IGNORE_CASE)
    private val unmuteProactivityRegex = Regex("^(?:come\\s+back|you\\s+can\\s+talk|speak\\s+again|capri\\s+talk)$", RegexOption.IGNORE_CASE)
    private val clearHistoryRegex = Regex("^(?:clear\\s+history|forget\\s+everything|wipe\\s+memory|clear\\s+chat|reset\\s+memory)$", RegexOption.IGNORE_CASE)

    private val complimentKeywords = setOf("cute", "smart", "cool", "best", "awesome", "love you", "pretty", "amazing", "good job", "great", "genius", "sweet")

    private val knownAppNames = setOf(
        "tiktok", "spotify", "youtube", "yt", "instagram", "discord", "whatsapp", "settings", "chrome", "maps", "reddit", "twitter", "telegram", "facebook"
    )

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

        seeScreenRegex.find(cleaned)?.let { match ->
            val prompt = match.groups[1]?.value?.trim() ?: "what is on this screen?"
            return Cmd.SeeScreen(prompt)
        }

        playOnRegex.find(cleaned)?.let { match ->
            val (q, app) = match.destructured
            return Cmd.Play(q.trim(), app.trim().removeSuffix("app").trim())
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
            return Cmd.Play(query, null)
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
