package org.jellyfin.mobile.sessionbrowser

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject

class LibraryService : MediaLibraryService() {
    private val apiClientController: ApiClientController by inject()
    private val apiClient: ApiClient by inject()

    private val playerAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(playerAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
    }

    private val mediaLibrarySession: MediaLibrarySession by lazy {
        MediaLibrarySession.Builder(this, exoPlayer, callback)
            .build()
    }

    private val resumption: PlaybackResumptionStore by lazy { PlaybackResumptionStore(this) }

    private val callback: MediaLibrarySession.Callback by lazy {
        SessionBrowserCallback(this, apiClient, resumption)
    }

    // Remember what's playing whenever it changes, so it can be resumed later (see PlaybackResumptionStore).
    private val rememberPlayback = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = resumption.save(exoPlayer)

        override fun onIsPlayingChanged(isPlaying: Boolean) = resumption.save(exoPlayer)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) resumption.save(exoPlayer)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibrarySession = mediaLibrarySession

    override fun onCreate() {
        super.onCreate()

        runBlocking { apiClientController.loadSavedServerUser() }
        exoPlayer.addListener(rememberPlayback)
    }

    override fun onDestroy() {
        resumption.save(exoPlayer)
        exoPlayer.removeListener(rememberPlayback)
        exoPlayer.release()
        mediaLibrarySession.release()

        super.onDestroy()
    }
}
