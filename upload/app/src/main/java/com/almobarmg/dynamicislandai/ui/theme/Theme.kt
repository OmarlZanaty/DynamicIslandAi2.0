package com.almobarmg.dynamicislandai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.core.view.WindowCompat
import com.almobarmg.dynamicislandai.R

/**
 * Apply the app's theme to an Activity.
 */
fun applyDynamicIslandTheme(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    }
    // Optionally set a theme programmatically if needed
    activity.setTheme(R.style.Theme_DynamicIslandAi)
}