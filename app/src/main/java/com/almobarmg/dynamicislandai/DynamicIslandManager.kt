package com.almobarmg.dynamicislandai

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
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// Data class for configuration
data class DynamicIslandConfig(
    val collapsedWidthDp: Int = 100, // Width when collapsed (dp)
    val collapsedHeightDp: Int = 30, // Height when collapsed (dp)
    // Expanded width will be calculated based on screen width
    val expandedHeightDp: Int = 100, // Increased height when expanded (dp)
    val horizontalMarginDp: Int = 16, // Margin from screen edges when expanded (dp)
    val positionYDp: Int = 15, // Pixels from top (dp)
    val animationDuration: Long = 250 // ms
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
    // -------------------------------------------------------------

    @SuppressLint("InflateParams")
    private var islandView: View? = null
    private var islandLayoutParams: WindowManager.LayoutParams? = null
    private var isViewAdded = false
    private var isExpanded = false
    private var currentAnimator: ValueAnimator? = null

    // Views cache
    private var notificationTitleView: TextView? = null
    private var notificationTextView: TextView? = null
    private var timeTextView: TextView? = null
    private var batteryTextView: TextView? = null
    private var batteryIconView: ImageView? = null
    private var networkIconView: ImageView? = null
    private var historyContainerView: LinearLayout? = null
    // *** ADDED: Reference to the main content area within the island ***
    private var islandContentLayout: View? = null

    // State tracking
    private val notificationHistory = mutableListOf<Pair<String, String>>()
    private var timeUpdateJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNotificationIntent: PendingIntent? = null

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
                        collapsedWidthPx, // Use calculated pixel values
                        collapsedHeightPx,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = positionYPx // Use calculated pixel value
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
            // *** ADDED: Cache the content layout ***
            islandContentLayout = it.findViewById(R.id.island_content_layout) // Make sure this ID exists in island_view.xml
            Log.d(TAG, "Cached view references.")
        }
    }

    private fun setupInteractions() {
        islandView?.setOnClickListener {
            if (currentNotificationIntent != null) {
                try {
                    Log.i(TAG, "🏝️ Island clicked, launching PendingIntent: $currentNotificationIntent")
                    currentNotificationIntent?.send()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error launching PendingIntent.", e)
                    Toast.makeText(context, "Error opening notification.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d(TAG, "🏝️ Island clicked (no intent), toggling expansion state.")
                updateIslandState(expand = !isExpanded, animate = true)
            }
        }
    }

    fun showNotification(title: String, text: String, intent: PendingIntent?) {
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

            Log.d(TAG, "Updating island with notification: Title=\"$title\", Text=\"$text\", Intent=${intent}")
            notificationHistory.add(0, title to text)
            currentNotificationIntent = intent

            notificationTitleView?.text = title
            notificationTextView?.text = text
            Log.d(TAG, "DIAGNOSTIC: Text set in TextViews. Title=\"${notificationTitleView?.text}\", Text=\"${notificationTextView?.text}\"")

            islandView?.visibility = View.VISIBLE
            updateIslandState(expand = true, animate = true)
        }
    }

    private fun updateIslandState(expand: Boolean, animate: Boolean) {
        if (islandView == null || islandLayoutParams == null || !isViewAdded) {
            Log.w(TAG, "⚠️ Cannot update island state: View not ready.")
            return
        }

        Log.i(TAG, "Updating island state: ${if (expand) "Expanding" else "Collapsing"} (Animate: $animate)")
        isExpanded = expand
        currentAnimator?.cancel()

        val startWidth = islandLayoutParams!!.width
        val startHeight = islandLayoutParams!!.height
        // *** Use calculated pixel dimensions ***
        val targetWidth = if (expand) expandedWidthPx else collapsedWidthPx
        val targetHeight = if (expand) expandedHeightPx else collapsedHeightPx

        if (!expand) {
            Log.d(TAG, "Clearing notification intent as island is collapsing.")
            currentNotificationIntent = null
        }

        // *** Control visibility of content during animation/state change ***
        val targetContentVisibility = if (expand) View.VISIBLE else View.INVISIBLE // Use INVISIBLE to keep layout space
        val targetHistoryVisibility = if (expand) View.VISIBLE else View.GONE

        if (animate) {
            // Set content visibility immediately at start of animation for expansion
            if (expand) {
                islandContentLayout?.visibility = targetContentVisibility
                notificationTitleView?.visibility = targetContentVisibility
                notificationTextView?.visibility = targetContentVisibility
            }

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
                            windowManager.updateViewLayout(islandView, islandLayoutParams)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error updating view layout during animation.", e)
                        cancel()
                    }
                }
                doOnEnd {
                    Log.d(TAG, "Animation ended. Final state: ${if (isExpanded) "Expanded" else "Collapsed"}")
                    // Set final visibility states after animation
                    islandContentLayout?.visibility = targetContentVisibility
                    notificationTitleView?.visibility = targetContentVisibility
                    notificationTextView?.visibility = targetContentVisibility
                    historyContainerView?.visibility = targetHistoryVisibility
                    if (isExpanded) updateHistoryView()
                    currentAnimator = null
                }
                start()
            }
        } else {
            // Set state immediately
            islandLayoutParams?.apply {
                width = targetWidth
                height = targetHeight
            }
            try {
                windowManager.updateViewLayout(islandView, islandLayoutParams)
                islandContentLayout?.visibility = targetContentVisibility
                notificationTitleView?.visibility = targetContentVisibility
                notificationTextView?.visibility = targetContentVisibility
                historyContainerView?.visibility = targetHistoryVisibility
                if (expand) updateHistoryView()
                Log.d(TAG, "Island state set immediately to ${if (expand) "expanded" else "collapsed"}.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error updating view layout immediately.", e)
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun updateHistoryView() {
        // ... (keep existing history view logic)
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

    private fun startBackgroundUpdates() {
        // ... (keep existing background update logic)
        Log.d(TAG, "Starting background updates (Time, Battery, Network).")
        startTimeUpdater()
        registerBatteryReceiver()
        registerNetworkCallback()
        updateNetworkStatus()
    }

    private fun stopBackgroundUpdates() {
        // ... (keep existing background update logic)
        Log.d(TAG, "Stopping background updates.")
        timeUpdateJob?.cancel()
        unregisterBatteryReceiver()
        unregisterNetworkCallback()
    }

    private fun startTimeUpdater() {
        // ... (keep existing time updater logic)
        if (timeUpdateJob?.isActive == true) return
        timeUpdateJob = mainScope.launch {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            while (isActive) {
                try {
                    timeTextView?.text = sdf.format(Date())
                    delay(TimeUnit.MINUTES.toMillis(1))
                } catch (e: CancellationException) {
                    Log.d(TAG, "Time updater job cancelled.")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in time updater", e)
                    delay(TimeUnit.MINUTES.toMillis(1)) // Wait before retrying
                }
            }
        }
        Log.d(TAG, "Time updater started.")
    }

    private fun registerBatteryReceiver() {
        // ... (keep existing battery receiver logic)
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                if (level != -1 && scale != -1) {
                    val batteryPct = (level * 100 / scale.toFloat()).toInt()
                    batteryTextView?.text = "$batteryPct%"
                }
                batteryIconView?.setImageResource(
                    if (isCharging) R.drawable.ic_battery_charging // Ensure this drawable exists
                    else R.drawable.ic_battery_charging // Ensure this drawable exists
                )
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
        // ... (keep existing battery unregister logic)
        if (batteryReceiver != null) {
            try {
                context.unregisterReceiver(batteryReceiver)
                Log.d(TAG, "Battery receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Battery receiver already unregistered?", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering battery receiver", e)
            }
            batteryReceiver = null
        }
    }

    private fun registerNetworkCallback() {
        // ... (keep existing network callback logic)
        if (networkCallback != null) return
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainScope.launch { updateNetworkStatus() }
            }
            override fun onLost(network: Network) {
                mainScope.launch { updateNetworkStatus() }
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                mainScope.launch { updateNetworkStatus() }
            }
        }
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "Network callback registered.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing ACCESS_NETWORK_STATE permission for network callback?", e)
            networkCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback", e)
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        // ... (keep existing network unregister logic)
        if (networkCallback != null) {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback!!)
                Log.d(TAG, "Network callback unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Network callback already unregistered?", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    private fun updateNetworkStatus() {
        // ... (keep existing network status update logic)
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            val iconResId = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> R.drawable.ic_wifi // Ensure exists
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> R.drawable.ic_mobile_data // Ensure exists
                else -> R.drawable.ic_no_signal // Ensure exists
            }
            networkIconView?.setImageResource(iconResId)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing ACCESS_NETWORK_STATE permission for getNetworkCapabilities?", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating network status", e)
        }
    }

    /**
     * Cleans up resources: removes view, stops updates, cancels coroutines.
     */
    fun cleanup() {
        Log.i(TAG, "Cleaning up DynamicIslandManager resources.")
        mainScope.cancel() // Cancel all coroutines
        stopBackgroundUpdates()
        cleanupView()
        instance = null // Allow garbage collection
    }

    private fun cleanupView() {
        if (islandView != null && isViewAdded) {
            try {
                windowManager.removeView(islandView)
                Log.i(TAG, "Island view removed from WindowManager.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Attempted to remove view that was not added?", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing island view", e)
            }
        }
        islandView = null
        islandLayoutParams = null
        isViewAdded = false
        // Clear view references
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
        @Volatile private var instance: DynamicIslandManager? = null

        fun getInstance(context: Context): DynamicIslandManager {
            return instance ?: synchronized(this) {
                instance ?: DynamicIslandManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

