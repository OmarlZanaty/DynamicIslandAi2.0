package com.almobarmg.dynamicislandai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class NotificationService : NotificationListenerService() {

    // *** REMOVED: lateinit var dynamicIslandManager ***

    override fun onListenerConnected() {
        super.onListenerConnected()
        // No need to get manager instance here anymore
        Log.i(TAG, "✅ Notification listener connected.")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "dynamic_island_channel")
            .setContentTitle("Dynamic Island AI")
            .setContentText("Running in background")
            .setSmallIcon(R.drawable.ic_notification) // Ensure this is a valid drawable
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Prevent dismissal
            .setSilent(true) // Avoid sound/vibration
            .build()
        try {
            startForeground(1, notification)
            Log.d("NotificationService", "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e("NotificationService", "Failed to start foreground service", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "dynamic_island_channel",
                "Dynamic Island Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for Dynamic Island foreground service"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d("NotificationService", "Notification channel created")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // *** ADDED: Log EVERY notification received ***
        Log.d(TAG, "DIAGNOSTIC: onNotificationPosted received key=${sbn?.key}, pkg=${sbn?.packageName}")

        if (sbn == null) {
            Log.w(TAG, "⚠️ Received null StatusBarNotification object.")
            return
        }

        // *** ADDED: Get DynamicIslandManager instance directly here ***
        val dynamicIslandManager: DynamicIslandManager?
        try {
            dynamicIslandManager = DynamicIslandManager.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get DynamicIslandManager instance in onNotificationPosted", e)
            return // Cannot proceed without the manager
        }


        // --- Data Extraction ---
        val notification = sbn.notification
        if (notification == null) {
            Log.w(TAG, "⚠️ Received StatusBarNotification with null Notification object: ${sbn.key}")
            return
        }

        val extras = notification.extras
        if (extras == null) {
            Log.w(TAG, "⚠️ Notification has null extras: ${sbn.key}")
            return
        }

        val packageName = sbn.packageName ?: "Unknown Package"
        val title = extras.getString("android.title", "")
        val text = extras.getString("android.text", "")
        val pendingIntent: PendingIntent? = notification.contentIntent

        Log.i(TAG, "📩 Processing Notification: [${packageName}] Title=\"${title}\", Text=\"${text}\", HasIntent=${pendingIntent != null}")

        // --- Temporarily Relaxed Blank Check for Debugging ---
        /*
        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            Log.w(TAG, "⚠️ Notification has blank title and text. Ignoring. Key: ${sbn.key}")
            return
        }
        */

        // --- Cancel Original Notification & Show in Island ---
        try {
            // Only cancel if it's not our own package (redundant if filter above is active)
            if (sbn.packageName != applicationContext.packageName) {
                cancelNotification(sbn.key)
                Log.d(TAG, "🚫 System notification canceled: ${sbn.key}")
            } else {
                Log.d(TAG, "🚫 Not canceling own notification: ${sbn.key}")
            }

            val displayTitle = title.ifBlank { "Notification (No Title)" } // Use default if blank
            val displayText = text.ifBlank { "(No Text)" } // Use default if blank

            // Use the manager instance obtained earlier
            dynamicIslandManager?.showNotification(displayTitle, displayText, pendingIntent)
            Log.d(TAG, "🏝️ Displaying notification in Island: Title=\"$displayTitle\", Text=\"$displayText\", Intent=${pendingIntent}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing or displaying notification: ${sbn.key}", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        Log.d(TAG, "🗑️ Notification removed: ${sbn?.key}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "🔌 Notification listener disconnected!")
    }

    companion object {
        private const val TAG = "DynamicIslandNotifSvc"
    }
}

