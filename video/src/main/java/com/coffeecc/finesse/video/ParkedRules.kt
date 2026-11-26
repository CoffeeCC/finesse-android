package com.coffeecc.finesse.video

import kotlin.math.abs

/**
 * The rules for "may video show?", with nothing of Android in them so they can be tested alone
 * (ParkedRulesTest). Readings from the car and a clock go in; a state comes out.
 *
 * - Any movement, **forwards or backwards**, is Moving at once. (The car reports reversing as a
 *   negative speed.)
 * - A reading the car can't give, or one that isn't a real number, is Unknown at once, and the
 *   wait for Parked starts over.
 * - Parked needs [parkedAfterStoppedMs] of unbroken good readings below [stoppedBelowMetersPerSecond].
 *   One good reading after a gap is never enough.
 *
 * Unknown and Moving both block video. The clock must be one that never jumps
 * (SystemClock.elapsedRealtime), not the time of day.
 */
class ParkedRules(
    // Below this the car counts as stopped (~2 mph, so jitter at a standstill doesn't count).
    private val stoppedBelowMetersPerSecond: Float = 1f,
    // Stopped this long before video is allowed.
    val parkedAfterStoppedMs: Long = 10_000L,
) {
    enum class State { Parked, Moving, Unknown }

    var state: State = State.Unknown
        private set

    /** When the current unbroken run of "stopped" readings began, or null when there isn't one. */
    private var stoppedSince: Long? = null

    /**
     * A reading from the car. [metersPerSecond] is null when the car says it can't give its speed.
     * Returns the state after it.
     */
    fun onReading(metersPerSecond: Float?, nowMs: Long): State {
        if (metersPerSecond == null || metersPerSecond.isNaN() || metersPerSecond.isInfinite()) {
            stoppedSince = null
            state = State.Unknown
            return state
        }
        if (abs(metersPerSecond) >= stoppedBelowMetersPerSecond) {
            stoppedSince = null
            state = State.Moving
            return state
        }
        val since = stoppedSince
        if (since == null || nowMs < since) {
            // The first stopped reading of a run: the wait starts here. Until it's over the state
            // stays what it was (Moving or Unknown), which blocks video.
            stoppedSince = nowMs
            return state
        }
        return check(nowMs)
    }

    /**
     * Called by a timer, because a stopped car may not report its speed again: says Parked only
     * if the latest thing the car said was "stopped", and it has been for long enough.
     */
    fun check(nowMs: Long): State {
        val since = stoppedSince
        if (since != null && nowMs - since >= parkedAfterStoppedMs) state = State.Parked
        return state
    }

    /** How long until [check] could say Parked, or null when there's nothing to wait for. */
    fun waitMs(nowMs: Long): Long? {
        val since = stoppedSince ?: return null
        if (state == State.Parked) return null
        return (parkedAfterStoppedMs - (nowMs - since)).coerceAtLeast(0L)
    }

    /** The car's speed can't be read at all (no permission, no sensor). */
    fun unavailable(): State {
        stoppedSince = null
        state = State.Unknown
        return state
    }
}
