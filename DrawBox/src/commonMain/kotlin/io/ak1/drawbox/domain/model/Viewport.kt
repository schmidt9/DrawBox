package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.exp
import kotlin.math.min

/**
 * Camera transform for the infinite canvas. Elements are stored in **world**
 * coordinates; the viewport projects them onto the **screen** using a translate
 * + uniform-scale transform.
 *
 * Screen-pixel point `s` and world point `w` relate as:
 *   `s = w * scale + offset`
 *   `w = (s - offset) / scale`
 *
 * Camera state is session state — not serialized, not in undo history. Mutate
 * by returning a new [Viewport] via [panBy], [zoomBy], or [zoomTo].
 */
data class Viewport(
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
) {
    fun screenToWorld(screenPos: Offset): Offset = (screenPos - offset) / scale

    fun worldToScreen(worldPos: Offset): Offset = worldPos * scale + offset

    /** Translate the camera by [delta] screen pixels. */
    fun panBy(delta: Offset): Viewport = copy(offset = offset + delta)

    /**
     * Zoom by [factor] keeping the world point under [focalScreen] anchored to
     * the same screen pixel. Same math as compose-infinite-canvas, just lifted
     * onto an immutable value type.
     */
    fun zoomBy(factor: Float, focalScreen: Offset): Viewport {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == scale) return this
        val actual = newScale / scale
        return Viewport(
            offset = focalScreen - (focalScreen - offset) * actual,
            scale = newScale,
        )
    }

    /** Set scale to [targetScale] anchored at [focalScreen]. */
    fun zoomTo(targetScale: Float, focalScreen: Offset): Viewport {
        val clamped = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (clamped == scale) return this
        val factor = clamped / scale
        return Viewport(
            offset = focalScreen - (focalScreen - offset) * factor,
            scale = clamped,
        )
    }

    /** Integer percent suitable for UI display. */
    val scalePercent: Int get() = (scale * 100).toInt()

    companion object {
        const val MIN_SCALE: Float = 0.1f
        const val MAX_SCALE: Float = 8f

        /**
         * Viewport that centers [content] inside [viewportSize] screen pixels,
         * leaving [padding] px of margin on every side. May zoom in on small
         * content; pass `maxScale = 1f` to never exceed 100%.
         */
        fun fit(
            content: Rect,
            viewportSize: Size,
            padding: Float = 0f,
            maxScale: Float = MAX_SCALE,
        ): Viewport {
            val availableWidth = (viewportSize.width - 2f * padding).coerceAtLeast(1f)
            val availableHeight = (viewportSize.height - 2f * padding).coerceAtLeast(1f)
            // Zero-extent content (straight lines, a single dot) must not divide by 0.
            val contentWidth = content.width.coerceAtLeast(1f)
            val contentHeight = content.height.coerceAtLeast(1f)
            val cap = maxScale.coerceIn(MIN_SCALE, MAX_SCALE)
            val scale = min(availableWidth / contentWidth, availableHeight / contentHeight)
                .coerceIn(MIN_SCALE, cap)
            val center = content.center
            return Viewport(
                offset = Offset(
                    viewportSize.width * 0.5f - center.x * scale,
                    viewportSize.height * 0.5f - center.y * scale,
                ),
                scale = scale,
            )
        }

        /**
         * Smooth multiplicative zoom for one wheel/trackpad event. Wheel up
         * (negative delta) zooms in. Continuous in [scrollDelta] so fractional
         * trackpad deltas produce proportional steps; per-event magnitude is
         * clamped so pixel-unit scroll deltas cannot jump the camera.
         */
        fun wheelZoomFactor(scrollDelta: Float): Float = exp(
            (-scrollDelta * WHEEL_ZOOM_PER_UNIT).coerceIn(-MAX_WHEEL_STEP, MAX_WHEEL_STEP),
        )

        /** ≈1.105× per wheel notch (±1 unit). */
        private const val WHEEL_ZOOM_PER_UNIT = 0.1f
        private const val MAX_WHEEL_STEP = 0.35f
    }
}
