package io.ak1.drawbox.input

import kotlin.math.abs

/**
 * Divisor applied to coarse Shift+wheel horizontal deltas, or 1 when the
 * platform reports both axes in the same units.
 *
 * macOS remaps Shift+wheel onto the x axis in coarse line units while ordinary
 * vertical wheel deltas arrive in 0.1 fixed-point units, so the same physical
 * notch is reported ~10x larger horizontally. Trackpads report fractional
 * precise deltas on both axes and are unaffected.
 */
internal expect fun shiftWheelDeltaScale(): Float

internal fun normalizeHorizontalWheelDelta(delta: Float, scale: Float): Float =
    if (scale > 1f && abs(delta) >= 1f) delta / scale else delta

/**
 * Normalizes a Shift+wheel horizontal delta so panning matches vertical wheel
 * speed. Fractional (precise trackpad) deltas pass through untouched.
 */
internal fun normalizeHorizontalWheelDelta(delta: Float): Float =
    normalizeHorizontalWheelDelta(delta, shiftWheelDeltaScale())
