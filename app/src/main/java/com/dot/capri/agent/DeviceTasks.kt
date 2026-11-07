package com.dot.capri.agent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.dot.capri.intents.AppChooser
import java.util.Locale

/**
 * Decides whether a message asks Capri to DO something on the phone or PLAY a game.
 * Questions stay in chat. Action and game requests are executed directly ("just do it").
 */
object DeviceTasks {

    private val leadingCapri = Regex("^(?:hey\\s+|yo\\s+|ok\\s+)?capri[,!.:]?\\s+", RegexOption.IGNORE_CASE)

    // Starts like a pure information question: keep it in chat unless it's "can you / could you / will you"
    private val questionStart = Regex(
        "^(?:how|what|why|when|where|who|which|is|are|was|were|does|do you know|do i|should|tell me about|explain)\\b.*",
        RegexOption.IGNORE_CASE
    )

    // Action words and on-screen things, anywhere in the sentence.
    private val actionWord = Regex(
        "\\b(open|opening|go|going|click|clicking|tap|tapping|press|scroll|scrolling|search|searching|" +
                "type|typing|take|turn|launch|find|send|check|use|enable|disable|select|choose|pick|run|play|playing|beat|win|mine|plant|build|start)\\b",
        RegexOption.IGNORE_CASE
    )

    private val deviceNoun = Regex(
        "\\b(app|apps|page|pages|screen|button|tab|link|search|google|github|repo|repos|settings|chrome|" +
                "gmail|youtube|whatsapp|spotify|mornye|library|song|songs|music|playlist|playlists|track|tracks|album|player|" +
                "camera|calculator|maps|list|menu|icon|drawer|home|launcher|bottom|top|" +
                "phone|wifi|bluetooth|notification|notifications|message|messages|contact|contacts|" +
                "game|games|level|levels|match|round|world|stage|board|quest)\\b",
        RegexOption.IGNORE_CASE
    )

    private val directGameRegex = Regex(
        "^(?:(?:can|could|will|would)\\s+you\\s+|please\\s+|i\\s+want\\s+you\\s+to\\s+|just\\s+|go\\s+ahead\\s+and\\s+)?" +
                "(?:play\\s+(?:this|the|a|my)?\\s*game|play\\s+for\\s+me|play\\s+it|take\\s+over(?:\\s+the\\s+screen)?|" +
                "control\\s+the\\s+screen|play\\s+on\\s+screen|help\\s+me\\s+(?:win|play|beat)|" +
                "win\\s+(?:this|the)\\s+(?:game|level|match)|beat\\s+(?:this|the)\\s+(?:game|level)|" +
                "start\\s+(?:the\\s+)?game|resume\\s+(?:the\\s+)?game)(?:\\b.*)?$",
        RegexOption.IGNORE_CASE
    )

    private val explicitMusicRegex = Regex(
        "\\b(song|songs|music|track|tracks|album|artist|playlist|liked\\s+songs|library|mornye|spotify|youtube\\s+music|yt\\s+music|soundcloud|apple\\s+music|listen\\s+to)\\b",
        RegexOption.IGNORE_CASE
    )

    /** True when the user is asking Capri to play a game (either the current screen or a named game). */
    fun isGamePlayRequest(raw: String, context: Context? = null): Boolean {
        val t = raw.trim().replace(leadingCapri, "").trim()
        if (t.isEmpty() || questionStart.matches(t)) return false
        if (explicitMusicRegex.containsMatchIn(t)) return false
        if (directGameRegex.matches(t)) return true

        val playTargetMatch = Regex(
            "^(?:(?:can|could|will|would)\\s+you\\s+|please\\s+|just\\s+)?(?:play|beat|win|hop\\s+on|start\\s+playing)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(t)
        if (playTargetMatch != null && context != null) {
            val target = playTargetMatch.groupValues[1].trim()
            if (resolveInstalledGame(context, target) != null) return true
        }
        return false
    }

    /**
     * Resolves a user query (e.g. app or game title)
     * to an installed game Option if one is installed on the device.
     */
    fun resolveInstalledGame(context: Context, rawText: String): AppChooser.Option? {
        val cleaned = rawText.trim().replace(leadingCapri, "").lowercase(Locale.US)
        if (explicitMusicRegex.containsMatchIn(cleaned)) return null

        val pm = context.packageManager

        fun isGameOption(opt: AppChooser.Option): Boolean {
            return try {
                val appInfo = pm.getApplicationInfo(opt.pkg, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appInfo.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    @Suppress("DEPRECATION")
                    (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                }
            } catch (_: Exception) {
                false
            }
        }

        val explicitName = AppChooser.extractExplicitAppName(cleaned) ?: cleaned
            .replace(Regex("^(?:(?:can|could|will|would)\\s+you\\s+|please\\s+|just\\s+)*(?:play|beat|win|open|launch|start|run)\\s+(?:the\\s+)?(?:game\\s+)?"), "")
            .substringBefore(",")
            .substringBefore(" and ")
            .trim()

        val match = AppChooser.findBestMatch(context, explicitName)
        if (match != null && isGameOption(match)) {
            return match
        }
        return null
    }

    /** An action word and an on-screen thing both appear somewhere in the sentence. */
    fun mentionsDeviceWork(raw: String, context: Context? = null): Boolean {
        val t = raw.trim().replace(leadingCapri, "").trim()
        if (t.isEmpty() || questionStart.matches(t)) return false
        if (isGamePlayRequest(t, context)) return true
        return actionWord.containsMatchIn(t) && deviceNoun.containsMatchIn(t)
    }
}
