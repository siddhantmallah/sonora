package com.sonora.player.playback

import android.util.Log
import com.sonora.player.data.SettingsRepository
import com.sonora.player.data.splitArtists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scrobbles to ListenBrainz (open, free, no app key needed). The user pastes their personal token in Settings;
 * it is stored on the phone and only sent to api.listenbrainz.org.
 */
class Scrobbler(private val http: OkHttpClient, private val settings: SettingsRepository) {
    private val json = "application/json".toMediaType()

    private val token: String get() = settings.state.value.listenBrainzToken.trim()
    val enabled: Boolean get() = token.isNotEmpty()

    suspend fun nowPlaying(title: String, artist: String, album: String?) {
        if (!enabled || title.isBlank()) return
        submit("playing_now", JSONObject().put("track_metadata", meta(title, artist, album, 0)))
    }

    suspend fun listened(title: String, artist: String, album: String?, durationMs: Long, at: Long) {
        if (!enabled || title.isBlank()) return
        submit(
            "single",
            JSONObject().put("listened_at", at / 1000).put("track_metadata", meta(title, artist, album, durationMs)),
        )
    }

    /** Returns the ListenBrainz user name for [candidate], or null if the token is invalid. */
    suspend fun validate(candidate: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$API/1/validate-token")
                .header("Authorization", "Token ${candidate.trim()}").get().build()
            http.newCall(req).execute().use { r ->
                val o = JSONObject(r.body.string())
                if (o.optBoolean("valid")) o.optString("user_name").ifBlank { "you" } else null
            }
        }.getOrNull()
    }

    private fun meta(title: String, artist: String, album: String?, durationMs: Long) = JSONObject()
        .put("track_name", title)
        .put("artist_name", splitArtists(artist).joinToString(", "))
        .apply { if (!album.isNullOrBlank()) put("release_name", album) }
        .put(
            "additional_info",
            JSONObject().put("media_player", "Sonora").put("submission_client", "Sonora")
                .apply { if (durationMs > 0) put("duration_ms", durationMs) },
        )

    private suspend fun submit(type: String, item: JSONObject) = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("listen_type", type).put("payload", JSONArray().put(item))
            val req = Request.Builder().url("$API/1/submit-listens")
                .header("Authorization", "Token $token")
                .post(body.toString().toRequestBody(json)).build()
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) Log.w("SonoraScrobble", "ListenBrainz ${r.code}") }
        }.onFailure { Log.w("SonoraScrobble", "scrobble failed", it) }
    }

    companion object { private const val API = "https://api.listenbrainz.org" }
}
