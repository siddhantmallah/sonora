package com.sonora.player.youtube

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Holds the music.youtube.com session cookies captured from the sign-in WebView. They stay in this
 * app's private storage, are only ever sent to music.youtube.com, and are wiped on sign-out.
 */
class YouTubeAuth(context: Context) {
    private val prefs = context.getSharedPreferences("youtube_session", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<YtAccount?> = _account.asStateFlow()

    private val _signedIn = MutableStateFlow(hasSession(prefs.getString(KEY_COOKIE, null)))
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    val cookie: String? get() = prefs.getString(KEY_COOKIE, null)?.takeIf(::hasSession)

    fun saveCookie(cookie: String) {
        prefs.edit().putString(KEY_COOKIE, cookie).apply()
        _signedIn.value = hasSession(cookie)
    }

    fun saveAccount(a: YtAccount?) {
        _account.value = a
        prefs.edit().putString(KEY_ACCOUNT, a?.let { JSONObject().put("n", it.name).put("h", it.handle).put("p", it.photo).toString() }).apply()
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _signedIn.value = false
        _account.value = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** SAPISIDHASH header that authenticates InnerTube calls for a cookie session. */
    fun authorization(): String? {
        val c = cookie ?: return null
        val sapisid = Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(c)?.groupValues?.get(1)
            ?: Regex("(?:^|;\\s*)__Secure-3PAPISID=([^;]+)").find(c)?.groupValues?.get(1)
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid ${InnerTube.ORIGIN}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hash"
    }

    private fun loadAccount(): YtAccount? = prefs.getString(KEY_ACCOUNT, null)?.let {
        runCatching { JSONObject(it).let { o -> YtAccount(o.optString("n"), o.optString("h").ifBlank { null }, o.optString("p").ifBlank { null }) } }.getOrNull()
    }

    companion object {
        private const val KEY_COOKIE = "cookie"
        private const val KEY_ACCOUNT = "account"
        fun hasSession(cookie: String?) = cookie != null && (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID="))
    }
}
