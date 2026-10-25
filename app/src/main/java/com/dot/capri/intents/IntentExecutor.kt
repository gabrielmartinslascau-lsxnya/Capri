package com.dot.capri.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dot.capri.util.ContactUtils
import java.net.URLEncoder

object IntentExecutor {

    private val appPackageMap = mapOf(
        "spotify" to "com.spotify.music",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "tiktok" to "com.zhiliaoapp.musically",
        "settings" to "com.android.settings",
        "chrome" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "instagram" to "com.instagram.android",
        "discord" to "com.discord",
        "whatsapp" to "com.whatsapp"
    )

    fun execute(context: Context, cmd: Cmd): Boolean {
        return try {
            when (cmd) {
                is Cmd.Play -> executePlay(context, cmd)
                is Cmd.Call -> executeCall(context, cmd)
                is Cmd.Text -> executeText(context, cmd)
                is Cmd.Open -> executeOpen(context, cmd)
                is Cmd.Search -> false
                is Cmd.SeeScreen -> true
                is Cmd.ClearHistory -> true
                is Cmd.Chat, is Cmd.Compliment, is Cmd.Mention, Cmd.MuteProactivity, Cmd.UnmuteProactivity -> true
                is Cmd.Unknown -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun executePlay(context: Context, cmd: Cmd.Play): Boolean {
        val encodedQuery = URLEncoder.encode(cmd.query, "UTF-8")
        val appLower = cmd.app?.lowercase()

        val uriString = when (appLower) {
            "spotify" -> "spotify:search:$encodedQuery"
            "youtube", "yt" -> "https://www.youtube.com/results?search_query=$encodedQuery"
            else -> "https://www.youtube.com/results?search_query=$encodedQuery"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun executeCall(context: Context, cmd: Cmd.Call): Boolean {
        val targetNumber = if (cmd.contact.any { it.isLetter() }) {
            ContactUtils.findPhoneNumberByName(context, cmd.contact) ?: cmd.contact
        } else {
            cmd.contact
        }

        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(targetNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun executeText(context: Context, cmd: Cmd.Text): Boolean {
        val targetNumber = if (cmd.contact.any { it.isLetter() }) {
            ContactUtils.findPhoneNumberByName(context, cmd.contact) ?: cmd.contact
        } else {
            cmd.contact
        }

        val cleanNum = targetNumber.replace(Regex("[^0-9+]"), "")
        val encodedMsg = URLEncoder.encode(cmd.message ?: "", "UTF-8")

        val whatsappUri = if (cleanNum.isNotEmpty()) {
            Uri.parse("https://api.whatsapp.com/send?phone=$cleanNum&text=$encodedMsg")
        } else {
            Uri.parse("smsto:${Uri.encode(targetNumber)}")
        }

        val intent = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(targetNumber)}")).apply {
                if (!cmd.message.isNullOrBlank()) {
                    putExtra("sms_body", cmd.message)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(smsIntent)
                true
            } catch (ex: Exception) {
                ex.printStackTrace()
                false
            }
        }
    }

    private fun executeOpen(context: Context, cmd: Cmd.Open): Boolean {
        val appQuery = cmd.app.lowercase().trim()
        val pm = context.packageManager

        val mappedPkg = appPackageMap[appQuery]
        if (mappedPkg != null) {
            val intent = pm.getLaunchIntentForPackage(mappedPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)

        var targetPackage: String? = null
        for (resolveInfo in resolveInfos) {
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            val pkg = resolveInfo.activityInfo.packageName.lowercase()
            if (label == appQuery || pkg.contains(appQuery)) {
                targetPackage = resolveInfo.activityInfo.packageName
                break
            }
        }

        if (targetPackage == null) {
            for (resolveInfo in resolveInfos) {
                val label = resolveInfo.loadLabel(pm).toString().lowercase()
                if (label.contains(appQuery) || appQuery.contains(label)) {
                    targetPackage = resolveInfo.activityInfo.packageName
                    break
                }
            }
        }

        if (targetPackage != null) {
            val intent = pm.getLaunchIntentForPackage(targetPackage) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        return false
    }
}
