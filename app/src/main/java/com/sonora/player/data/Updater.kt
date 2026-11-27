package com.sonora.player.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.sonora.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** A newer Sonora on GitHub Releases. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** Downloaded; Android's installer takes it from here (and asks the user to confirm). */
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Android needs "Install unknown apps" allowed for Sonora first. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Sonora updates itself from its GitHub Releases: checks the latest release, downloads the APK and hands it to
 * Android's package installer. Android only accepts it as an update when it's signed with the same key as the
 * installed app, so a tampered APK can't replace Sonora.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checks GitHub (at most every [minIntervalMs] unless [force]). */
    suspend fun check(force: Boolean = false, minIntervalMs: Long = 6 * 3600_000L): UpdateState {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0) < minIntervalMs && _state.value !is UpdateState.Idle) return _state.value
        _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(API_LATEST).header("Accept", "application/vnd.github+json").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("GitHub said ${r.code}")
                    val o = JSONObject(r.body.string())
                    val tag = o.getString("tag_name").removePrefix("v")
                    val assets = o.optJSONArray("assets")
                    var url: String? = null
                    var size = 0L
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            url = a.getString("browser_download_url"); size = a.optLong("size"); break
                        }
                    }
                    if (url == null || !isNewer(tag, currentVersion)) UpdateState.UpToDate
                    else UpdateState.Available(UpdateInfo(tag, o.optString("body"), url, size, o.optString("html_url")))
                }
            }.getOrElse { UpdateState.Failed("Couldn't check for updates (${it.message ?: "no connection"})") }
        }
        prefs.edit().putLong("last_check", now).apply()
        _state.value = result
        return result
    }

    /** Was this version's "later" already chosen (so the start-up prompt doesn't nag)? */
    fun isSnoozed(version: String) = prefs.getString("snoozed", null) == version
    fun snooze(version: String) { prefs.edit().putString("snoozed", version).apply() }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for Sonora. */
    fun openInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Downloads the APK (with progress) and starts the install. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        if (!canInstall()) { _state.value = UpdateState.NeedsPermission(info); return }
        _state.value = UpdateState.Downloading(info, 0f)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "Sonora-${info.version}.apk")
                http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) error("download failed (${r.code})")
                    val total = r.body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                    r.body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(info, if (total > 0) done.toFloat() / total else 0f) }
                            }
                        }
                    }
                }
                out
            }.getOrElse { _state.value = UpdateState.Failed("Download failed (${it.message})", info); null }
        } ?: return
        install(info, file)
    }

    private suspend fun install(info: UpdateInfo, apk: File) = withContext(Dispatchers.IO) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("sonora.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
            _state.value = UpdateState.Installing(info)
        }.onFailure { _state.value = UpdateState.Failed("Couldn't start the install (${it.message})", info) }
    }

    internal fun onInstallFailed(message: String) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = UpdateState.Failed(message, info)
    }

    companion object {
        const val REPO = "siddhantmallah/sonora"
        private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

        /** "1.10.0" > "1.9.2"; ignores anything after a "-" (pre-release tags). */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(remote); val b = parts(local)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives the package installer's result: shows Android's confirm screen, or reports a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.d("SonoraUpdate", "installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.d("SonoraUpdate", "install failed: $msg")
                runCatching { (context.applicationContext as com.sonora.player.SonoraApp).container.updater.onInstallFailed("Install didn't finish ($msg)") }
            }
        }
    }
}
