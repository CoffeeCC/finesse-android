package org.jellyfin.mobile.sessionbrowser

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tells the server what the car plays, the way Finesse in the browser does: songs count as played,
 * show under Recently played, and the server shows them as playing now.
 *
 * Reports are best effort: a failed one (no signal) is logged and dropped, never retried, and
 * never stops the music.
 */
class PlaybackReporter(private val player: Player, private val api: ApiClient) : Player.Listener {
    private class Playing(val itemId: UUID) {
        val playSessionId: String = UUID.randomUUID().toString()
        var started = false
        var positionMs = 0L
        var durationMs = C.TIME_UNSET
    }

    // Reports outlive the service's own work: a stop sent as the service ends should still go out.
    private val network = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var current: Playing? = null

    fun attach() {
        player.addListener(this)
        current = player.currentMediaItem?.toPlaying()
        if (player.isPlaying) onIsPlayingChanged(true)
    }

    fun release() {
        player.removeListener(this)
        ticker?.cancel()
        current?.let { remember(it); stop(it, finished = player.playbackState == Player.STATE_ENDED) }
        current = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Moving on by itself means the last song played to the end.
        val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        current?.let { stop(it, finished) }
        current = mediaItem?.toPlaying()
        if (player.isPlaying) current?.let(::start)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val playing = current ?: return
        if (isPlaying) {
            if (!playing.started) start(playing) else progress(playing)
            ticker?.cancel()
            ticker = main.launch {
                while (isActive) {
                    delay(PROGRESS_INTERVAL_MS)
                    current?.let(::progress)
                }
            }
        } else {
            ticker?.cancel()
            if (playing.started) progress(playing)
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) current?.takeIf { it.started }?.let(::progress)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // The end of the queue: the last song finished.
        if (playbackState == Player.STATE_ENDED) {
            current?.let { stop(it, finished = true) }
            current = null
        }
    }

    private fun remember(playing: Playing) {
        playing.positionMs = player.currentPosition.coerceAtLeast(0)
        player.duration.takeIf { it != C.TIME_UNSET }?.let { playing.durationMs = it }
    }

    private fun start(playing: Playing) {
        playing.started = true
        remember(playing)
        val info = PlaybackStartInfo(
            itemId = playing.itemId,
            playSessionId = playing.playSessionId,
            positionTicks = playing.positionMs.milliseconds.inWholeTicks,
            canSeek = true,
            isPaused = false,
            isMuted = false,
            playMethod = PlayMethod.DIRECT_PLAY,
            repeatMode = RepeatMode.REPEAT_NONE,
            playbackOrder = PlaybackOrder.DEFAULT,
        )
        send("start") { api.playStateApi.reportPlaybackStart(info) }
    }

    private fun progress(playing: Playing) {
        if (!playing.started) return
        remember(playing)
        val info = PlaybackProgressInfo(
            itemId = playing.itemId,
            playSessionId = playing.playSessionId,
            positionTicks = playing.positionMs.milliseconds.inWholeTicks,
            canSeek = true,
            isPaused = !player.isPlaying,
            isMuted = false,
            playMethod = PlayMethod.DIRECT_PLAY,
            repeatMode = RepeatMode.REPEAT_NONE,
            playbackOrder = PlaybackOrder.DEFAULT,
        )
        send("progress") { api.playStateApi.reportPlaybackProgress(info) }
    }

    private fun stop(playing: Playing, finished: Boolean) {
        if (!playing.started) return
        playing.started = false
        // After a song ends the player is already on the next one, so use what was last seen.
        val positionMs = if (finished && playing.durationMs != C.TIME_UNSET) playing.durationMs else playing.positionMs
        val info = PlaybackStopInfo(
            itemId = playing.itemId,
            playSessionId = playing.playSessionId,
            positionTicks = positionMs.milliseconds.inWholeTicks,
            failed = false,
        )
        send("stop") { api.playStateApi.reportPlaybackStopped(info) }
    }

    private fun send(what: String, report: suspend () -> Unit) {
        if (api.accessToken == null) return
        network.launch {
            runCatching { report() }.onFailure { Timber.w(it, "Couldn't report playback $what") }
        }
    }

    private fun MediaItem.toPlaying(): Playing? =
        (runCatching { Json.decodeFromString<LibraryMediaId>(mediaId) }.getOrNull() as? LibraryMediaId.Item)
            ?.let { Playing(it.itemId) }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
