package com.almobarmg.dynamicislandai

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.animation.doOnEnd
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// Data class for configuration (consider making it mutable if needed or provide update methods)
data class DynamicIslandConfig(
    val collapsedWidth: Int = 200, // Adjusted for typical pill shape
    val collapsedHeight: Int = 60,
    val expandedWidth: Int = 350,
    val expandedHeight: Int = 150,
    val positionY: Int = 30, // Pixels from top
    val animationDuration: Long = 250 // ms
)

class DynamicIslandManager private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutInflater = LayoutInflater.from(context)
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val config = DynamicIslandConfig()

    @SuppressLint("InflateParams") // OK for overlay view not attached to parent
    private var islandView: View? = null
    private var islandLayoutParams: WindowManager.LayoutParams? = null
    private var isViewAdded = false
    private var isExpanded = false
    private var currentAnimator: ValueAnimator? = null

    // Views cache (initialize after inflation)
    private var notificationTitleView: TextView? = null
    private var notificationTextView: TextView? = null
    private var timeTextView: TextView? = null
    private var batteryTextView: TextView? = null
    private var batteryIconView: ImageView? = null
    private var networkIconView: ImageView? = null
    private var historyContainerView: LinearLayout? = null

    // State tracking
    private val notificationHistory = mutableListOf<Pair<String, String>>()
    private var timeUpdateJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // *** ADDED: Store the current notification's PendingIntent ***
    private var currentNotificationIntent: PendingIntent? = null

    /**
     * Creates and adds the island view to the window manager if not already added.
     * Should only be called when necessary permissions are granted.
     */
    fun createAndShowIsland() {
        mainScope.launch {
            if (islandView != null && isViewAdded) {
                Log.d(TAG, "Island view already created and added.")
                islandView?.visibility = View.VISIBLE
                return@launch
            }

            if (!Settings.canDrawOverlays(context)) {
                Log.e(TAG, "❌ Cannot create island view: SYSTEM_ALERT_WINDOW permission missing.")
                Toast.makeText(context, "Overlay permission missing.", Toast.LENGTH_LONG).show()
                return@launch
            }

            try {
                if (islandView == null) {
                    Log.d(TAG, "Inflating island view layout.")
                    islandView = layoutInflater.inflate(R.layout.island_view, null)
                    cacheViewReferences()
                }

                if (islandLayoutParams == null) {
                    islandLayoutParams = WindowManager.LayoutParams(
                        config.collapsedWidth,
                        config.collapsedHeight,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = config.positionY
                    }
                }

                if (!isViewAdded && islandView != null) {
                    windowManager.addView(islandView, islandLayoutParams)
                    isViewAdded = true
                    Log.i(TAG, "✅ Island view added to WindowManager.")
                    islandView?.visibility = View.VISIBLE
                    updateIslandState(expand = false, animate = false)
                    setupInteractions() // Setup interactions AFTER view is added
                    startBackgroundUpdates()
                }
            } catch (e: WindowManager.BadTokenException) {
                Log.e(TAG, "❌ BadTokenException: Could not add island view.", e)
                cleanupView()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "❌ IllegalStateException adding island view.", e)
                cleanupView()
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
            Log.d(TAG, "Cached view references.")
        }
    }

    private fun setupInteractions() {
        islandView?.setOnClickListener {
            // *** MODIFIED: Handle click based on PendingIntent ***
            if (currentNotificationIntent != null) {
                try {
                    Log.i(TAG, "🏝️ Island clicked, launching PendingIntent: $currentNotificationIntent")
                    currentNotificationIntent?.send()
                    // Optionally collapse the island after launching the intent
                    // updateIslandState(expand = false, animate = true)
                } catch (e: PendingIntent.CanceledException) {
                    Log.e(TAG, "❌ PendingIntent was canceled, cannot launch.", e)
                    Toast.makeText(context, "Cannot open notification action.", Toast.LENGTH_SHORT).show()
                    // Fallback: Toggle expansion if intent fails?
                    // updateIslandState(expand = !isExpanded, animate = true)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error launching PendingIntent.", e)
                    Toast.makeText(context, "Error opening notification.", Toast.LENGTH_SHORT).show()
                    // Fallback: Toggle expansion if intent fails?
                    // updateIslandState(expand = !isExpanded, animate = true)
                }
            } else {
                // If no intent, just toggle the expansion state
                Log.d(TAG, "🏝️ Island clicked (no intent), toggling expansion state.")
                updateIslandState(expand = !isExpanded, animate = true)
            }
        }
    }

    /**
     * Displays a notification in the island.
     * *** MODIFIED: Accepts PendingIntent ***
     */
    fun showNotification(title: String, text: String, intent: PendingIntent?) {
        mainScope.launch {
            if (islandView == null || !isViewAdded) {
                Log.w(TAG, "⚠️ Attempted to show notification but island view is not ready. Creating...")
                createAndShowIsland()
                delay(100)
                if (islandView == null || !isViewAdded) {
                    Log.e(TAG, "❌ Failed to create island view for notification.")
                    return@launch
                }
            }

            Log.d(TAG, "Updating island with notification: Title=\"$title\", Text=\"$text\", Intent=${intent}")
            notificationHistory.add(0, title to text) // Add to history (intent not stored in history)

            // *** ADDED: Store the new intent, replacing the old one ***
            currentNotificationIntent = intent

            notificationTitleView?.text = title
            notificationTextView?.text = text
            Log.d(TAG, "DIAGNOSTIC: Text set in TextViews. Title View: ${notificationTitleView?.text}, Text View: ${notificationTextView?.text}")

            if (notificationTitleView == null || notificationTextView == null) {
                Log.e(TAG, "❌ Failed to find notification text views in island layout!")
            }

            islandView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
        }
    }

    /**
     * Updates the island's expanded/collapsed state, optionally animating.
     */
    private fun updateIslandState(expand: Boolean, animate: Boolean) {
        if (islandView == null || islandLayoutParams == null || !isViewAdded) {
            Log.w(TAG, "⚠️ Cannot update island state: View not ready.")
            return
        }
        // *** MODIFIED: Don't return early if state is same but intent might have changed ***
        // if (expand == isExpanded && currentAnimator?.isRunning != true) {
        //      Log.d(TAG, "Island state already ${if (expand) "expanded" else "collapsed"}. No change needed.")
        //      historyContainerView?.visibility = if (expand) View.VISIBLE else View.GONE
        //      if (expand) updateHistoryView()
        //      return
        // }

        Log.i(TAG, "Updating island state: ${if (expand) "Expanding" else "Collapsing"} (Animate: $animate)")
        isExpanded = expand
        currentAnimator?.cancel()

        val startWidth = islandLayoutParams!!.width
        val startHeight = islandLayoutParams!!.height
        val targetWidth = if (expand) config.expandedWidth else config.collapsedWidth
        val targetHeight = if (expand) config.expandedHeight else config.collapsedHeight

        // *** ADDED: Clear intent when collapsing ***
        if (!expand) {
            Log.d(TAG, "Clearing notification intent as island is collapsing.")
            currentNotificationIntent = null
        }

        if (animate) {
            currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = config.animationDuration
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animation ->
                    val fraction = animation.animatedValue as Float
                    islandLayoutParams?.apply {
                        width = (startWidth + (targetWidth - startWidth) * fraction).toInt()
                        height = (startHeight + (targetHeight - startHeight) * fraction).toInt()
                    }
                    try {
                        if (isViewAdded && islandView != null) {
                            Log.v(TAG, "DIAGNOSTIC (Anim): Updating view layout. Width=${islandLayoutParams?.width}, Height=${islandLayoutParams?.height}")
                            windowManager.updateViewLayout(islandView, islandLayoutParams)
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "❌ Error updating view layout during animation.", e)
                        cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Unexpected error updating view layout during animation.", e)
                        cancel()
                    }
                }
                doOnEnd {
                    Log.d(TAG, "Animation ended. Final state: ${if (isExpanded) "Expanded" else "Collapsed"}")
                    historyContainerView?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    if (isExpanded) updateHistoryView()
                    currentAnimator = null
                }
                start()
            }
        } else {
            islandLayoutParams?.apply {
                width = targetWidth
                height = targetHeight
            }
            try {
                Log.d(TAG, "DIAGNOSTIC (Immediate): Updating view layout. Width=${islandLayoutParams?.width}, Height=${islandLayoutParams?.height}")
                windowManager.updateViewLayout(islandView, islandLayoutParams)
                Log.d(TAG, "DIAGNOSTIC (Immediate): updateViewLayout successful.")
                historyContainerView?.visibility = if (expand) View.VISIBLE else View.GONE
                if (expand) updateHistoryView()
                Log.d(TAG, "Island state set immediately to ${if (expand) "expanded" else "collapsed"}.")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "❌ Error updating view layout immediately.", e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error updating view layout immediately.", e)
            }
        }
    }

    /**
     * Updates the content of the notification history view when expanded.
     */
    @SuppressLint("InflateParams")
    private fun updateHistoryView() {
        historyContainerView?.let { container ->
            Log.d(TAG, "Updating history view with ${notificationHistory.size} items.")
            container.removeAllViews()
            if (notificationHistory.isEmpty()) {
                val emptyView = TextView(context).apply { text = "No recent notifications" }
                container.addView(emptyView)
                return@let
            }

            notificationHistory.forEachIndexed { index, (title, text) ->
                try {
                    val itemView = layoutInflater.inflate(R.layout.notification_item, container, false)
                    itemView.findViewById<TextView>(R.id.title)?.text = title
                    itemView.findViewById<TextView>(R.id.text)?.text = text
                    itemView.findViewById<Button>(R.id.remove_button)?.setOnClickListener {
                        Log.d(TAG, "Removing notification history item at index $index")
                        notificationHistory.removeAt(index)
                        updateHistoryView()
                    }
                    container.addView(itemView)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error inflating or adding notification history item at index $index", e)
                }
            }
        }
    }

    /**
     * Starts background tasks like time, battery, and network updates.
     */
    private fun startBackgroundUpdates() {
        Log.d(TAG, "Starting background updates (Time, Battery, Network).")
        startTimeUpdater()
        registerBatteryReceiver()
        registerNetworkCallback()
        updateNetworkStatus()
    }

    /**
     * Stops background tasks.
     */
    private fun stopBackgroundUpdates() {
        Log.d(TAG, "Stopping background updates.")
        timeUpdateJob?.cancel()
        unregisterBatteryReceiver()
        unregisterNetworkCallback()
    }

    /**
     * Starts a recurring coroutine to update the time display.
     */
    private fun startTimeUpdater() {
        if (timeUpdateJob?.isActive == true) return
        timeUpdateJob = mainScope.launch {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            while (isActive) {
                try {
                    val currentTime = sdf.format(Date())
                    if (timeTextView == null && islandView != null) {
                        Log.w(TAG, "Time TextView (R.id.time_text) not found in layout.")
                    }
                    timeTextView?.text = currentTime
                    delay(TimeUnit.MINUTES.toMillis(1))
                } catch (e: Exception) {
                    Log.e(TAG, "Error in time updater coroutine", e)
                    delay(TimeUnit.MINUTES.toMillis(1))
                }
            }
        }
        Log.d(TAG, "Time updater started.")
    }

    /**
     * Registers a broadcast receiver to monitor battery changes.
     */
    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1

                    if (batteryTextView == null || batteryIconView == null && islandView != null) {
                        Log.w(TAG, "Battery views not found in layout.")
                    }
                    batteryTextView?.text = if (batteryPct != -1) "$batteryPct%" else "--%"
                    batteryIconView?.setImageResource(
                        if (isCharging) R.drawable.ic_battery_charging
                        else R.drawable.ic_battery_charging
                    )
                }
            }
        }
        try {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            Log.d(TAG, "Battery receiver registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering battery receiver", e)
            batteryReceiver = null
        }
    }

    private fun unregisterBatteryReceiver() {
        if (batteryReceiver != null) {
            try {
                context.unregisterReceiver(batteryReceiver)
                Log.d(TAG, "Battery receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Battery receiver already unregistered or not registered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering battery receiver", e)
            }
            batteryReceiver = null
        }
    }

    /**
     * Registers a network callback to monitor connectivity changes.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    mainScope.launch { updateNetworkStatus() }
                }
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    mainScope.launch { updateNetworkStatus() }
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    mainScope.launch { updateNetworkStatus() }
                }
            }
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network callback registered.")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException registering network callback.", e)
            networkCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback", e)
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        if (networkCallback != null) {
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(networkCallback!!)
                Log.d(TAG, "Network callback unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Network callback already unregistered or not registered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    /**
     * Updates the network status icon based on current connectivity.
     */
    private fun updateNetworkStatus() {
        if (networkIconView == null && islandView != null) {
            Log.w(TAG, "Network icon view (R.id.network_icon) not found.")
            return
        }
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            val iconResId = when {
                capabilities == null -> R.drawable.ic_no_signal
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.drawable.ic_wifi
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.drawable.ic_mobile_data
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_mobile_data
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> R.drawable.ic_mobile_data
                else -> R.drawable.ic_no_signal
            }
            networkIconView?.setImageResource(iconResId)
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException getting network capabilities.", e)
            networkIconView?.setImageResource(R.drawable.ic_no_signal)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating network status icon", e)
            networkIconView?.setImageResource(R.drawable.ic_no_signal)
        }
    }

    /**
     * Cleans up resources: removes view, cancels coroutines, unregisters receivers.
     */
    fun cleanup() {
        Log.i(TAG, "🧹 Cleaning up DynamicIslandManager resources.")
        mainScope.cancel()
        stopBackgroundUpdates()
        cleanupView()
        instance = null
    }

    private fun cleanupView() {
        mainScope.launch {
            currentAnimator?.cancel()
            if (islandView != null && isViewAdded) {
                try {
                    windowManager.removeView(islandView)
                    Log.d(TAG, "Island view removed from WindowManager.")
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Attempted to remove view that was not added or already removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing island view", e)
                }
            }
            islandView = null
            islandLayoutParams = null
            isViewAdded = false
            isExpanded = false
            // *** ADDED: Clear intent on cleanup ***
            currentNotificationIntent = null
            // Clear view references
            notificationTitleView = null
            notificationTextView = null
            timeTextView = null
            batteryTextView = null
            batteryIconView = null
            networkIconView = null
            historyContainerView = null
        }
    }

    companion object {
        private const val TAG = "DynamicIslandManager"

        @Volatile private var instance: DynamicIslandManager? = null

        fun getInstance(context: Context): DynamicIslandManager {
            return instance ?: synchronized(this) {
                instance ?: DynamicIslandManager(context.applicationContext).also {
                    Log.i(TAG, "Creating new DynamicIslandManager instance.")
                    instance = it
                }
            }
        }
    }
}

