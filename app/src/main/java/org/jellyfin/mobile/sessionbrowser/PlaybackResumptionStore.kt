package org.jellyfin.mobile.sessionbrowser

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.Player
import kotlinx.serialization.json.Json

/**
 * Remembers what was playing, so the app can pick up where it left off when something asks it to
 * resume: Android Auto's "Start music automatically" when the phone connects to the car, a
 * Bluetooth play button, or the system's media card.
 *
 * Only a window around the current song is kept: resuming looks the songs up again in one request.
 */
class PlaybackResumptionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Saved(val mediaIds: List<String>, val index: Int, val positionMs: Long)

    fun save(player: Player) {
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val from = (current - BEFORE).coerceAtLeast(0)
        val to = (current + AFTER).coerceAtMost(count - 1)
        val ids = (from..to).map { player.getMediaItemAt(it).mediaId }
        prefs.edit {
            putString(KEY_IDS, Json.encodeToString(ids))
            putInt(KEY_INDEX, current - from)
            putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
        }
    }

    fun load(): Saved? {
        val ids = prefs.getString(KEY_IDS, null)
            ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return Saved(
            mediaIds = ids,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.size - 1),
            positionMs = prefs.getLong(KEY_POSITION, 0),
        )
    }

    private companion object {
        const val PREFS = "playback_resumption"
        const val KEY_IDS = "media_ids"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position_ms"

        // Up to 100 songs: the ids go in one request's address when resuming.
        const val BEFORE = 20
        const val AFTER = 79
    }
}
