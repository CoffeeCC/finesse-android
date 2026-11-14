package com.coffeecc.finesse.video

import com.coffeecc.finesse.video.ParkedRules.State.Moving
import com.coffeecc.finesse.video.ParkedRules.State.Parked
import com.coffeecc.finesse.video.ParkedRules.State.Unknown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Video must never show while the car moves. Every sequence here is one that went wrong, or
 * could, in the first version of the gate.
 */
class ParkedRulesTest {
    private val rules = ParkedRules()
    private val s = 1000L

    @Test
    fun `nothing is known until the car has said something`() {
        assertEquals(Unknown, rules.state)
        assertEquals(Unknown, rules.check(60 * s))
        assertNull(rules.waitMs(0))
    }

    @Test
    fun `stopped for ten unbroken seconds is parked, and not a moment sooner`() {
        assertEquals(Unknown, rules.onReading(0f, 0))
        assertEquals(10 * s, rules.waitMs(0))
        assertEquals(Unknown, rules.onReading(0f, 9 * s + 999))
        assertEquals(Unknown, rules.check(9 * s + 999))
        assertEquals(Parked, rules.check(10 * s))
        assertNull(rules.waitMs(10 * s))
    }

    @Test
    fun `a later stopped reading confirms parked without the timer`() {
        rules.onReading(0.2f, 0)
        assertEquals(Parked, rules.onReading(0.1f, 10 * s))
    }

    @Test
    fun `moving forwards is moving at once, and the wait starts over`() {
        rules.onReading(0f, 0)
        rules.check(10 * s)
        assertEquals(Moving, rules.onReading(5f, 11 * s))
        assertEquals(Moving, rules.onReading(0f, 12 * s))
        assertEquals(Moving, rules.check(21 * s))
        assertEquals(Parked, rules.check(22 * s))
    }

    @Test
    fun `reversing is moving - the car reports it as a negative speed`() {
        assertEquals(Moving, rules.onReading(-3f, 0))
        assertEquals(Moving, rules.onReading(-1f, 5 * s))
        // Ten seconds of reversing never becomes parked.
        assertEquals(Moving, rules.onReading(-2.5f, 20 * s))
        assertEquals(Moving, rules.check(40 * s))
        // And reversing ends a parked spell at once.
        rules.onReading(0f, 50 * s)
        assertEquals(Parked, rules.check(60 * s))
        assertEquals(Moving, rules.onReading(-1.2f, 61 * s))
    }

    @Test
    fun `a reading that is not a number is unknown, never stopped`() {
        assertEquals(Unknown, rules.onReading(Float.NaN, 0))
        assertEquals(Unknown, rules.check(30 * s))
        assertEquals(Unknown, rules.onReading(Float.POSITIVE_INFINITY, 31 * s))
        assertEquals(Unknown, rules.onReading(Float.NEGATIVE_INFINITY, 32 * s))
        assertEquals(Unknown, rules.check(60 * s))
    }

    @Test
    fun `the car saying it cannot tell cancels the wait`() {
        // Stopped at 0 s, "can't say" at 2 s: the timer set for 10 s must not say Parked.
        rules.onReading(0f, 0)
        assertEquals(Unknown, rules.onReading(null, 2 * s))
        assertNull(rules.waitMs(2 * s))
        assertEquals(Unknown, rules.check(10 * s))
        assertEquals(Unknown, rules.check(60 * s))
    }

    @Test
    fun `one zero after a gap is not enough - the full wait runs again`() {
        rules.onReading(0f, 0)
        rules.check(10 * s)
        assertEquals(Unknown, rules.onReading(null, 20 * s))
        // The car may have moved during the gap.
        assertEquals(Unknown, rules.onReading(0f, 300 * s))
        assertEquals(10 * s, rules.waitMs(300 * s))
        assertEquals(Unknown, rules.check(309 * s))
        assertEquals(Parked, rules.check(310 * s))
    }

    @Test
    fun `not knowing ends a parked spell at once`() {
        rules.onReading(0f, 0)
        assertEquals(Parked, rules.check(10 * s))
        assertEquals(Unknown, rules.onReading(null, 11 * s))
        assertEquals(Unknown, rules.unavailable())
    }

    @Test
    fun `creeping at walking pace or faster is moving`() {
        assertEquals(Moving, rules.onReading(1f, 0))
        assertEquals(Moving, rules.onReading(-1f, 1 * s))
        // Just under the limit counts as stopped (jitter at a standstill).
        rules.onReading(0.99f, 2 * s)
        assertEquals(Parked, rules.check(12 * s))
    }

    @Test
    fun `a clock that went backwards restarts the wait instead of cutting it short`() {
        rules.onReading(0f, 100 * s)
        assertEquals(Unknown, rules.onReading(0f, 50 * s))
        assertEquals(Unknown, rules.check(59 * s))
        assertEquals(Parked, rules.check(60 * s))
    }
}
