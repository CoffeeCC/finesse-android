package com.coffeecc.finesse.video

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * Whether video may show: only while the car is stopped, judged from the car's own speed.
 *
 * Android Auto doesn't tell apps the gear, so "parked" here means "stopped for a while". The
 * rules themselves are in [ParkedRules] (and tested there); this class only connects them to the
 * car's speed readings and a timer. Playback never restarts by itself after moving (see
 * VideoPlayerScreen).
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

    private val rules = ParkedRules()
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false

    // A clock that never jumps: the time of day does when the phone corrects it, which phones do
    // on connecting to a car, and a forward jump would cut the wait short.
    private fun now() = SystemClock.elapsedRealtime()

    // A stopped car may not report its speed again, so a timer re-checks instead of waiting for
    // the next report. It only ever confirms what the latest reading said.
    private val confirmParked = Runnable { publish(rules.check(now())) }

    private val listener = OnCarDataAvailableListener<Speed> { speed ->
        val value = speed.rawSpeedMetersPerSecond
        val metersPerSecond = if (value.status == CarValue.STATUS_SUCCESS) value.value else null
        val next = rules.onReading(metersPerSecond, now())
        // One timer at most, for exactly as long as the rules still need.
        handler.removeCallbacks(confirmParked)
        rules.waitMs(now())?.let { handler.postDelayed(confirmParked, it) }
        publish(next)
    }

    fun start() {
        if (listening) return
        listening = true
        try {
            val carInfo = carContext.getCarService(CarHardwareManager::class.java).carInfo
            carInfo.addSpeedListener(ContextCompat.getMainExecutor(carContext), listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read the car's speed", e)
            publish(rules.unavailable())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "The car's speed isn't available", e)
            publish(rules.unavailable())
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        handler.removeCallbacksAndMessages(null)
        // Not listening any more means not knowing: the next start waits the full time again.
        // (Said quietly: the screen is going away, so there's nobody to tell.)
        rules.unavailable()
        state = State.Unknown
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(listener)
        }
    }

    private companion object {
        const val TAG = "FinesseVideo"
    }

    private fun publish(next: ParkedRules.State) {
        val mapped = when (next) {
            ParkedRules.State.Parked -> State.Parked
            ParkedRules.State.Moving -> State.Moving
            ParkedRules.State.Unknown -> State.Unknown
        }
        if (mapped == state) return
        state = mapped
        onChange(mapped)
    }
}
