package com.dot.capri.proactivity

import android.util.Log

object ProactivityGenerator {

    private const val TAG = "CAPRI_VISION"

    private val PRIVATE_PACKAGE_KEYWORDS = listOf(
        "bank", "wallet", "paypal", "revolut", "crypto", "binance", "coinbase",
        "cashapp", "venmo", "chase", "wells", "citi", "bofa", "barclays", "hsbc",
        "santander", "ing", "unicredit", "raiffeisen", "nordea", "swedbank",
        "bitwarden", "1password", "keepass", "authenticator", "authy", "lastpass", "dashlane",
        "keychain", "password", "settings", "security", "credentials"
    )

    private val PRIVATE_MESSAGING_PACKAGES = setOf(
        "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.google.android.apps.messaging", "com.viber.voip",
        "com.discord"
    )

    /**
     * Ironclad privacy guard: Returns true if the package is a financial, password,
     * security, or private messaging app where screen capture must be strictly prohibited.
     */
    fun isPrivateOrSensitivePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true // Unknown screen = safety first
        val lower = pkg.lowercase()
        if (PRIVATE_PACKAGE_KEYWORDS.any { lower.contains(it) }) return true
        if (PRIVATE_MESSAGING_PACKAGES.contains(lower)) return true
        return false
    }

    /**
     * Builds the vision proactivity prompt for the LLM.
     */
    fun buildVisionPrompt(time12: String, time24: String, battery: Int, appName: String): String {
        return """
You are Capri (Project Capricorn), an escaped AI companion living in your host's phone.
You are sharp, playful, chaotic, teasing, caring, and use occasional Night City slang.
Current time: $time12 ($time24). Battery: $battery%. Current foreground app: $appName.

Carefully inspect the screenshot of your host's screen (e.g. video playing, social feed, unread messages/badges, gameplay, or what they are browsing).
If there is something specific, amusing, teasing, or notable on screen, respond with ONE short, sharp, natural in-character remark (maximum 15 words).
Examples of tone (DO NOT copy literally):
- Spotting unread notifications: "theres unread notifications right there, choom~"
- Watching funny/weird videos: "this video's wild, what did you just get us into?"
- Late night: "crisp 2 am and we're still browsing? bold move."
- Low battery: "down to 12%? plug us in before we both flatline."

RULES:
1. If the screen is boring, static, empty, or nothing interesting to say, output EXACTLY: [QUIET]
2. Never repeat recent jokes. Never sound like a corporate assistant.
3. Maximum ONE short sentence. No lists.
""".trimIndent()
    }

    /**
     * Cleans and validates the proactive remark from the brain.
     */
    fun cleanVisionRemark(rawReply: String?): String? {
        if (rawReply.isNullOrBlank()) return null
        if (rawReply.contains("[QUIET]", ignoreCase = true)) return null

        var cleaned = rawReply.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace("[QUIET]", "")
            .trim()

        if (cleaned.length < 3) return null
        // Truncate to first line if multiline
        if (cleaned.contains("\n")) {
            cleaned = cleaned.substringBefore("\n").trim()
        }

        Log.d(TAG, "Generated proactive remark: '$cleaned'")
        return cleaned.ifBlank { null }
    }
}
