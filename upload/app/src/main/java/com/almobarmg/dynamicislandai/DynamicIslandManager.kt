package com.almobarmg.dynamicislandai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.content.res.Resources
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// Data class for configuration
data class DynamicIslandConfig(
    val collapsedWidthDp: Int = 150, // Width when collapsed (dp)
    val collapsedHeightDp: Int = 35, // Height when collapsed (dp)
    // Expanded width will be calculated based on screen width
    val expandedHeightDp: Int = 100, // Increased height when expanded (dp)
    val horizontalMarginDp: Int = 25, // Margin from screen edges when expanded (dp)
    val positionYDp: Int = 25, // Pixels from top (dp)
    val animationDuration: Long = 250, // ms
    val swipeThresholdVelocity: Int = 100, // Velocity threshold for swipe detection
    val swipeThresholdDistance: Int = 50 // Distance threshold for swipe detection
)

class DynamicIslandManager private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutInflater = LayoutInflater.from(context)
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val config = DynamicIslandConfig()

    // --- Calculate pixel dimensions based on DP and screen size ---
    private val resources: Resources = context.resources
    private val displayMetrics: DisplayMetrics = resources.displayMetrics
    private val screenWidthPx: Int = displayMetrics.widthPixels

    private val collapsedWidthPx: Int = dpToPx(config.collapsedWidthDp)
    private val collapsedHeightPx: Int = dpToPx(config.collapsedHeightDp)
    private val expandedWidthPx: Int = screenWidthPx - (dpToPx(config.horizontalMarginDp) * 2)
    private val expandedHeightPx: Int = dpToPx(config.expandedHeightDp)
    private val positionYPx: Int = dpToPx(config.positionYDp)
    private val swipeThresholdVelocityPx: Int = dpToPx(config.swipeThresholdVelocity)
    private val swipeThresholdDistancePx: Int = dpToPx(config.swipeThresholdDistance)
    // -------------------------------------------------------------

    @SuppressLint("InflateParams")
    private var islandView: View? = null
    private var islandLayoutParams: WindowManager.LayoutParams? = null
    private var isViewAdded = false
    private var isExpanded = false
    private var currentAnimator: ValueAnimator? = null
    private var isAnimating = false // Flag to track animation state

    // Views cache
    private var notificationTitleView: TextView? = null
    private var notificationTextView: TextView? = null
    private var timeTextView: TextView? = null
    private var batteryTextView: TextView? = null
    private var batteryIconView: ImageView? = null
    private var networkIconView: ImageView? = null
    private var historyContainerView: LinearLayout? = null
    private var islandContentLayout: View? = null
    private var musicPlaybackLayout: LinearLayout? = null

    // State tracking
    private val notificationHistory = mutableListOf<Pair<String, String>>()
    private var timeUpdateJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNotificationIntent: PendingIntent? = null
    private var currentNotificationKey: String? = null // Store key to potentially dismiss system notification
    private var backgroundUpdatesPaused = false // Flag to pause background updates during animation

    // Gesture Detector
    private lateinit var gestureDetector: GestureDetectorCompat

    // Helper to convert DP to Pixels
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), displayMetrics).toInt()
    }

    fun createAndShowIsland() {
        mainScope.launch {
            if (islandView != null && isViewAdded) {
                Log.d(TAG, "Island view already created and added.")
                islandView?.visibility = View.VISIBLE
                return@launch
            }

            if (!Settings.canDrawOverlays(context)) {
                Log.e(TAG, "❌ Cannot create island view: SYSTEM_ALERT_WINDOW permission missing.")
                return@launch
            }

            try {
                if (islandView == null) {
                    Log.d(TAG, "Inflating island view layout.")
                    islandView = layoutInflater.inflate(R.layout.island_view, null)
                    cacheViewReferences()
                    setupGestureDetector()
                }

                if (islandLayoutParams == null) {
                    islandLayoutParams = WindowManager.LayoutParams(
                        collapsedWidthPx,
                        collapsedHeightPx,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = positionYPx
                        // Attempt hardware acceleration (may or may not work on overlays)
                        // flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    }
                }

                if (!isViewAdded && islandView != null) {
                    windowManager.addView(islandView, islandLayoutParams)
                    isViewAdded = true
                    Log.i(TAG, "✅ Island view added to WindowManager.")
                    islandView?.visibility = View.VISIBLE
                    updateIslandState(expand = false, animate = false) // Start collapsed
                    setupInteractions()
                    startBackgroundUpdates()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error creating or adding island view.", e)
                cleanupView()
            }
        }
    }

    private fun cacheViewReferences() {
        islandView?.let {
            notificationTitleView = it.findViewById(R.id.notification_title)
            notificationTextView = it.findViewById(R.id.notification_text)
            timeTextView = it.findViewById(R.id.time_text)
            batteryTextView = it.findViewById(R.id.battery_text)
            batteryIconView = it.findViewById(R.id.battery_icon)
            networkIconView = it.findViewById(R.id.network_icon)
            historyContainerView = it.findViewById(R.id.history_container)
            islandContentLayout = it.findViewById(R.id.island_content_layout) // Ensure this ID exists
            albumArtView = it.findViewById(R.id.album_art)
            musicTitleView = it.findViewById(R.id.music_title)
            musicArtistView = it.findViewById(R.id.music_artist)
            timerTextView = it.findViewById(R.id.timer_text_view)
            faceIdIconView = it.findViewById(R.id.face_id_icon)
            chargingIconView = it.findViewById(R.id.charging_icon)
            networkTypeTextView = it.findViewById(R.id.network_type_text)
            Log.d(TAG, "Cached view references.")
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Must return true to receive further events
                return true
            }

            // Use onSingleTapConfirmed to distinguish from double-tap
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d(TAG, "GestureDetector: onSingleTapConfirmed")
                handleSingleClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "GestureDetector: onDoubleTap")
                handleDoubleClick()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Check for horizontal swipe - now works for both test notifications and system notifications
                if (isExpanded &&
                    abs(diffX) > abs(diffY) &&
                    abs(diffX) > swipeThresholdDistancePx &&
                    abs(velocityX) > swipeThresholdVelocityPx) {

                    if (diffX > 0) {
                        Log.d(TAG, "GestureDetector: Swipe Right detected")
                    } else {
                        Log.d(TAG, "GestureDetector: Swipe Left detected")
                    }
                    handleSwipeDismiss()
                    return true // Consumed the event
                }
                return false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractions() {
        // Use the GestureDetector for touch events
        islandView?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            // Return true to indicate the event was handled (by the detector)
            true
        }
    }

    private fun handleSingleClick() {
        // Single click confirmed (not part of a double-click)
        // If expanded (showing status/history OR notification), collapse.
        // If collapsed, expand to show status/history.
        if (isExpanded) {
            Log.d(TAG, "🏝️ Single Click Confirmed: Collapsing island.")
            updateIslandState(expand = false, animate = true)
        } else {
            Log.d(TAG, "🏝️ Single Click Confirmed: Expanding status/history view.")
            // Clear any lingering notification details if expanding manually
            currentNotificationIntent = null
            currentNotificationKey = null
            notificationTitleView?.text = ""
            notificationTextView?.text = ""
            updateIslandState(expand = true, animate = true)
        }
    }

    private fun handleDoubleClick() {
        // Double tap confirmed
        if (isExpanded && currentNotificationIntent != null) {
            try {
                Log.i(TAG, "🏝️ Double Click: Launching PendingIntent: $currentNotificationIntent")
                currentNotificationIntent?.send()
                // Collapse after launching
                updateIslandState(expand = false, animate = true)
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "❌ PendingIntent Canceled on double-click.", e)
                Toast.makeText(context, "Notification action canceled.", Toast.LENGTH_SHORT).show()
                // Clear the intent and collapse
                currentNotificationIntent = null
                currentNotificationKey = null
                updateIslandState(expand = false, animate = true)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error launching PendingIntent on double-click.", e)
                Toast.makeText(context, "Error opening notification.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d(TAG, "🏝️ Double Click: Ignored (not expanded or no notification active).")
            // Optional: Could toggle expand/collapse here if desired as a fallback
        }
    }

    private fun handleSwipeDismiss() {
        // Already handled in onFling, this function just performs the action
        if (isExpanded) {
            Log.i(TAG, "🏝️ Swipe Dismiss Action: Dismissing current notification and collapsing.")
            // Clear the current notification details
            currentNotificationIntent = null
            val keyToDismiss = currentNotificationKey // Store key before clearing
            currentNotificationKey = null
            notificationTitleView?.text = ""
            notificationTextView?.text = ""

            // Collapse the island
            updateIslandState(expand = false, animate = true)

            // Attempt to dismiss the actual system notification via the service
            if (keyToDismiss != null) {
                NotificationService.requestCancelNotification(context, keyToDismiss)
            }
        } else {
            Log.d(TAG, "🏝️ Swipe Dismiss Action: Ignored (not expanded).")
        }
    }

    // Handle system notification removal (Now only used if NotificationService fix is reverted)
    fun onSystemNotificationRemoved(key: String?) {
        // This logic is currently disabled in NotificationService v4
        // If re-enabled, it would collapse the island when the system notification is removed.
        if (key != null && key == currentNotificationKey) {
            mainScope.launch {
                Log.d(TAG, "System notification removed, collapsing island: $key")
                currentNotificationIntent = null
                currentNotificationKey = null
                notificationTitleView?.text = ""
                notificationTextView?.text = ""
                updateIslandState(expand = false, animate = true)
            }
        }
    }

    // Pass the notification key from NotificationService
    fun showNotification(title: String, text: String, intent: PendingIntent?, key: String?) {
        mainScope.launch {
            if (islandView == null || !isViewAdded) {
                Log.w(TAG, "⚠️ Attempted to show notification but island view is not ready. Creating...")
                createAndShowIsland()
                delay(100) // Give view time to be added
                if (islandView == null || !isViewAdded) {
                    Log.e(TAG, "❌ Failed to create island view for notification.")
                    return@launch
                }
            }

            Log.d(TAG, "Updating island with notification: Title=\"$title\", Text=\"$text\", Intent=${intent}, Key=$key")
            notificationHistory.add(0, title to text) // Add to history
            currentNotificationIntent = intent
            currentNotificationKey = key

            notificationTitleView?.text = title
            notificationTextView?.text = text

            islandView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
        }
    }

    private fun updateIslandState(expand: Boolean, animate: Boolean) {
        val currentView = islandView
        val currentParams = islandLayoutParams
        if (currentView == null || currentParams == null || !isViewAdded) {
            Log.w(TAG, "⚠️ Cannot update island state: View not ready.")
            return
        }
        // If already in the target state and not animating, do nothing
        if (isExpanded == expand && !isAnimating) {
            Log.d(TAG, "Already in target state (${if (expand) "Expanded" else "Collapsed"}). No update needed.")
            return
        }
        // If an animation is running towards the *same* target state, let it finish
        if (isAnimating && isExpanded == expand) {
            Log.d(TAG, "Animation already running towards target state. Ignoring request.")
            return
        }
        // If an animation is running towards the *opposite* state, cancel it
        if (isAnimating && isExpanded != expand) {
            Log.d(TAG, "Cancelling running animation to opposite state.")
            currentAnimator?.cancel() // isAnimating will be set to false in onAnimationCancel/End
        }

        Log.i(TAG, "Updating island state: ${if (expand) "Expanding" else "Collapsing"} (Animate: $animate)")
        isExpanded = expand

        val startWidth = currentParams.width
        val startHeight = currentParams.height
        val targetWidth = if (expand) expandedWidthPx else collapsedWidthPx
        val targetHeight = if (expand) expandedHeightPx else collapsedHeightPx

        val showNotificationContent = expand && currentNotificationIntent != null
        val showMusicContent = expand && mediaController != null && mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        val showTimerContent = expand && isTimerRunning
        val showFaceIdContent = expand && faceIdIconView?.visibility == View.VISIBLE
        val showChargingContent = expand && chargingIconView?.visibility == View.VISIBLE

        // Determine which content to show
        // Priority: Call > Notification > Music > Timer > Face ID > Charging > Status/History
        val showCall = inCall
        val showNotification = !showCall && currentNotificationIntent != null
        val showMusic = !showCall && !showNotification && mediaController != null && mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        val showTimer = !showCall && !showNotification && !showMusic && isTimerRunning
        val showFaceId = !showCall && !showNotification && !showMusic && !showTimer && faceIdIconView?.visibility == View.VISIBLE
        val showCharging = !showCall && !showNotification && !showMusic && !showTimer && !showFaceId && chargingIconView?.visibility == View.VISIBLE
        val showStatusAndHistory = !showCall && !showNotification && !showMusic && !showTimer && !showFaceId && !showCharging && expand

        // Update visibility of main content layout
        islandContentLayout?.visibility = if (showNotification || showMusic || showTimer || showFaceId || showCharging || showStatusAndHistory) View.VISIBLE else View.GONE

        // Update visibility of individual content sections
        notificationTitleView?.visibility = if (showNotification) View.VISIBLE else View.GONE
        notificationTextView?.visibility = if (showNotification) View.VISIBLE else View.GONE

        musicPlaybackLayout?.visibility = if (showMusic) View.VISIBLE else View.GONE

        timerTextView?.visibility = if (showTimer) View.VISIBLE else View.GONE

        faceIdIconView?.visibility = if (showFaceId) View.VISIBLE else View.GONE

        chargingIconView?.visibility = if (showCharging) View.VISIBLE else View.GONE

        historyContainerView?.visibility = if (showStatusAndHistory) View.VISIBLE else View.GONE

        // Update status row visibility based on overall island state
        statusRow?.visibility = if (expand) View.GONE else View.VISIBLE

        if (animate) {
            currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = config.animationDuration
                interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()

                addUpdateListener { animation ->
                    val fraction = animation.animatedValue as Float
                    currentParams.width = (startWidth + (targetWidth - startWidth) * fraction).toInt()
                    currentParams.height = (startHeight + (targetHeight - startHeight) * fraction).toInt()
                    try {
                        if (isViewAdded) { // Check if view is still added
                            windowManager.updateViewLayout(currentView, currentParams)
                        }
                    } catch (e: IllegalArgumentException) {
                        // View not attached? Log and potentially cancel/cleanup
                        Log.e(TAG, "Error updating view layout during animation (View likely removed)", e)
                        cancel()
                        cleanupView()
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error updating view layout during animation", e)
                        // Consider canceling animation or other recovery
                    }
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        Log.d(TAG, "Animation started: ${if (expand) "Expand" else "Collapse"}")
                        isAnimating = true
                        backgroundUpdatesPaused = true // Pause background updates
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        Log.d(TAG, "Animation ended. Final state: ${if (isExpanded) "Expanded" else "Collapsed"}")
                        isAnimating = false
                        backgroundUpdatesPaused = false // Resume background updates
                        currentAnimator = null
                        // Ensure final state is set precisely
                        currentParams.width = targetWidth
                        currentParams.height = targetHeight
                        try {
                            if (isViewAdded) windowManager.updateViewLayout(currentView, currentParams)
                        } catch (e: Exception) { Log.e(TAG, "Error setting final layout post-animation", e) }

                        // If collapsed, hide the main content layout completely
                        if (!isExpanded) {
                            islandContentLayout?.visibility = View.GONE
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        Log.d(TAG, "Animation cancelled.")
                        isAnimating = false
                        backgroundUpdatesPaused = false // Resume background updates
                        currentAnimator = null
                        // Don't force final state on cancel, might be starting new animation
                    }
                })
                start()
            }
        } else {
            // Apply changes immediately without animation
            currentParams.width = targetWidth
            currentParams.height = targetHeight
            try {
                if (isViewAdded) windowManager.updateViewLayout(currentView, currentParams)
            } catch (e: Exception) { Log.e(TAG, "Error setting layout without animation", e) }

            // If collapsed, hide the main content layout completely
            if (!isExpanded) {
                islandContentLayout?.visibility = View.GONE
            }
        }
    }

    private fun startBackgroundUpdates() {
        Log.d(TAG, "Starting background updates (Time, Battery, Network).")
        startTimeUpdates()
        startBatteryUpdates()
        startNetworkUpdates()
    }

    private fun stopBackgroundUpdates() {
        Log.d(TAG, "Stopping background updates.")
        timeUpdateJob?.cancel()
        timeUpdateJob = null
        stopBatteryUpdates()
        stopNetworkUpdates()
    }

    private fun startTimeUpdates() {
        timeUpdateJob?.cancel() // Cancel previous job if any
        timeUpdateJob = mainScope.launch {
            while (isActive) {
                if (!backgroundUpdatesPaused) { // Check flag before updating
                    val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    timeTextView?.text = currentTime
                }
                delay(TimeUnit.MINUTES.toMillis(1)) // Update every minute
            }
        }
    }

    private fun startBatteryUpdates() {
        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_BATTERY_CHANGED && !backgroundUpdatesPaused) {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                        batteryTextView?.text = if (batteryPct != -1) "$batteryPct%" else "N/A"
                        batteryIconView?.setImageResource(
                            when {
                                isCharging -> R.drawable.ic_battery_charging
                                batteryPct > 80 -> R.drawable.ic_battery_charging
                                batteryPct > 20 -> R.drawable.ic_battery_charging
                                batteryPct >= 0 -> R.drawable.ic_battery_charging
                                else -> R.drawable.ic_battery_charging
                            }
                        )
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
            Log.d(TAG, "Battery receiver registered.")
        }
    }

    private fun stopBatteryUpdates() {
        if (batteryReceiver != null) {
            try {
                context.unregisterReceiver(batteryReceiver)
                Log.d(TAG, "Battery receiver unregistered.")
            } catch (e: IllegalArgumentException) {
    private fun startNetworkUpdates() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (networkCallback == null) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, networkCapabilities)
                    Log.d(TAG, "Network capabilities changed: $networkCapabilities")
                    updateNetworkIcon(networkCapabilities)
                    updateNetworkType(networkCapabilities)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network lost: $network")
                    mainScope.launch {
                        networkIconView?.setImageResource(R.drawable.ic_no_wifi)
                        networkTypeTextView?.text = "No Network"
                    }
                }
            }
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network callback registered.")
        }
    }

    private fun stopNetworkUpdates() {
        if (networkCallback != null) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback!!)
                Log.d(TAG, "Network callback unregistered.")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    private fun updateNetworkIcon(caps: NetworkCapabilities) {
        mainScope.launch {
            val iconRes = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.drawable.ic_wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.drawable.ic_wifi
                else -> R.drawable.ic_wifi
            }
            networkIconView?.setImageResource(iconRes)
        }
    }

    fun cleanup() {
        mainScope.launch {
            Log.i(TAG, "🧹 Cleaning up Dynamic Island Manager.")
            stopBackgroundUpdates()
            cleanupView()
            // Cancel any ongoing coroutines within this scope
            mainScope.cancel()
            instance = null // Allow garbage collection
        }
    }

    private fun cleanupView() {
        currentAnimator?.cancel() // Cancel any running animation
        if (islandView != null && isViewAdded) {
            try {
                windowManager.removeView(islandView)
                Log.d(TAG, "Island view removed from WindowManager.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing island view", e)
            }
        }
        islandView = null
        islandLayoutParams = null
        isViewAdded = false
        isExpanded = false
        isAnimating = false
        // Clear cached views
        notificationTitleView = null
        notificationTextView = null
        timeTextView = null
        batteryTextView = null
        batteryIconView = null
        networkIconView = null
        historyContainerView = null
        islandContentLayout = null
    }

    companion object {
        private const val TAG = "DynamicIslandManager"

        @Volatile
        private var instance: DynamicIslandManager? = null

        fun getInstance(context: Context): DynamicIslandManager {
            return instance ?: synchronized(this) {
                instance ?: DynamicIslandManager(context.applicationContext).also { instance = it }
            }
        }
    }
}



    // Call state handling
    private var callStateListener: PhoneStateListener? = null
    private var telecomManager: TelecomManager? = null
    private var inCall = false
    private var callStartTime: Long = 0
    private var callDurationJob: Job? = null

    private fun startCallUpdates() {
        telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        callStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in API 31")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                if (backgroundUpdatesPaused) return

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d(TAG, "Call State: RINGING")
                        mainScope.launch {
                            showCallNotification("Incoming Call", phoneNumber ?: "Unknown")
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(TAG, "Call State: OFFHOOK")
                        inCall = true
                        callStartTime = System.currentTimeMillis()
                        startCallDurationUpdates()
                        mainScope.launch {
                            showCallNotification("On Call", phoneNumber ?: "Unknown")
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "Call State: IDLE")
                        inCall = false
                        callDurationJob?.cancel()
                        mainScope.launch {
                            updateIslandState(expand = false, animate = true)
                        }
                    }
                }
            }

            override fun onCallStateChanged(state: Int) {
                super.onCallStateChanged(state)
                if (backgroundUpdatesPaused) return

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d(TAG, "Call State: RINGING")
                        mainScope.launch {
                            showCallNotification("Incoming Call", "") // Phone number not available in new API
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(TAG, "Call State: OFFHOOK")
                        inCall = true
                        callStartTime = System.currentTimeMillis()
                        startCallDurationUpdates()
                        mainScope.launch {
                            showCallNotification("On Call", "")
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "Call State: IDLE")
                        inCall = false
                        callDurationJob?.cancel()
                        mainScope.launch {
                            updateIslandState(expand = false, animate = true)
                        }
                    }
                }
            }
        }

        try {
            telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d(TAG, "Call state listener registered.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for PhoneStateListener.LISTEN_CALL_STATE", e)
        }
    }

    private fun stopCallUpdates() {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (callStateListener != null) {
            telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_NONE)
            callStateListener = null
            Log.d(TAG, "Call state listener unregistered.")
        }
        callDurationJob?.cancel()
    }

    private fun showCallNotification(title: String, subtitle: String) {
        mainScope.launch {
            if (islandView == null || !isViewAdded) {
                Log.w(TAG, "⚠️ Attempted to show call notification but island view is not ready. Creating...")
                createAndShowIsland()
                delay(100) // Give view time to be added
                if (islandView == null || !isViewAdded) {
                    Log.e(TAG, "❌ Failed to create island view for call notification.")
                    return@launch
                }
            }

            notificationTitleView?.text = title
            notificationTextView?.text = subtitle
            islandView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
        }
    }

    private fun startCallDurationUpdates() {
        callDurationJob?.cancel() // Cancel any existing job
        callDurationJob = mainScope.launch {
            while (inCall) {
                val duration = System.currentTimeMillis() - callStartTime
                val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60
                val timeString = String.format("%02d:%02d", minutes, seconds)
                notificationTextView?.text = timeString
                delay(1000) // Update every second
            }
        }
    }

    // Modify startBackgroundUpdates and stopBackgroundUpdates to include call updates
    private fun startBackgroundUpdates() {
        startTimeUpdates()
        startBatteryUpdates()
        startNetworkUpdates()
        startCallUpdates()
        startMusicUpdates()
        startTimerUpdates()
        startFaceIdUpdates()
        startChargingUpdates()
    }

    private fun stopBackgroundUpdates() {
        stopTimeUpdates()
        stopBatteryUpdates()
        stopNetworkUpdates()
        stopCallUpdates() // Stop call updates
    }




import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager





    // Media playback handling
    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaSessionCallback: MediaSession.Callback? = null
    private var mediaController: MediaController? = null
    private var albumArtView: ImageView? = null
    private var musicTitleView: TextView? = null
    private var musicArtistView: TextView? = null

    private fun setupMusicPlaybackControls() {
        islandView?.let {
            albumArtView = it.findViewById(R.id.album_art)
            musicTitleView = it.findViewById(R.id.music_title)
            musicArtistView = it.findViewById(R.id.music_artist)
        }
    }

    private fun startMusicUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(context, NotificationService::class.java)

            mediaSessionCallback = object : MediaSession.Callback() {
                override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
                    super.onActiveSessionsChanged(controllers)
                    Log.d(TAG, "Active media sessions changed.")
                    updateMediaController(controllers)
                }
            }

            try {
                mediaSessionManager?.addOnActiveSessionsChangedListener(mediaSessionCallback, componentName)
                Log.d(TAG, "Media session listener registered.")
                // Initial check for active sessions
                updateMediaController(mediaSessionManager?.getActiveSessions(componentName))
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for MediaSessionManager.addOnActiveSessionsChangedListener", e)
            }
        }
    }

    private fun stopMusicUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionCallback?.let {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(it)
                Log.d(TAG, "Media session listener unregistered.")
            }
            mediaController = null
        }
    }

    private fun updateMediaController(controllers: List<MediaController>?) {
        val activeController = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (activeController != null) {
            mediaController = activeController
            updateMediaMetadata(activeController.metadata)
            updatePlaybackState(activeController.playbackState)
            Log.d(TAG, "Active media controller found: ${activeController.packageName}")
        } else {
            mediaController = null
            mainScope.launch {
                albumArtView?.setImageDrawable(null)
                musicTitleView?.text = ""
                musicArtistView?.text = ""
                // Collapse if no other notifications/calls are active
                if (!inCall && currentNotificationIntent == null) {
                    updateIslandState(expand = false, animate = true)
                }
            }
            Log.d(TAG, "No active media controller.")
        }
    }

    private fun updateMediaMetadata(metadata: MediaMetadata?) {
        mainScope.launch {
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

            musicTitleView?.text = title
            musicArtistView?.text = artist
            albumArtView?.setImageBitmap(albumArt)

            if (title.isNotBlank() || artist.isNotBlank()) {
                showMusicNotification(title, artist)
            }
        }
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        mainScope.launch {
            when (state?.state) {
                PlaybackState.STATE_PLAYING -> {
                    Log.d(TAG, "Media State: PLAYING")
                    // Update UI for playing state
                }
                PlaybackState.STATE_PAUSED -> {
                    Log.d(TAG, "Media State: PAUSED")
                    // Update UI for paused state
                }
                else -> {
                    Log.d(TAG, "Media State: ${state?.state}")
                    // Handle other states or hide music controls
                }
            }
        }
    }

    private fun showMusicNotification(title: String, artist: String) {
        mainScope.launch {
            if (islandView == null || !isViewAdded) {
                Log.w(TAG, "⚠️ Attempted to show music notification but island view is not ready. Creating...")
                createAndShowIsland()
                delay(100) // Give view time to be added
                if (islandView == null || !isViewAdded) {
                    Log.e(TAG, "❌ Failed to create island view for music notification.")
                    return@launch
                }
            }

            notificationTitleView?.text = title
            notificationTextView?.text = artist
            islandView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
        }
    }

    // Modify startBackgroundUpdates and stopBackgroundUpdates to include music updates
    private fun startBackgroundUpdates() {
        startTimeUpdates()
        startBatteryUpdates()
        startNetworkUpdates()
        startCallUpdates()
        startMusicUpdates() // Add music updates
    }

    private fun stopBackgroundUpdates() {
        stopTimeUpdates()
        stopBatteryUpdates()
        stopNetworkUpdates()
        stopCallUpdates()
        stopMusicUpdates() // Stop music updates
    }




import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.ComponentName





    // Timer/Stopwatch handling
    private var timerJob: Job? = null
    private var timerStartTime: Long = 0
    private var isTimerRunning = false
    private var timerTextView: TextView? = null

    private fun setupTimerViews() {
        islandView?.let {
            timerTextView = it.findViewById(R.id.timer_text_view)
        }
    }

    fun startTimer() {
        if (isTimerRunning) return
        isTimerRunning = true
        timerStartTime = System.currentTimeMillis()
        timerJob = mainScope.launch {
            while (isTimerRunning) {
                val elapsed = System.currentTimeMillis() - timerStartTime
                val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
                val timeString = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }
                timerTextView?.text = timeString
                showTimerNotification(timeString)
                delay(1000)
            }
        }
    }

    fun stopTimer() {
        isTimerRunning = false
        timerJob?.cancel()
        mainScope.launch {
            updateIslandState(expand = false, animate = true)
        }
    }

    private fun showTimerNotification(time: String) {
        mainScope.launch {
            if (islandView == null || !isViewAdded) {
                Log.w(TAG, "⚠️ Attempted to show timer notification but island view is not ready. Creating...")
                createAndShowIsland()
                delay(100) // Give view time to be added
                if (islandView == null || !isViewAdded) {
                    Log.e(TAG, "❌ Failed to create island view for timer notification.")
                    return@launch
                }
            }

            notificationTitleView?.text = "Timer"
            notificationTextView?.text = time
            islandView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
        }
    }

    // Modify startBackgroundUpdates and stopBackgroundUpdates to include timer updates
    private fun startBackgroundUpdates() {
        startTimeUpdates()
        startBatteryUpdates()
        startNetworkUpdates()
        startCallUpdates()
        startMusicUpdates()
        // No direct start for timer here, it's started manually
    }

    private fun stopBackgroundUpdates() {
        stopTimeUpdates()
        stopBatteryUpdates()
        stopNetworkUpdates()
        stopCallUpdates()
        stopMusicUpdates()
        stopTimer()
    }






    // Face ID / Unlock Animation
    private var faceIdIconView: ImageView? = null

    fun showFaceIdAnimation() {
        mainScope.launch {
            if (islandView == null || !isViewAdded) {
                Log.w(TAG, "⚠️ Attempted to show Face ID animation but island view is not ready. Creating...")
                createAndShowIsland()
                delay(100) // Give view time to be added
                if (islandView == null || !isViewAdded) {
                    Log.e(TAG, "❌ Failed to create island view for Face ID animation.")
                    return@launch
                }
            }
            notificationTitleView?.text = "Face ID"
            notificationTextView?.text = "Unlocking..."
            faceIdIconView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
            delay(2000) // Show for 2 seconds
            faceIdIconView?.visibility = View.GONE
            updateIslandState(expand = false, animate = true)
        }
    }

    // Charging Animation
    private var chargingIconView: ImageView? = null

    fun showChargingAnimation(batteryPct: Int) {
        mainScope.launch {
            if (islandView == null || !isViewAdded) {
                Log.w(TAG, "⚠️ Attempted to show charging animation but island view is not ready. Creating...")
                createAndShowIsland()
                delay(100) // Give view time to be added
                if (islandView == null || !isViewAdded) {
                    Log.e(TAG, "❌ Failed to create island view for charging animation.")
                    return@launch
                }
            }
            notificationTitleView?.text = "Charging"
            notificationTextView?.text = "$batteryPct%"
            chargingIconView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
            delay(2000) // Show for 2 seconds
            chargingIconView?.visibility = View.GONE
            updateIslandState(expand = false, animate = true)
        }
    }






    // Network type handling
    private var networkTypeTextView: TextView? = null

    private fun setupNetworkTypeView() {
        islandView?.let {
            networkTypeTextView = it.findViewById(R.id.network_type_text)
        }
    }

    private fun updateNetworkType(networkCapabilities: NetworkCapabilities?) {
        mainScope.launch {
            val networkType = when {
                networkCapabilities == null -> ""
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                else -> ""
            }
            networkTypeTextView?.text = networkType
        }
    }



