package com.sonora.player.ui.drive

import android.annotation.SuppressLint
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Speed (km/h) above which you're clearly in a car or on a bike, not walking or jogging. */
private const val DRIVING_KMH = 20f

/** After Sonora opens Driving mode once, it waits for the trip to end (2 min below walking pace) before doing it again. */
private object AutoDriveMemory {
    var openedThisTrip = false
    var slowSince = 0L
}

/**
 * Opens Driving mode by itself when music is playing and you're moving faster than 20 km/h for a few seconds (GPS).
 * Only while Sonora is on screen: Android doesn't let an app watch your speed or open screens from the background.
 * If you leave Driving mode, it won't reopen until that trip is over.
 */
@SuppressLint("MissingPermission")
@Composable
fun AutoDriveWatcher(active: Boolean, inDrive: Boolean, onDriving: () -> Unit) {
    val context = LocalContext.current
    val open = rememberUpdatedState(onDriving)
    val alreadyThere = rememberUpdatedState(inDrive)
    DisposableEffect(active) {
        if (!active || !hasLocation(context)) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(LocationManager::class.java) ?: return@DisposableEffect onDispose { }
        var fast = 0
        val listener = LocationListener { loc ->
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val now = System.currentTimeMillis()
            if (kmh < 5f) {
                if (AutoDriveMemory.slowSince == 0L) AutoDriveMemory.slowSince = now
                if (now - AutoDriveMemory.slowSince > 120_000) AutoDriveMemory.openedThisTrip = false
            } else AutoDriveMemory.slowSince = 0L
            fast = if (kmh > DRIVING_KMH) fast + 1 else 0
            // Three fixes in a row (about 6 s) so a GPS jump can't trigger it.
            if (fast >= 3 && !alreadyThere.value && !AutoDriveMemory.openedThisTrip) {
                AutoDriveMemory.openedThisTrip = true
                open.value()
            }
        }
        val provider = if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER
        else LocationManager.GPS_PROVIDER
        runCatching { lm.requestLocationUpdates(provider, 2000L, 0f, listener, Looper.getMainLooper()) }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
}
