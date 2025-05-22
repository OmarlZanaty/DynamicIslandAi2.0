package com.almobarmg.dynamicislandai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi

class OverlayService : Service() {

    private lateinit var islandManager: DynamicIslandManager

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        islandManager = DynamicIslandManager.getInstance(applicationContext)
        islandManager.createAndShowIsland()
        startForegroundServiceWithNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Optional: keep island unless explicitly removed
        // islandManager.cleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundServiceWithNotification() {
        val channelId = "island_service"
        val channelName = "Dynamic Island Service"
        val notificationId = 1001

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Dynamic Island Running")
            .setContentText("Tap to manage your dynamic overlay.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(notificationId, notification)
    }
}
