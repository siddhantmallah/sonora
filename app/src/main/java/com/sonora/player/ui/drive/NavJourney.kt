package com.sonora.player.ui.drive

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The journey your navigation app is guiding right now, as its notification shows it. */
@Immutable
data class Journey(
    val key: String,
    val app: String,
    /** Usually the distance to the next turn ("300 m"), sometimes the instruction. */
    val headline: String,
    /** Usually the instruction ("Turn left onto MG Road"). */
    val detail: String?,
    /** Usually time left, distance left and arrival ("12 min · 4.2 km · 17:45"). */
    val footer: String?,
    /** The turn arrow the navigation app draws. */
    val arrow: Bitmap?,
    /** 0..1 when the app reports progress, else null. */
    val progress: Float?,
    val open: PendingIntent?,
)

/**
 * Sonora's view of an ongoing journey. Filled by [NavListener] from the navigation app's own notification (Google
 * Maps, Waze, OsmAnd, Organic Maps, HERE, Sygic…): Google doesn't offer an API for this, but every nav app keeps a
 * notification with the next turn while it guides you. Only navigation notifications are read; nothing is stored.
 */
object NavJourney {
    private val _state = MutableStateFlow<Journey?>(null)
    val state: StateFlow<Journey?> = _state.asStateFlow()

    internal fun set(j: Journey?) { _state.value = j }

    /** Whether you've let Sonora see notifications (needed to read the journey). */
    fun hasAccess(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /**
     * Asks Android to (re)connect the listener: after an app update Android can leave it disconnected until the next
     * reboot. Harmless when it's already connected.
     */
    fun reconnect(context: Context) {
        if (!hasAccess(context)) return
        runCatching { NotificationListenerService.requestRebind(ComponentName(context, NavListener::class.java)) }
    }

    /** Opens Android's notification-access page (straight to Sonora where Android allows). */
    fun requestAccess(context: Context) {
        val component = ComponentName(context, NavListener::class.java).flattenToString()
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

/** Reads the navigation app's ongoing notification and nothing else. */
class NavListener : NotificationListenerService() {

    override fun onListenerConnected() {
        android.util.Log.i(TAG, "connected")
        runCatching { activeNotifications?.forEach { consider(it) } }.onFailure { android.util.Log.w(TAG, "scan failed", it) }
    }

    override fun onListenerDisconnected() = NavJourney.set(null)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { consider(sbn) }.onFailure { android.util.Log.w(TAG, "couldn't read ${sbn.packageName}", it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (NavJourney.state.value?.key == sbn.key) NavJourney.set(null)
    }

    private fun consider(sbn: StatusBarNotification) {
        val n = sbn.notification
        val navApp = sbn.packageName in NAV_APPS || n.category == "navigation"
        if (!navApp || n.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim()
        val sub = (e.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: e.getCharSequence(Notification.EXTRA_INFO_TEXT))?.toString()?.trim()
        if (title.isBlank() && text.isNullOrBlank()) return
        // Google Maps (Android 16 Live Update): title "60 m · Turn left onto Link Rd", short text "60 m".
        val short = e.getCharSequence("android.shortCriticalText")?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val parts = title.split(" · ", limit = 2)
        val (head, instruction) = when {
            parts.size == 2 -> parts[0].trim() to parts[1].trim()
            short != null && title != short -> short to title
            else -> title to text
        }
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = if (max > 0) e.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max else null
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault("Navigation")
        android.util.Log.i(TAG, "journey from ${sbn.packageName}: $head | $instruction | $sub")
        NavJourney.set(
            Journey(
                key = sbn.key, app = app,
                headline = head.ifBlank { text.orEmpty() },
                detail = instruction?.takeIf { it.isNotBlank() && it != head },
                footer = sub?.takeIf { it.isNotBlank() },
                arrow = arrowOf(n),
                progress = progress,
                open = n.contentIntent,
            ),
        )
    }

    private fun arrowOf(n: Notification): Bitmap? = runCatching {
        val icon: Icon? = n.getLargeIcon()
        val d = icon?.loadDrawable(this) ?: return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 128
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 128
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b); d.setBounds(0, 0, w, h); d.draw(c)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "SonoraNav"
        val NAV_APPS = setOf(
            "com.google.android.apps.maps", "com.google.android.apps.mapslite", "com.waze", "net.osmand",
            "net.osmand.plus", "app.organicmaps", "com.here.app.maps", "com.sygic.aura", "com.mapfactor.navigator",
            "com.tomtom.gplay.navapp", "ru.yandex.yandexnavi", "com.mapquest.android.ace", "cz.seznam.mapy",
        )
    }
}
