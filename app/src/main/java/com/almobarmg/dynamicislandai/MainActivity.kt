package com.almobarmg.dynamicislandai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var dynamicIslandManager: DynamicIslandManager
    private var hasOverlayPermission = false
    private var hasNotificationListenerPermission = false
    private var hasPostNotificationPermission = false // For testing notifications

    // ActivityResultLauncher for Overlay Permission
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Check again after returning from settings
        hasOverlayPermission = Settings.canDrawOverlays(this)
        Log.d(TAG, "Overlay permission result: ${if (hasOverlayPermission) "Granted" else "Denied"}")
        if (!hasOverlayPermission) {
            showPermissionDeniedDialog("Display over other apps")
        } else {
            // If all permissions are now granted, proceed
            checkAndProceed()
        }
    }

    // ActivityResultLauncher for Notification Listener Permission
    // We don't get a direct result, so we re-check in onResume
    private val notificationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* No direct result, check in onResume */ }

    // ActivityResultLauncher for Post Notification Permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPostNotificationPermission = isGranted
        Log.d(TAG, "Post Notification permission result: ${if (isGranted) "Granted" else "Denied"}")
        if (!isGranted) {
            // Optional: Inform user why test notifications might not work
            Toast.makeText(this, "Test notifications require permission.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "🚀 onCreate: Starting MainActivity")

        requestAutoStartPermission()

        createNotificationChannel() // Create channel for test notifications

        if (!isNotificationServiceEnabled()) {
            Toast.makeText(this, "Please enable Notification Access for the app.", Toast.LENGTH_LONG).show()
            requestNotificationListenerPermission()
        }

        checkBatteryOptimization()

        val titleInput = findViewById<EditText>(R.id.notification_title_input)
        val textInput = findViewById<EditText>(R.id.notification_text_input)
        findViewById<Button>(R.id.send_notification_button).setOnClickListener {
            if (!::dynamicIslandManager.isInitialized) {
                Toast.makeText(this, "Please grant permissions first.", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            val title = titleInput.text.toString().ifEmpty { "Test Title" }
            val text = textInput.text.toString().ifEmpty { "This is test notification text."}
            Log.d(TAG, "🔔 Send notification button clicked: title=$title, text=$text")
            // *** MODIFIED: Pass null for the intent parameter ***
            dynamicIslandManager.showNotification(title, text, null)
        }

        findViewById<Button>(R.id.check_permissions_button).setOnClickListener {
            checkAndRequestPermissions()
        }

        // Initial permission check
        checkAndRequestPermissions()
    }

    private fun checkBatteryOptimization() {
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            val intent = Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
    // MainActivity.kt
    private fun requestAutoStartPermission() {
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Please enable Auto-Start for this app in MIUI Settings", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "🔄 onResume: Checking permissions again")
        // Re-check permissions when returning to the activity, especially Notification Listener
        checkPermissionsState()
        if (hasOverlayPermission && hasNotificationListenerPermission) {
            Log.d(TAG, "✅ Permissions confirmed in onResume, ensuring services are running.")
            initializeAndStartServices()
        } else {
            Log.w(TAG, "⚠️ Permissions still missing in onResume.")
        }
    }

    private fun checkPermissionsState() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        hasNotificationListenerPermission = isNotificationServiceEnabled()
        hasPostNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Granted by default on older versions
        }
        Log.d(TAG, "Current Permission State: Overlay=$hasOverlayPermission, Listener=$hasNotificationListenerPermission, Post=$hasPostNotificationPermission")
    }

    private fun checkAndRequestPermissions() {
        checkPermissionsState()

        val neededPermissions = mutableListOf<String>()
        if (!hasOverlayPermission) neededPermissions.add("Display over other apps")
        if (!hasNotificationListenerPermission) neededPermissions.add("Notification Access")

        if (neededPermissions.isEmpty()) {
            Log.d(TAG, "✅ All essential permissions already granted.")
            initializeAndStartServices()
        } else {
            Log.w(TAG, "🔒 Missing essential permissions: ${neededPermissions.joinToString()}")
            if (!hasOverlayPermission) {
                requestOverlayPermission()
            } else if (!hasNotificationListenerPermission) {
                requestNotificationListenerPermission()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
            Log.d(TAG, "🔔 Requesting Post Notification permission for testing.")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkAndProceed() {
        checkPermissionsState()
        if (hasOverlayPermission && hasNotificationListenerPermission) {
            Log.d(TAG, "✅ All essential permissions granted after check.")
            initializeAndStartServices()
        } else if (!hasNotificationListenerPermission) {
            requestNotificationListenerPermission()
        }
    }

    private fun initializeAndStartServices() {
        if (!::dynamicIslandManager.isInitialized) {
            Log.d(TAG, "🔧 Initializing DynamicIslandManager and starting services.")
            dynamicIslandManager = DynamicIslandManager.getInstance(applicationContext)
            // *** MODIFIED: Use createAndShowIsland instead of showIslandAlways ***
            dynamicIslandManager.createAndShowIsland() // Create and show the base island

            // Start Overlay Service (if you still have a separate OverlayService)
            // val overlayServiceIntent = Intent(this, OverlayService::class.java)
            // try {
            //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //         startForegroundService(overlayServiceIntent)
            //     } else {
            //         startService(overlayServiceIntent)
            //     }
            //     Log.d(TAG, "🟢 OverlayService started successfully.")
            // } catch (e: Exception) {
            //     Log.e(TAG, "❌ Failed to start OverlayService", e)
            //     Toast.makeText(this, "Error starting overlay service.", Toast.LENGTH_LONG).show()
            // }

            Log.d(TAG, "ℹ️ NotificationListenerService should be running if permission is granted.")

        } else {
            Log.d(TAG, "✅ Manager already initialized and services likely running.")
        }
    }

    private fun requestOverlayPermission() {
        Log.d(TAG, "❓ Requesting 'Display over other apps' permission.")
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("This app needs permission to display over other apps to show the Dynamic Island. Please grant this permission in the next screen.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showPermissionDeniedDialog("Display over other apps")
            }
            .setCancelable(false)
            .show()
    }

    private fun requestNotificationListenerPermission() {
        Log.d(TAG, "❓ Requesting 'Notification Access' permission.")
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("This app needs access to your notifications to display them in the Dynamic Island. Please enable access for '${getString(R.string.app_name)}' in the system settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                notificationSettingsLauncher.launch(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showPermissionDeniedDialog("Notification Access")
            }
            .setCancelable(false)
            .show()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val contentResolver = contentResolver
        val enabledNotificationListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val packageName = packageName
        return enabledNotificationListeners?.contains(packageName)
            ?: false
    }

    private fun showPermissionDeniedDialog(permissionName: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("The '$permissionName' permission is required for the Dynamic Island to function correctly. The app may not work as expected without it.")
            .setPositiveButton("Re-check Permissions") { _, _ -> checkAndRequestPermissions() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "dynamic_island_test_channel"
            val channelName = "Test Notifications"
            val channelDescription = "Channel for sending test notifications to the island."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel '$channelId' created")
        }
    }

    companion object {
        private const val TAG = "DynamicIslandMain"
    }
}

