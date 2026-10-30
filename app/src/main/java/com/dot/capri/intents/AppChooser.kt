package com.dot.capri.intents

import android.content.Context
import android.content.Intent

object AppChooser {
    data class Option(val label: String, val pkg: String)

    /** Set when a name matched several apps. BubbleService asks which one; the user answers with a number. */
    @Volatile
    var pending: List<Option>? = null

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

    /** Exact app labels for the model, so it can copy them instead of guessing. */
    fun labelsForPrompt(context: Context, max: Int = 120): String =
        launchers(context).map { it.label }.distinct().sorted().take(max).joinToString(", ")
}
