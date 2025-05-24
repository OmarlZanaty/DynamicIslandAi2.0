package com.almobarmg.dynamicislandai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "ðŸš€ Boot completed. Service start responsibility moved to MainActivity.")
            // *** REMOVED: Do NOT start OverlayService from here due to background restrictions ***
            /*
            val serviceIntent = Intent(context, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                    Log.d(TAG, "Attempted to start OverlayService as foreground service.")
                } else {
                    context.startService(serviceIntent)
                    Log.d(TAG, "Attempted to start OverlayService as background service.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error attempting to start OverlayService from BootReceiver", e)
            }
            */
        }
    }

    companion object {
        private const val TAG = "DynamicIslandBootRcv"
    }
}

