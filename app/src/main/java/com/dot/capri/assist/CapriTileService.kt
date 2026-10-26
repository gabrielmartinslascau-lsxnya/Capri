package com.dot.capri.assist

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.dot.capri.BubbleService

class CapriTileService : TileService() {

    override fun onClick() {
        super.onClick()
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
    }
}
