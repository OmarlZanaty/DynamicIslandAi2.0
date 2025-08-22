package com.almobarmg.dynamicislandai

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var dynamicIslandManager: DynamicIslandManager? = null
    private var hasOverlayPermission = false
    private var hasNotificationListenerPermission = false
    private var hasPostNotificationPermission = false // For testing notifications
    private var hasExactAlarmPermission = false // For BootReceiver
    private var isPermissionCheckRunning = false // Prevent concurrent checks
    private var servicesInitialized = false // Track if services are started
    private lateinit var prefs: SharedPreferences
    private lateinit var alarmManager: AlarmManager

    companion object {
        private const val TAG = "DynamicIslandMain"
        private const val PREF_NAME = "dynamic_island_prefs"
        private const val PREF_FIRST_RUN = "first_run"
        private const val PREF_LAST_START_TIME = "last_start_time"
    }

    // ActivityResultLauncher for Overlay Permission
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        hasOverlayPermission = Settings.canDrawOverlays(this)
        Log.d(TAG, "Overlay permission result: ${if (hasOverlayPermission) "Granted" else "Denied"}")
        checkAndRequestPermissions()
    }

    // ActivityResultLauncher for Notification Listener Permission
    private val notificationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Re-check in onResume */ }

    // ActivityResultLauncher for Post Notification Permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPostNotificationPermission = isGranted
        Log.d(TAG, "Post Notification permission result: ${if (isGranted) "Granted" else "Denied"}")
        if (!isGranted) {
            Toast.makeText(this, "Test notifications require permission.", Toast.LENGTH_SHORT).show()
        }
        checkAndRequestPermissions()
    }

    // ActivityResultLauncher for Exact Alarm Permission (Android 12+)
    private val exactAlarmSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Re-check in onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "🚀 onCreate: Starting MainActivity")

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        createNotificationChannels()
        setupUI()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "🔄 onResume: Checking permissions again")
        lifecycleScope.launch {
            delay(500) // Wait 500ms
            checkAndRequestPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: MainActivity is being destroyed")
        prefs.edit().putLong(PREF_LAST_START_TIME, System.currentTimeMillis()).apply()
    }

    private fun setupUI() {
        val titleInput = findViewById<EditText>(R.id.notification_title_input)
        val textInput = findViewById<EditText>(R.id.notification_text_input)

        findViewById<Button>(R.id.send_notification_button).setOnClickListener {
            if (dynamicIslandManager == null) {
                Toast.makeText(this, "Permissions might be missing or manager not ready.", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            val title = titleInput.text.toString().ifEmpty { "Test Title" }
            val text = textInput.text.toString().ifEmpty { "This is test notification text."}
            Log.d(TAG, "🔔 Send notification button clicked: title=$title, text=$text")
            // Pass null for both the PendingIntent and the key parameter
            dynamicIslandManager?.showNotification(title, text, null, null)
        }

        findViewById<Button>(R.id.check_permissions_button).setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    private fun checkPermissionsState(): Boolean {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        hasNotificationListenerPermission = isNotificationServiceEnabled()
        hasPostNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // Granted by default on older versions
        }
        Log.d(TAG, "Current Permission State: Overlay=$hasOverlayPermission, Listener=$hasNotificationListenerPermission, Post=$hasPostNotificationPermission, ExactAlarm=$hasExactAlarmPermission")
        // Exact Alarm is needed for boot persistence, but maybe not strictly essential for basic function
        return hasOverlayPermission && hasNotificationListenerPermission
    }

    private fun checkAndRequestPermissions() {
        if (isPermissionCheckRunning) {
            Log.d(TAG, "Permission check already running, skipping.")
            return
        }
        isPermissionCheckRunning = true
        Log.d(TAG, "Starting permission check...")

        val essentialGranted = checkPermissionsState() // Checks Overlay & Listener

        if (essentialGranted) {
            Log.d(TAG, "✅ Essential permissions (Overlay, Listener) granted.")
            initializeAndStartServices()

            // Now check optional/secondary permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
                Log.d(TAG, "🔔 Requesting Post Notification permission for testing.")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasExactAlarmPermission) {
                Log.d(TAG, "⏰ Requesting Schedule Exact Alarm permission for boot persistence.")
                requestExactAlarmPermission()
            } else {
                Log.d(TAG, "✅ All permissions granted.")
            }
        } else {
            Log.w(TAG, "🔒 Missing essential permissions.")
            servicesInitialized = false // Reset flag if permissions are lost
            // Request essential permissions sequentially
            if (!hasOverlayPermission) {
                requestOverlayPermission()
            } else if (!hasNotificationListenerPermission) {
                requestNotificationListenerPermission()
            }
        }
        // Reset the flag after a short delay to allow dialogs/launchers to process
        Handler(Looper.getMainLooper()).postDelayed({ isPermissionCheckRunning = false }, 1000)
    }

    private fun initializeAndStartServices() {
        if (servicesInitialized) {
            Log.d(TAG, "Services already initialized.")
            return
        }

        Log.d(TAG, "🔧 Initializing DynamicIslandManager and starting services.")

        dynamicIslandManager = DynamicIslandManager.getInstance(applicationContext)
        dynamicIslandManager?.createAndShowIsland()

        if (prefs.getBoolean(PREF_FIRST_RUN, true)) {
            prefs.edit().putBoolean(PREF_FIRST_RUN, false).apply()
        }
        prefs.edit().putLong(PREF_LAST_START_TIME, System.currentTimeMillis()).apply()

        startOverlayService()
        // Let the system handle binding naturally

        servicesInitialized = true
    }

    private fun startOverlayService() {
        val overlayServiceIntent = Intent(this, OverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayServiceIntent)
                Log.d(TAG, "🟢 OverlayService started via startForegroundService.")
            } else {
                startService(overlayServiceIntent)
                Log.d(TAG, "🟢 OverlayService started via startService (pre-Oreo).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start OverlayService", e)
            Toast.makeText(this, "Error starting overlay service: ${e.message}", Toast.LENGTH_LONG).show()
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
                isPermissionCheckRunning = false
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
                isPermissionCheckRunning = false
            }
            .setCancelable(false)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestExactAlarmPermission() {
        Log.d(TAG, "❓ Requesting 'Alarms & reminders' permission.")
        AlertDialog.Builder(this)
            .setTitle("Permission Required for Boot Start")
            .setMessage("To ensure the Dynamic Island starts automatically after your phone reboots, the app needs the 'Alarms & reminders' permission. This is used only to schedule the service start after boot.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    exactAlarmSettingsLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch exact alarm settings", e)
                    Toast.makeText(this, "Could not open Alarms & reminders settings. Please grant manually.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Island may not start automatically after reboot.", Toast.LENGTH_SHORT).show()
                isPermissionCheckRunning = false
            }
            .setCancelable(false)
            .show()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, NotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) ?: false
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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channels = listOf(
                NotificationChannel(
                    "dynamic_island_test_channel",
                    "Test Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Channel for sending test notifications to the island." },
                NotificationChannel(
                    "dynamic_island_overlay_channel",
                    "Dynamic Island Overlay Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent notification to keep the island overlay running"
                    setShowBadge(false)
                },
                NotificationChannel(
                    "dynamic_island_boot_channel",
                    "Dynamic Island Boot Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Notifications related to starting Dynamic Island after device boot" }
            )
            notificationManager.createNotificationChannels(channels)
            Log.d(TAG, "✅ All notification channels created")
        }
    }
}
