package com.dot.capri

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        findViewById<ImageButton>(R.id.btn_tutorial_close)?.setOnClickListener {
            finish()
        }

        val content = """
            1. CONVERSING & SCREEN READING
            • Ask anything naturally: "how are you doing?", "give me dinner ideas".
            • Screen Reading: "can you see my screen?", "what's on my screen?".
            • Notifications: "what did I miss?" (summarizes recent notifications).
            • Currently Playing Song: "what song is playing?", "who is singing?".
            • Battery Level: "what is my battery level?".

            2. GESTURE & CURSOR CONTROLS
            • Scrolling / Swiping: "scroll down", "scroll up", "scroll left", "scroll right".
            • Repeat Swipes: "scroll left twice", "swipe down 3 times".
            • Tapping / Clicking: "click on Search", "tap Download", "press Play".
            • Click & Hold: "long press the image", "press and hold Settings".

            3. AUTONOMOUS TASKS (task: ...)
            • Prefix multi-step requests with "task:" or "agent:".
            • Capri shows her glowing pink cursor ring overlay, analyzes screen layout & vision screenshots, and executes steps automatically.
            • Examples:
              - task: open Spotify and search for synthwave
              - task: open Mornye, search for song, click download, select Dolby Atmos, and go home
            • Tap the "STOP" pill at the top of the screen at any time to cancel.

            4. APP LAUNCHING & SELECTION
            • Say: "open Spotify", "launch WhatsApp", "go to Google".
            • If an app name matches multiple installed apps, Capri asks with a numbered list. Reply with a number (e.g. "1") to open.

            5. CALLS & MESSAGING
            • Calls: "call Mom", "dial 123456789".
            • WhatsApp: "text Alex saying on my way". Capri pre-fills the message and asks for confirmation ("ready to send? yes/no").

            6. RECORDING & REPLAYING MACROS
            • Record: Say "record macro <name>" (e.g. "record macro my_workout").
            • Perform: Tap, scroll, or type in your apps normally.
            • Save: Say "stop recording".
            • Replay: Say "<name>" anytime (e.g. "my_workout") to let Capri replay your exact steps automatically.

            7. MEDIA & VOLUME CONTROLS
            • Play: "play lofi on Spotify", "pause music", "next song".
            • Volume: "volume up", "louder", "volume down", "mute".
        """.trimIndent()

        findViewById<TextView>(R.id.tv_tutorial_content)?.text = content
    }
}
