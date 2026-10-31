package com.dot.capri.agent

/**
 * Decides whether a message asks Capri to DO something on the phone.
 * Imperative requests go to the phone agent. Questions and small talk stay in chat.
 */
object DeviceTasks {

    private val leadingCapri = Regex("^capri[,!.]?\\s+", RegexOption.IGNORE_CASE)

    // Starts like a question or a "what can you do" style message: keep it in chat.
    private val questionStart = Regex(
        "^(?:how|what|why|when|where|who|which|is|are|was|were|does|do you|do i|should|can i ask|tell me about)\\b.*",
        RegexOption.IGNORE_CASE
    )

    // Imperative verbs and polite forms, anywhere in the first few words.
    private val actionStart = Regex(
        "^(?:(?:can|could|would|will) you |please |just |now |go ahead and |and |then )?" +
                "(?:open|launch|start|close|quit|exit|click|tap|press|hit|scroll|swipe|drag|hold|long.?press|" +
                "type|write in|search|look up|find|turn|enable|disable|switch|toggle|go to|goto|navigate|" +
                "take over|take control|use|send|text|call|message|install|uninstall|delete|remove|" +
                "select|choose|pick|move|set|clear|run|do|show me|play|pause|stop playing|" +
                "reply|answer|dismiss|allow|accept|deny|cancel|back|go back|go home|home)\\b.*",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeDeviceTask(raw: String): Boolean {
        val t = raw.trim().replace(leadingCapri, "")
        if (t.isEmpty()) return false
        if (questionStart.matches(t)) return false
        return actionStart.matches(t)
    }
}
