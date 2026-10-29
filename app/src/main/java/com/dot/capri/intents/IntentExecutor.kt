package com.dot.capri.intents

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import com.dot.capri.CapriAccessibilityService
import com.dot.capri.MainActivity
import com.dot.capri.util.ContactUtils
import com.dot.capri.util.MediaUtils
import java.net.URLEncoder

object IntentExecutor {

    private val appPackageMap = mapOf(
        "spotify" to "com.spotify.music",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "ytmusic" to "com.google.android.apps.youtube.music",
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
                is Cmd.MediaControl -> executeMediaControl(context, cmd)
                is Cmd.Volume -> executeVolume(context, cmd)
                is Cmd.TypeText -> executeTypeText(cmd)
                is Cmd.Write -> com.dot.capri.accessibility.ScreenWriter.writeToFocusedField(cmd.text) is com.dot.capri.accessibility.WriteResult.Success
                is Cmd.ClearField -> com.dot.capri.accessibility.ScreenWriter.clearFocusedField() is com.dot.capri.accessibility.WriteResult.Success
                is Cmd.Call -> executeCall(context, cmd)
                is Cmd.Text -> executeText(context, cmd)
                is Cmd.Open -> executeOpen(context, cmd)
                is Cmd.SetDefaultApp -> executeSetDefaultApp(context, cmd)
                is Cmd.GoHome -> executeGoHome(context)
                is Cmd.Search -> false
                is Cmd.SeeScreen -> true
                is Cmd.Scroll -> true
                is Cmd.Tap -> true
                is Cmd.ClearHistory -> true
                is Cmd.EasterEgg -> true
                is Cmd.CheckNotifications -> true
                is Cmd.Chat, is Cmd.Compliment, is Cmd.Mention, Cmd.MuteProactivity, Cmd.UnmuteProactivity -> true
                is Cmd.Unknown -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun executeVolume(context: Context, cmd: Cmd.Volume): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val direction = when (cmd.direction) {
            VolumeDirection.UP -> AudioManager.ADJUST_RAISE
            VolumeDirection.DOWN -> AudioManager.ADJUST_LOWER
            VolumeDirection.MUTE -> AudioManager.ADJUST_MUTE
            VolumeDirection.UNMUTE -> AudioManager.ADJUST_UNMUTE
        }
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private fun executeTypeText(cmd: Cmd.TypeText): Boolean {
        val accessibilityService = CapriAccessibilityService.instance ?: return false
        return accessibilityService.typeTextIntoFocusedField(cmd.text)
    }

    private fun executeMediaControl(context: Context, cmd: Cmd.MediaControl): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val keycode = when (cmd.action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }

        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
        return true
    }

    private fun executeGoHome(context: Context): Boolean {
        val accessibilityService = CapriAccessibilityService.instance
        if (accessibilityService != null) {
            return accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(homeIntent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun executeSetDefaultApp(context: Context, cmd: Cmd.SetDefaultApp): Boolean {
        if (cmd.category == "music") {
            MainActivity.setDefaultMusicPlayer(context, cmd.appName)
            return true
        } else if (cmd.category == "reels") {
            MainActivity.setDefaultReelsApp(context, cmd.appName)
            return true
        }
        return false
    }

    private fun executePlay(context: Context, cmd: Cmd.Play): Boolean {
        val rawQuery = cmd.query.trim().lowercase()

        // Handle short video / reels queries ("play video", "play a vid", "reels")
        if (rawQuery in setOf("reels", "video", "vid", "shorts", "tiktok", "a video", "a vid")) {
            val reelsApp = MainActivity.getDefaultReelsApp(context)
            return executeOpen(context, Cmd.Open(reelsApp))
        }

        // Handle random song queries ("play something", "play a song")
        val randomTracks = listOf(
            "Cyberpunk 2077 I Really Want to Stay at Your House",
            "HOME Resonance",
            "Kavinsky Nightcall",
            "Tame Impala The Less I Know The Better",
            "The Weeknd Blinding Lights",
            "Daft Punk One More Time"
        )
        val finalQuery = if (rawQuery in setOf("something", "a song", "music", "a track", "a tune", "songs")) {
            randomTracks.random()
        } else {
            cmd.query.trim()
        }

        val encodedQuery = URLEncoder.encode(finalQuery, "UTF-8")

        val rawChoice = if (!cmd.app.isNullOrBlank()) {
            cmd.app.lowercase().trim()
        } else {
            if (MediaUtils.isTrackDownloadedLocally(context, finalQuery)) {
                "local"
            } else {
                MainActivity.getDefaultMusicPlayer(context).lowercase().trim()
            }
        }

        val playerChoice = when {
            rawChoice.contains("spotify") -> "spotify"
            rawChoice.contains("ytmusic") || rawChoice.contains("youtube music") || rawChoice.contains("yt music") -> "ytmusic"
            rawChoice.contains("apple") -> "apple"
            rawChoice.contains("soundcloud") -> "soundcloud"
            rawChoice.contains("deezer") -> "deezer"
            rawChoice.contains("local") || rawChoice.contains("offline") -> "local"
            rawChoice.contains("youtube") -> "youtube"
            else -> rawChoice
        }

        val intent: Intent = when (playerChoice) {
            "spotify" -> {
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encodedQuery"))
            }
            "ytmusic" -> {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encodedQuery"))
            }
            "apple" -> {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://music.apple.com/search?term=$encodedQuery"))
            }
            "soundcloud" -> {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://soundcloud.com/search?q=$encodedQuery"))
            }
            "deezer" -> {
                Intent(Intent.ACTION_VIEW, Uri.parse("deezer://www.deezer.com/search/$encodedQuery"))
            }
            "local" -> {
                val audioUri = MediaUtils.findLocalAudioUri(context, finalQuery)
                if (audioUri != null) {
                    val playFileIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(audioUri, "audio/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    return try {
                        context.startActivity(playFileIntent)
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                } else {
                    val localMusicIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_MUSIC)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    return try {
                        context.startActivity(localMusicIntent)
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
            }
            else -> {
                if (!cmd.app.isNullOrBlank() && executeOpen(context, Cmd.Open(playerChoice))) {
                    return true
                }
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery"))
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            if (playerChoice == "spotify") {
                val pm = context.packageManager
                val spotifyIntent = pm.getLaunchIntentForPackage("com.spotify.music")
                if (spotifyIntent != null) {
                    spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(spotifyIntent)
                    return true
                }
            }
            e.printStackTrace()
            false
        }
    }

    private fun executeCall(context: Context, cmd: Cmd.Call): Boolean {
        val targetNumber = if (cmd.contact.any { it.isLetter() }) {
            ContactUtils.findPhoneNumberByName(context, cmd.contact) ?: return false
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
            ContactUtils.findPhoneNumberByName(context, cmd.contact) ?: return false
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
