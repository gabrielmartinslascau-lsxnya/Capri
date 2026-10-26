package com.dot.capri.assist

import android.service.voice.VoiceInteractionService
import android.util.Log

class CapriVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.d("CAPRI_ASSIST", "CapriVoiceInteractionService onReady")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d("CAPRI_ASSIST", "CapriVoiceInteractionService onShutdown")
    }
}
