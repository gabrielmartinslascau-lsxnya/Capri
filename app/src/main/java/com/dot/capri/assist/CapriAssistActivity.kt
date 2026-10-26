package com.dot.capri.assist

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dot.capri.BubbleService

class CapriAssistActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("CAPRI_ASSIST", "CapriAssistActivity onCreate triggered with intent action: ${intent?.action}")

        val service = BubbleService.instance
        if (service != null) {
            service.expandToChat()
        } else {
            val intent = Intent(this, BubbleService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Handler(Looper.getMainLooper()).postDelayed({
                BubbleService.instance?.expandToChat()
            }, 300)
        }
        finish()
    }
}
