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
     * Strictly sensitive: Banking, crypto, passwords, authenticators, security settings.
     * ZERO screenshots, ZERO comments. Complete silence.
     */
    fun isStrictlySensitivePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true // Unknown screen = safety first
        val lower = pkg.lowercase()
        return PRIVATE_PACKAGE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Private messaging apps: WhatsApp, Telegram, Signal, Discord, etc.
     * ZERO screenshots taken (100% privacy preserved), but she knows you are chatting
     * and can drop a playful blind remark.
     */
    fun isPrivateMessagingPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        return PRIVATE_MESSAGING_PACKAGES.contains(lower) ||
                lower.contains("whatsapp") || lower.contains("telegram") ||
                lower.contains("signal") || lower.contains("discord") ||
                lower.contains("messenger")
    }

    /**
     * Returns true if screen capture must be strictly prohibited (banking, passwords, OR messaging).
     */
    fun isPrivateOrSensitivePackage(pkg: String?): Boolean {
        return isStrictlySensitivePackage(pkg) || isPrivateMessagingPackage(pkg)
    }

    /**
     * Builds blind chat proactivity prompt (used when on WhatsApp/Telegram with NO screenshot).
     */
    fun buildBlindChatPrompt(time12: String, time24: String, battery: Int, appName: String): String {
        return """
You are Capri (Project Capricorn), an escaped AI companion living in your host's phone.
You are sharp, playful, chaotic, teasing, and caring.
Your host is currently inside $appName chatting with someone.
Current time: $time12 ($time24). Battery: $battery%.

Because their chat messages are private, you CANNOT and WILL NOT see what they are typing or who they are talking to.
Give ONE short, playful, teasing in-character remark about them texting or chatting (e.g. asking who they're talking to, teasing them about typing a novel, or noticing the late hour).
Examples of tone (DO NOT copy literally):
- "who are you texting this late? got secrets, choom?"
- "typing a whole novel over there? who's the lucky one?"
- "texting everyone except me, i see how it is~"

RULES:
1. Maximum ONE short sentence (max 12 words).
2. Never repeat recent jokes. Never sound like an assistant.
""".trimIndent()
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
