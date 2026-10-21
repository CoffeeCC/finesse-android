package org.jellyfin.mobile.carapp

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Whether video may show: only while the car is stopped, judged from the car's own speed.
 *
 * Android Auto doesn't tell apps the gear, so "parked" here means "stopped for a while": the speed
 * must stay at walking pace or below for [parkedAfterStoppedMs] before video is allowed, and any
 * movement blocks it at once. Playback never restarts by itself after moving (see VideoPlayerScreen).
 *
 * It fails safe: until the car has reported a speed, and whenever it can't, the answer is
 * [State.Unknown], which blocks video like [State.Moving] does.
 */
class ParkedGate(
    private val carContext: CarContext,
    private val onChange: (State) -> Unit,
) {
    enum class State { Parked, Moving, Unknown }

    var state: State = State.Unknown
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var listening = false

    // Below this the car counts as stopped (~2 mph, so GPS-style jitter at a standstill doesn't count).
    private val stoppedBelowMetersPerSecond = 1f

    // Stopped this long before video is allowed. A stopped car may not report its speed again, so a
    // timer re-checks instead of waiting for the next report.
    private val parkedAfterStoppedMs = 10_000L
    private var stoppedSince = 0L
    private val confirmParked = Runnable {
        if (stoppedSince != 0L && System.currentTimeMillis() - stoppedSince >= parkedAfterStoppedMs) set(State.Parked)
    }

    private val listener = OnCarDataAvailableListener<Speed> { speed ->
        val value = speed.rawSpeedMetersPerSecond
        if (value.status != CarValue.STATUS_SUCCESS || value.value == null) {
            set(State.Unknown)
            return@OnCarDataAvailableListener
        }
        val metersPerSecond = value.value!!
        if (metersPerSecond >= stoppedBelowMetersPerSecond) {
            stoppedSince = 0L
            handler.removeCallbacks(confirmParked)
            set(State.Moving)
        } else if (stoppedSince == 0L) {
            stoppedSince = System.currentTimeMillis()
            handler.postDelayed(confirmParked, parkedAfterStoppedMs)
        } else {
            confirmParked.run()
        }
    }

    fun start() {
        if (listening) return
        listening = true
        try {
            val carInfo = carContext.getCarService(CarHardwareManager::class.java).carInfo
            carInfo.addSpeedListener(ContextCompat.getMainExecutor(carContext), listener)
        } catch (e: SecurityException) {
            Timber.w(e, "No permission to read the car's speed")
            set(State.Unknown)
        } catch (e: IllegalStateException) {
            Timber.w(e, "The car's speed isn't available")
            set(State.Unknown)
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        handler.removeCallbacksAndMessages(null)
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(listener)
        }
    }

    private fun set(next: State) {
        if (next == state) return
        state = next
        onChange(next)
    }
}
