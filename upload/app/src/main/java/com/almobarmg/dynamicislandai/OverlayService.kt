package com.almobarmg.dynamicislandai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Enhanced OverlayService with improved persistence mechanisms:
 * 1. Proper foreground service implementation with wake lock
 * 2. Self-restart capability if killed
 * 3. Improved cleanup and error handling
 * 4. Fixed startForeground timing to prevent ForegroundServiceDidNotStartInTimeException
 * 5. Added foregroundServiceType for Android 12+ compatibility (using integer value)
 */
class OverlayService : Service() {

    private var islandManager: DynamicIslandManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false

    companion object {
        private const val TAG = "DynamicIslandOverlaySvc"
        private const val CHANNEL_ID = "dynamic_island_overlay_channel"
        private const val NOTIFICATION_ID = 1001 // Unique ID for the foreground notification
        private const val WAKELOCK_TAG = "DynamicIsland:OverlayServiceWakeLock"

        // Action for self-restart
        const val ACTION_RESTART = "com.almobarmg.dynamicislandai.RESTART_OVERLAY_SERVICE"

        // Integer value for FOREGROUND_SERVICE_TYPE_SYSTEM_ALERT_WINDOW (for compatibility)
        private const val FGS_TYPE_SYSTEM_ALERT_WINDOW = 2048
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService created.")
        createNotificationChannel()

        // CRITICAL FIX: Start foreground immediately in onCreate with the correct type
        try {
            val notification = createForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+ (required type for Android 12+ targeting SDK 31+)
                // For Android 14+ (targeting SDK 34+), type MUST be specified here.
                // Using integer value 2048 for FOREGROUND_SERVICE_TYPE_SYSTEM_ALERT_WINDOW
                startForeground(NOTIFICATION_ID, notification, FGS_TYPE_SYSTEM_ALERT_WINDOW)
                Log.d(TAG, "OverlayService started in foreground during onCreate with type SYSTEM_ALERT_WINDOW (int value).")
            } else {
                // For older versions (Android 9 and below)
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "OverlayService started in foreground during onCreate (legacy).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service in onCreate", e)
            // Fallback attempt without type (might still fail on newer Android)
            try {
                val simpleNotification = createSimpleForegroundNotification()
                startForeground(NOTIFICATION_ID, simpleNotification)
                Log.w(TAG, "OverlayService started in foreground with simple notification during onCreate (fallback).")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start as foreground even with simple notification in onCreate", e2)
            }
        }

        islandManager = DynamicIslandManager.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand. Action: ${intent?.action}")

        // Handle restart action if present
        if (intent?.action == ACTION_RESTART) {
            Log.d(TAG, "Received restart action")
        }

        // Acquire wake lock if service is starting
        if (!isServiceStarted) {
            isServiceStarted = true
            acquireWakeLock()
        }

        // Ensure the manager is initialized
        if (islandManager == null) {
            islandManager = DynamicIslandManager.getInstance(applicationContext)
        }

        // Create and show the island if not already visible
        try {
            islandManager?.createAndShowIsland()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing island", e)
        }

        // If the service is killed, the system should try to restart it
        return START_STICKY
    }

    private fun acquireWakeLock() {
        // Create wake lock to improve service persistence
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            setReferenceCounted(false)
            if (!isHeld) {
                acquire(10*60*1000L) // 10 minutes max
                Log.d(TAG, "Wake lock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService destroyed.")

        // Clean up the island view if the service is destroyed
        islandManager?.cleanup()
        islandManager = null

        // Release wake lock
        releaseWakeLock()

        // Stop being a foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }

        isServiceStarted = false

        // Try to restart ourselves if we're being killed unexpectedly
        val restartIntent = Intent(applicationContext, OverlayService::class.java).apply {
            action = ACTION_RESTART
        }
        try {
            applicationContext.startService(restartIntent)
            Log.d(TAG, "Requested service restart after destruction")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request service restart", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Dynamic Island Overlay Service"
            val channelDescription = "Persistent notification to keep the island overlay running"
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance to be less intrusive
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Foreground service notification channel created.")
        }
    }

    private fun createForegroundNotification(): Notification {
        // Intent to open MainActivity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dynamic Island Active")
            .setContentText("Overlay service is running.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Make it persistent
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createSimpleForegroundNotification(): Notification {
        // Simpler notification with minimal features for compatibility
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dynamic Island Active")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
