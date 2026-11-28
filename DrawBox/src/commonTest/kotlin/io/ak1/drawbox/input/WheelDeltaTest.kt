package io.ak1.drawbox.input

import kotlin.test.Test
import kotlin.test.assertEquals

class WheelDeltaTest {

    @Test
    fun coarseMacDeltasAreNormalized() {
        assertEquals(0.1f, normalizeHorizontalWheelDelta(1f, 10f), 1e-6f)
        assertEquals(0.5f, normalizeHorizontalWheelDelta(5f, 10f), 1e-6f)
        assertEquals(-0.2f, normalizeHorizontalWheelDelta(-2f, 10f), 1e-6f)
    }

    @Test
    fun preciseTrackpadDeltasPassThrough() {
        assertEquals(0.1f, normalizeHorizontalWheelDelta(0.1f, 10f), 1e-6f)
        assertEquals(-0.4f, normalizeHorizontalWheelDelta(-0.4f, 10f), 1e-6f)
    }

    @Test
    fun platformsWithMatchingUnitsPassThrough() {
        assertEquals(1f, normalizeHorizontalWheelDelta(1f, 1f), 1e-6f)
        assertEquals(5f, normalizeHorizontalWheelDelta(5f, 1f), 1e-6f)
    }

    @Test
    fun zeroStaysZero() {
        assertEquals(0f, normalizeHorizontalWheelDelta(0f, 10f), 1e-6f)
    }
}
