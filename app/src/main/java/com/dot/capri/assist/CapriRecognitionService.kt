package com.dot.capri.assist

import android.content.Intent
import android.speech.RecognitionService

class CapriRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
