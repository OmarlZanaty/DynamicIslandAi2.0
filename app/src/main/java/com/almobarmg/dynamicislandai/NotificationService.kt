package com.almobarmg.dynamicislandai

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Enhanced NotificationService with improved reliability:
 * 1. More robust error handling and logging
 * 2. Automatic reconnection attempts
 * 3. Better notification filtering and processing
 * 4. Passes notification key to DynamicIslandManager
 * 5. Provides static method to request notification cancellation
 * 6. Cancels original system notification after showing in island
 * 7. Ignores onNotificationRemoved to prevent premature island collapse
 */
class NotificationService : NotificationListenerService() {
    private var isConnected = false
    private var reconnectCount = 0
    private val MAX_RECONNECT_ATTEMPTS = 5

    companion object {
        private const val TAG = "DynamicIslandNotifSvc"

        // Static reference to active service instance for cancellation requests
        @Volatile private var activeService: NotificationService? = null

        // Static method to allow DynamicIslandManager to request cancellation
        fun requestCancelNotification(context: Context, key: String) {
            Log.d(TAG, "Static request to cancel notification: $key")

            // Try to use the active service instance if available
            val service = activeService
            if (service != null) {
                try {
                    service.cancelNotification(key)
                    Log.d(TAG, "Successfully canceled notification via active service: $key")
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Error canceling notification via active service", e)
                }
            }

            // Fallback: Try to parse the key and use NotificationManager
            try {
                // Key format is typically "package|id|tag|userId"
                val parts = key.split("|")
                if (parts.size >= 2) {
                    val id = parts[1].toIntOrNull()
                    val tag = if (parts.size >= 3 && parts[2] != "null") parts[2] else null // Handle null tag string

                    if (id != null) {
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        notificationManager.cancel(tag, id)
                        Log.d(TAG, "Canceled notification via NotificationManager: tag=$tag, id=$id")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing notification key or canceling via NotificationManager", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationService created.")
        activeService = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        reconnectCount = 0
        activeService = this
        Log.i(TAG, "✅ Notification listener connected.")

        // Request a rebind of the notification listener service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, NotificationService::class.java))
                Log.d(TAG, "Requested rebind of notification listener service")
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting rebind", e)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "🔌 Notification listener disconnected!")

        // Try to reconnect if we haven't exceeded the maximum attempts
        if (reconnectCount < MAX_RECONNECT_ATTEMPTS) {
            reconnectCount++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(this, NotificationService::class.java))
                    Log.d(TAG, "Attempting to reconnect notification listener (attempt $reconnectCount)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting rebind on disconnect", e)
                }
            }
        } else {
            Log.e(TAG, "Failed to reconnect notification listener after $MAX_RECONNECT_ATTEMPTS attempts")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        val binder = super.onBind(intent)
        Log.d(TAG, "NotificationService onBind called, returning binder: $binder")
        return binder
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        Log.d(TAG, "DIAGNOSTIC: onNotificationPosted received key=${sbn?.key}, pkg=${sbn?.packageName}")

        if (sbn == null) {
            Log.w(TAG, "⚠️ Received null StatusBarNotification object.")
            return
        }

        if (sbn.isOngoing) {
            Log.d(TAG, "Ignoring ongoing notification: ${sbn.key}")
            return
        }

        if (sbn.packageName == applicationContext.packageName) {
            Log.d(TAG, "Ignoring own notification: ${sbn.key}")
            return
        }

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
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getString(Notification.EXTRA_TEXT, "")
        val pendingIntent: PendingIntent? = notification.contentIntent
        val key = sbn.key // Get the notification key

        Log.i(TAG, "📩 Processing Notification: [${packageName}] Title=\"$title\", Text=\"$text\", HasIntent=${pendingIntent != null}, Key=$key")

        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            Log.w(TAG, "⚠️ Notification has blank title and text. Ignoring. Key: ${sbn.key}")
            return
        }

        val dynamicIslandManager: DynamicIslandManager?
        try {
            dynamicIslandManager = DynamicIslandManager.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get DynamicIslandManager instance in onNotificationPosted", e)
            return
        }

        try {
            // Start by ensuring the overlay service is running
            ensureOverlayServiceRunning()

            val displayTitle = title.ifBlank { "Notification" }
            val displayText = text.ifBlank { "(No Content)" }

            // Show the notification in the island, passing the key
            dynamicIslandManager.showNotification(displayTitle, displayText, pendingIntent, key)
            Log.d(TAG, "🏝️ Displaying notification in Island: Title=\"$displayTitle\", Text=\"$displayText\", Intent=${pendingIntent}, Key=$key")

            // --- FIX: Cancel original notification to prevent duplicates ---
            try {
                cancelNotification(key)
                Log.d(TAG, "🚫 System notification canceled: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel system notification $key", e)
            }
            // -------------------------------------------------------------

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing or displaying notification: ${sbn.key}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService == this) {
            activeService = null
        }
        Log.d(TAG, "NotificationService destroyed.")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        Log.d(TAG, "🗑️ Notification removed by system: ${sbn?.key}")

        // --- FIX: Do NOT collapse island when system notification is removed ---
        // The island should only collapse based on user interaction (handled in DynamicIslandManager)
        // or when replaced by a new notification.
        /*
        if (sbn != null) {
            try {
                val dynamicIslandManager = DynamicIslandManager.getInstance(applicationContext)
                dynamicIslandManager.onSystemNotificationRemoved(sbn.key)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling notification removal in island", e)
            }
        }
        */
        // ---------------------------------------------------------------------
    }

    private fun ensureOverlayServiceRunning() {
        val overlayServiceIntent = Intent(applicationContext, OverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(overlayServiceIntent)
                // Log.d(TAG, "Requested OverlayService start via startForegroundService")
            } else {
                applicationContext.startService(overlayServiceIntent)
                // Log.d(TAG, "Requested OverlayService start via startService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService", e)
        }
    }
}
