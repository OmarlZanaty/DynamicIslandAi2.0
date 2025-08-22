package com.almobarmg.dynamicislandai

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Enhanced BootReceiver that uses multiple strategies to ensure service persistence:
 * 1. Schedules a delayed service start using AlarmManager (more reliable than direct start)
 * 2. Checks for SCHEDULE_EXACT_ALARM permission before using exact alarms on Android 12+
 * 3. Shows a notification to prompt user action if direct start fails
 * 4. Implements retry mechanism with exponential backoff
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DynamicIslandBootRcv"
        private const val BOOT_NOTIFICATION_CHANNEL_ID = "dynamic_island_boot_channel"
        private const val BOOT_NOTIFICATION_ID = 2001
        private const val SERVICE_START_REQUEST_CODE = 1001
        private const val SERVICE_RETRY_REQUEST_CODE = 1002
        private const val INITIAL_DELAY_MS = 10 * 1000L // 10 seconds after boot
        private const val MAX_RETRY_COUNT = 3

        // Action constants
        const val ACTION_START_SERVICES = "com.almobarmg.dynamicislandai.START_SERVICES"
        const val ACTION_RETRY_START = "com.almobarmg.dynamicislandai.RETRY_START"
        const val EXTRA_RETRY_COUNT = "retry_count"

        // Persistent flag to track if we've already tried to start services
        private const val PREF_NAME = "dynamic_island_boot_prefs"
        private const val PREF_SERVICE_START_ATTEMPTED = "service_start_attempted"
        private const val PREF_LAST_BOOT_TIME = "last_boot_time"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "🚀 Boot completed. Scheduling delayed service start.")
                saveServiceStartAttempted(context, false)
                saveLastBootTime(context, System.currentTimeMillis())
                createBootNotificationChannel(context)
                scheduleServiceStart(context, INITIAL_DELAY_MS)
            }

            ACTION_START_SERVICES -> {
                Log.d(TAG, "⏰ Delayed service start triggered.")
                attemptStartServices(context, 0)
            }

            ACTION_RETRY_START -> {
                val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)
                Log.d(TAG, "🔄 Retry service start attempt #$retryCount")
                attemptStartServices(context, retryCount)
            }
        }
    }

    private fun attemptStartServices(context: Context, retryCount: Int) {
        saveServiceStartAttempted(context, true)

        val hasOverlayPermission = Settings.canDrawOverlays(context)
        if (!hasOverlayPermission) {
            Log.w(TAG, "⚠️ Overlay permission not granted. Showing notification to user.")
            showPermissionRequiredNotification(context, "Overlay Permission Required", "Tap to open app and grant overlay permission")
            return
        }

        val overlayServiceIntent = Intent(context, OverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, overlayServiceIntent)
                Log.d(TAG, "✅ OverlayService start requested via startForegroundService.")
            } else {
                context.startService(overlayServiceIntent)
                Log.d(TAG, "✅ OverlayService start requested via startService.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start OverlayService", e)
            if (retryCount < MAX_RETRY_COUNT) {
                scheduleRetry(context, retryCount + 1)
            } else {
                showServiceStartFailedNotification(context)
            }
        }
    }

    private fun scheduleServiceStart(context: Context, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_START_SERVICES
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SERVICE_START_REQUEST_CODE,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMs

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                    Log.d(TAG, "⏰ Scheduled exact service start in ${delayMs/1000} seconds")
                } else {
                    Log.w(TAG, "⚠️ Cannot schedule exact alarm: SCHEDULE_EXACT_ALARM permission missing. Service might not start reliably after boot.")
                    // Optionally: Fallback to non-exact alarm or notify user
                    // For now, we just log and rely on MainActivity check
                    showPermissionRequiredNotification(context, "Permission Needed for Auto-Start", "Tap to grant 'Alarms & Reminders' permission for reliable boot start.")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use exact alarm for M-R where permission is not needed
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d(TAG, "⏰ Scheduled exact service start (API < S) in ${delayMs/1000} seconds")
            } else {
                // Use inexact alarm for older versions
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d(TAG, "⏰ Scheduled inexact service start (API < M) in ${delayMs/1000} seconds")
            }
        } catch (e: SecurityException) {
            // This should ideally not happen with the canScheduleExactAlarms check, but catch just in case
            Log.e(TAG, "❌ SecurityException scheduling alarm. Check permissions.", e)
            showPermissionRequiredNotification(context, "Permission Error", "Failed to schedule boot start. Please check app permissions.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception scheduling alarm.", e)
        }
    }

    private fun scheduleRetry(context: Context, retryCount: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RETRY_START
            putExtra(EXTRA_RETRY_COUNT, retryCount)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SERVICE_RETRY_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val delayMs = when (retryCount) {
            1 -> 30 * 1000L
            2 -> 2 * 60 * 1000L
            else -> 5 * 60 * 1000L
        }
        val triggerAtMillis = SystemClock.elapsedRealtime() + delayMs

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                    Log.d(TAG, "⏰ Scheduled exact retry #$retryCount in ${delayMs/1000} seconds")
                } else {
                    Log.w(TAG, "⚠️ Cannot schedule exact retry alarm: SCHEDULE_EXACT_ALARM permission missing.")
                    // Don't schedule retry if permission is missing
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d(TAG, "⏰ Scheduled exact retry #$retryCount (API < S) in ${delayMs/1000} seconds")
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d(TAG, "⏰ Scheduled inexact retry #$retryCount (API < M) in ${delayMs/1000} seconds")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException scheduling retry alarm.", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception scheduling retry alarm.", e)
        }
    }

    private fun showPermissionRequiredNotification(context: Context, title: String, text: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val settingsIntent = Intent(context, MainActivity::class.java).apply {
            // Add flags to bring MainActivity to front if already running
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BOOT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(BOOT_NOTIFICATION_ID, notification)
    }

    private fun showServiceStartFailedNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BOOT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Dynamic Island Needs Attention")
            .setContentText("Tap to restart the Dynamic Island service")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(BOOT_NOTIFICATION_ID, notification)
    }

    private fun createBootNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Dynamic Island Boot Notifications"
            val channelDescription = "Notifications related to starting Dynamic Island after device boot"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(BOOT_NOTIFICATION_CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "✅ Boot notification channel created")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to create boot notification channel", e)
            }
        }
    }

    // Preference helpers
    private fun saveServiceStartAttempted(context: Context, attempted: Boolean) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_SERVICE_START_ATTEMPTED, attempted).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preference", e)
        }
    }

    private fun saveLastBootTime(context: Context, time: Long) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(PREF_LAST_BOOT_TIME, time).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save preference", e)
        }
    }
}
