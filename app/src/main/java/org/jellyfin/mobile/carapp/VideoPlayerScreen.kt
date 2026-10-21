package org.jellyfin.mobile.carapp

import android.app.Presentation
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID

/**
 * Plays one film or episode on the car screen. The picture is drawn on the surface Android Auto
 * gives navigation apps; [ParkedGate] decides whether it may show: the moment the car moves, the
 * video pauses and the picture is covered. It never restarts by itself: once the car has been
 * stopped long enough, someone has to press Play.
 */
@OptIn(UnstableApi::class)
class VideoPlayerScreen(
    carContext: CarContext,
    private val item: BaseItemDto,
) : Screen(carContext), SurfaceCallback, KoinComponent, DefaultLifecycleObserver {
    private val api: ApiClient by inject()

    private val playSessionId = UUID.randomUUID().toString().replace("-", "")
    private var player: ExoPlayer? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var notice: TextView? = null
    private var playerView: PlayerView? = null
    private var visibleArea: Rect? = null
    private var error: String? = null

    private val gate = ParkedGate(carContext) { state -> onParkedChanged(state) }

    init {
        lifecycle.addObserver(this)
    }

    // ---- Lifecycle ----

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        if (carContext.checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED) {
            gate.start()
        } else {
            // Shows a prompt on the phone (the car is parked when someone opens this).
            carContext.requestPermissions(listOf(CAR_SPEED)) { granted, _ ->
                if (CAR_SPEED in granted) gate.start() else render()
            }
        }
        createPlayer()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        gate.stop()
        reportStopped()
        player?.release()
        player = null
        tearDownSurface()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    // ---- Player ----

    private fun createPlayer() {
        val exo = ExoPlayer.Builder(carContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setMediaItem(
                MediaItem.Builder()
                    .setUri(streamUrl())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
                resumePositionMs(),
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = invalidate()

                override fun onPlayerError(e: PlaybackException) {
                    Timber.e(e, "Video playback failed")
                    error = "Couldn't play this. Check that the phone is online."
                    render()
                    invalidate()
                }
            })
            prepare()
        }
        player = exo
        playerView?.player = exo
    }

    /** Jellyfin, behind Finesse at <address>/jellyfin, makes an HLS stream sized for a car screen. */
    private fun streamUrl(): String {
        val id = item.id.toString().replace("-", "")
        val params = listOf(
            "MediaSourceId" to id,
            "DeviceId" to api.deviceInfo.id,
            "PlaySessionId" to playSessionId,
            "VideoCodec" to "h264",
            "AudioCodec" to "aac",
            // About 1.8 GB an hour: enough for a car screen without eating a phone plan.
            "MaxStreamingBitrate" to "4000000",
            "MaxWidth" to "1280",
            "TranscodingMaxAudioChannels" to "2",
            "SegmentContainer" to "ts",
            "ApiKey" to api.accessToken.orEmpty(),
        ).joinToString("&") { (k, v) -> "$k=${android.net.Uri.encode(v)}" }
        return "${api.baseUrl!!.trimEnd('/')}/Videos/$id/master.m3u8?$params"
    }

    private fun resumePositionMs(): Long =
        (item.userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS

    /** Tell the server where we got to, so Continue watching picks up there (and the transcode stops). */
    private fun reportStopped() {
        val positionMs = player?.currentPosition ?: return
        val info = PlaybackStopInfo(
            itemId = item.id,
            mediaSourceId = item.id.toString().replace("-", ""),
            positionTicks = positionMs * TICKS_PER_MS,
            playSessionId = playSessionId,
            failed = false,
        )
        // Not the screen's scope: this runs as the screen closes, when that scope is already cancelled.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { api.playStateApi.reportPlaybackStopped(info) }
                .onFailure { Timber.w(it, "Couldn't report the stop position") }
        }
    }

    // ---- Parked only ----

    private fun onParkedChanged(state: ParkedGate.State) {
        if (state != ParkedGate.State.Parked) player?.pause()
        render()
        invalidate()
    }

    private val mayShowVideo get() = gate.state == ParkedGate.State.Parked

    // ---- Surface ----

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        tearDownSurface()
        val vd = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "FinesseVideo",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surface,
            0,
        )
        display = vd
        val frame = FrameLayout(carContext).apply { setBackgroundColor(Color.BLACK) }
        val pv = PlayerView(carContext).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            player = this@VideoPlayerScreen.player
        }
        val text = TextView(carContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(pv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root = frame
        playerView = pv
        notice = text
        presentation = Presentation(carContext, vd.display).apply {
            setContentView(frame)
            show()
        }
        applyVisibleArea()
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        applyVisibleArea()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = tearDownSurface()

    /** Keep the picture inside the part of the screen the car's own buttons don't cover. */
    private fun applyVisibleArea() {
        val frame = root ?: return
        val area = visibleArea ?: return
        val w = frame.width.takeIf { it > 0 } ?: (display?.display?.mode?.physicalWidth ?: return)
        val h = frame.height.takeIf { it > 0 } ?: (display?.display?.mode?.physicalHeight ?: return)
        frame.setPadding(area.left, area.top, (w - area.right).coerceAtLeast(0), (h - area.bottom).coerceAtLeast(0))
    }

    private fun tearDownSurface() {
        playerView?.player = null
        presentation?.dismiss()
        display?.release()
        presentation = null
        display = null
        root = null
        playerView = null
        notice = null
    }

    /** The picture, or a message covering it. */
    private fun render() {
        val text = notice ?: return
        val message = error ?: when (gate.state) {
            ParkedGate.State.Parked -> null
            ParkedGate.State.Moving -> "Video plays only while parked.\nOnce you've stopped, press Play."
            ParkedGate.State.Unknown ->
                if (carContext.checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED) {
                    "Checking the car is stopped…\n\nIf this stays, this car doesn't share its speed, so video can't play."
                } else {
                    "Finesse needs to see the car's speed to play video only while parked.\nAllow it on your phone."
                }
        }
        text.text = message.orEmpty()
        text.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    // ---- Car screen buttons ----

    override fun onGetTemplate(): Template {
        val exo = player
        val playing = exo?.isPlaying == true
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(if (playing) "Pause" else "Play")
                    .setOnClickListener {
                        val p = player ?: return@setOnClickListener
                        if (p.isPlaying) {
                            p.pause()
                        } else if (mayShowVideo && error == null) {
                            p.play()
                        }
                        invalidate()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("−10s")
                    .setOnClickListener { player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("+30s")
                    .setOnClickListener { player?.let { it.seekTo(it.currentPosition + 30_000) } }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Exit")
                    .setOnClickListener { screenManager.pop() }
                    .build(),
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .build()
    }

    private companion object {
        const val CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"
        const val TICKS_PER_MS = 10_000L
    }
}
