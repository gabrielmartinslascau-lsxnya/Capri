package com.dot.capri.intents

import android.content.Context
import android.content.Intent
import java.util.Locale

object AppChooser {
    data class Option(val label: String, val pkg: String)

    /** Set when a name matched several apps. BubbleService asks which one; the user answers with a number. */
    @Volatile
    var pending: List<Option>? = null

    private val UI_NON_APP_WORDS = setOf(
        "library", "your library", "search", "home", "menu", "tab", "button", "screen",
        "page", "list", "song", "songs", "music", "track", "tracks", "playlist", "playlists",
        "album", "albums", "bottom", "top", "left", "right", "down", "up", "one", "it",
        "that", "this", "already", "playing", "scroll", "click", "tap", "pick", "choose"
    )

    fun launchers(context: Context): List<Option> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { Option(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
    }

    fun open(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts ONLY an explicit app name from a user command (e.g. "open mornye, go in library..." -> "mornye").
     * Never returns UI navigation words like "library", "scroll", "down", "pick", "song", "bottom".
     */
    fun extractExplicitAppName(rawGoal: String): String? {
        val cleaned = rawGoal.trim()
            .replace(Regex("^(?:(?:hey|yo|ok)\\s+)?capri[,!.:]?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:(?:can|could|will|would)\\s+you\\s+|please\\s+|i\\s+want\\s+you\\s+to\\s+|just\\s+)+", RegexOption.IGNORE_CASE), "")
            .trim()

        // 1. "In <app>: <goal>"
        Regex("^in\\s+([a-z0-9_]+(?:\\s+[a-z0-9_]+)?)\\s*:", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)?.trim()?.lowercase(Locale.US)?.let { candidate ->
                if (candidate !in UI_NON_APP_WORDS) return candidate
            }

        // 2. "open/launch/start/go on/go to/run <app> [, / and / then / to ...]"
        val openLead = Regex(
            "^(?:open|launch|start|run|go\\s+(?:on|to|in))\\s+(?:the\\s+)?(?:app\\s+)?([a-z0-9_]+(?:\\s+[a-z0-9_]+)?)(?:\\s+app)?(?=$|,|\\s+(?:and|then|to|or|go|in|on|scroll|click|tap|pick|choose|play|find|search)\\b)",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
        if (openLead != null) {
            val candidate = openLead.groupValues[1].trim().lowercase(Locale.US)
            if (candidate.isNotEmpty() && candidate !in UI_NON_APP_WORDS) {
                return candidate
            }
        }

        // 3. "... on/in/using <app>" at the very end of the sentence (only if not "on the bottom/screen/top")
        val endApp = Regex(
            "\\b(?:on|in|using|via)\\s+(?:the\\s+)?([a-z0-9_]+)(?:\\s+app)?$",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
        if (endApp != null) {
            val candidate = endApp.groupValues[1].trim().lowercase(Locale.US)
            if (candidate.isNotEmpty() && candidate !in UI_NON_APP_WORDS) {
                return candidate
            }
        }

        return null
    }

    /**
     * Resolves an app name against installed launcher apps using strict priority tiers
     * (exact label -> whole-word in label -> label prefix/substring -> exact package suffix)
     * so a weak substring match on an unrelated 3rd-party package never beats the real app.
     */
    fun findBestMatch(context: Context, rawAppName: String): Option? {
        val query = rawAppName.trim().lowercase(Locale.US).removeSuffix(" app").trim()
        if (query.isEmpty() || query in UI_NON_APP_WORDS) return null

        val all = launchers(context)

        // Tier 1: Exact label match (e.g. "mornye" == "Mornye")
        all.firstOrNull { it.label.trim().lowercase(Locale.US) == query }?.let { return it }

        // Tier 2: Whole-word match inside label
        all.firstOrNull { opt ->
            val words = opt.label.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            query in words
        }?.let { return it }

        // Tier 3: Label starts with query
        if (query.length >= 3) {
            all.firstOrNull { it.label.trim().lowercase(Locale.US).startsWith(query) }?.let { return it }
        }

        // Tier 4: Label contains query (minimum 4 chars)
        if (query.length >= 4) {
            all.firstOrNull { it.label.lowercase(Locale.US).contains(query) }?.let { return it }
        }

        // Tier 5: Package's final segment equals query (e.g. com.example.mornye -> "mornye")
        if (query.length >= 4) {
            all.firstOrNull { it.pkg.lowercase(Locale.US).substringAfterLast('.') == query }?.let { return it }
        }

        return null
    }

    /** Exact app labels for the model, so it can copy them instead of guessing. */
    fun labelsForPrompt(context: Context, max: Int = 120): String =
        launchers(context).map { it.label }.distinct().sorted().take(max).joinToString(", ")
}
