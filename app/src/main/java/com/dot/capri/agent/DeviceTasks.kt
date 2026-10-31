package com.dot.capri.agent

/**
 * Decides whether a message asks Capri to DO something on the phone.
 * Questions stay in chat. Requests are caught anywhere in the sentence.
 */
object DeviceTasks {

    private val leadingCapri = Regex("^capri[,!.]?\\s+", RegexOption.IGNORE_CASE)

    // Starts like a question: keep it in chat.
    private val questionStart = Regex(
        "^(?:how|what|why|when|where|who|which|is|are|was|were|does|do you|do i|should|tell me about)\\b.*",
        RegexOption.IGNORE_CASE
    )

    // Action words and on-screen things, anywhere in the sentence.
    private val actionWord = Regex(
        "\\b(open|opening|go|going|click|clicking|tap|tapping|press|scroll|scrolling|search|searching|" +
                "type|typing|take|turn|launch|find|send|check|use|enable|disable|select|choose|pick|run|play)\\b",
        RegexOption.IGNORE_CASE
    )

    private val deviceNoun = Regex(
        "\\b(app|apps|page|pages|screen|button|tab|link|search|google|github|repo|repos|settings|chrome|" +
                "gmail|youtube|whatsapp|spotify|camera|calculator|maps|list|menu|icon|drawer|home|launcher|" +
                "phone|wifi|bluetooth|notification|notifications|message|messages|contact|contacts)\\b",
        RegexOption.IGNORE_CASE
    )

    /** An action word and an on-screen thing both appear somewhere in the sentence. */
    fun mentionsDeviceWork(raw: String): Boolean {
        val t = raw.trim().replace(leadingCapri, "")
        if (t.isEmpty() || questionStart.matches(t)) return false
        return actionWord.containsMatchIn(t) && deviceNoun.containsMatchIn(t)
    }
}
