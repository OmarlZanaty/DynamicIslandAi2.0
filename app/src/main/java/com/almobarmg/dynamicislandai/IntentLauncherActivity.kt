package com.almobarmg.dynamicislandai

import android.app.Activity
import android.app.PendingIntent
import android.os.Bundle
import android.util.Log

// IntentLauncherActivity.kt
class IntentLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingIntent = intent.getParcelableExtra<PendingIntent>("pending_intent")
        try {
            pendingIntent?.send()
        } catch (e: PendingIntent.CanceledException) {
            Log.e("IntentLauncher", "PendingIntent canceled", e)
        }
        finish()
    }
}