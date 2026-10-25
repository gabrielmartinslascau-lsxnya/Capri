package com.dot.capri.proactivity

import android.content.Context

data class CommentEvent(val comment: String, val appPackage: String)

interface Trigger {
    val id: String
    fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent?
    fun getFireCountToday(): Int
    fun resetFireCount()
    fun resetCooldown()
    fun getDetailedStatus(context: Context, currentApp: String?, now: Long, fastMode: Boolean): String
}
