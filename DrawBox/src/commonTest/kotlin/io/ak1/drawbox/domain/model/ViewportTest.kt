package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {

    private fun assertOffsetClose(expected: Offset, actual: Offset, eps: Float = 0.001f) {
        assertTrue(abs(expected.x - actual.x) < eps, "x ${actual.x} != ${expected.x}")
        assertTrue(abs(expected.y - actual.y) < eps, "y ${actual.y} != ${expected.y}")
    }

    @Test
    fun identityRoundTrip() {
        val vp = Viewport()
        val world = Offset(42f, -17f)
        val screen = vp.worldToScreen(world)
        assertOffsetClose(world, screen) // identity
        assertOffsetClose(world, vp.screenToWorld(screen))
    }

    @Test
    fun roundTripAtArbitraryViewport() {
        val vp = Viewport(offset = Offset(120f, -40f), scale = 1.5f)
        val world = Offset(50f, 80f)
        val screen = vp.worldToScreen(world)
        assertOffsetClose(world, vp.screenToWorld(screen))
    }

    @Test
    fun panShiftsOnlyOffset() {
        val vp = Viewport(offset = Offset(10f, 20f), scale = 2f)
        val out = vp.panBy(Offset(5f, -3f))
        assertEquals(Offset(15f, 17f), out.offset)
        assertEquals(2f, out.scale)
    }

    @Test
    fun zoomByPreservesFocalPoint() {
        // The world point under the focal pixel must remain there after zoom.
        val vp = Viewport(offset = Offset(100f, 50f), scale = 1f)
        val focal = Offset(300f, 200f)
        val focalWorldBefore = vp.screenToWorld(focal)
        val out = vp.zoomBy(2f, focal)
        val focalWorldAfter = out.screenToWorld(focal)
        assertOffsetClose(focalWorldBefore, focalWorldAfter)
    }

    @Test
    fun zoomByRespectsMinMax() {
        val vp = Viewport(scale = 1f)
        assertEquals(Viewport.MAX_SCALE, vp.zoomBy(1000f, Offset.Zero).scale)
        assertEquals(Viewport.MIN_SCALE, vp.zoomBy(0.0001f, Offset.Zero).scale)
    }

    @Test
    fun zoomToSetsAbsoluteScalePreservingFocal() {
        val vp = Viewport(offset = Offset(10f, 10f), scale = 1.3f)
        val focal = Offset(200f, 100f)
        val focalWorldBefore = vp.screenToWorld(focal)
        val out = vp.zoomTo(3f, focal)
        assertEquals(3f, out.scale)
        assertOffsetClose(focalWorldBefore, out.screenToWorld(focal))
    }

    @Test
    fun scalePercentMatchesScale() {
        assertEquals(100, Viewport(scale = 1f).scalePercent)
        assertEquals(250, Viewport(scale = 2.5f).scalePercent)
    }

    @Test
    fun fitCentersContentInViewport() {
        val content = Rect(0f, 0f, 100f, 50f)
        val vp = Viewport.fit(content, Size(400f, 400f))
        assertEquals(4f, vp.scale)
        assertOffsetClose(Offset(200f, 200f), vp.worldToScreen(content.center))
        assertOffsetClose(Offset(0f, 100f), vp.worldToScreen(content.topLeft))
    }

    @Test
    fun fitUsesTighterAxis() {
        val content = Rect(0f, 0f, 400f, 100f)
        val vp = Viewport.fit(content, Size(800f, 800f))
        assertEquals(2f, vp.scale)
        assertOffsetClose(Offset(400f, 400f), vp.worldToScreen(content.center))
    }

    @Test
    fun fitHonorsPadding() {
        val content = Rect(0f, 0f, 100f, 100f)
        val vp = Viewport.fit(content, Size(400f, 400f), padding = 20f)
        assertEquals(3.6f, vp.scale)
        assertOffsetClose(Offset(20f, 20f), vp.worldToScreen(content.topLeft))
        assertOffsetClose(Offset(200f, 200f), vp.worldToScreen(content.center))
    }

    @Test
    fun fitClampsToMaxScale() {
        val vp = Viewport.fit(Rect(0f, 0f, 1f, 1f), Size(400f, 400f))
        assertEquals(Viewport.MAX_SCALE, vp.scale)
    }

    @Test
    fun fitCanCapAtOneHundredPercent() {
        val content = Rect(0f, 0f, 1f, 1f)
        val vp = Viewport.fit(content, Size(400f, 400f), maxScale = 1f)
        assertEquals(1f, vp.scale)
        assertOffsetClose(Offset(200f, 200f), vp.worldToScreen(content.center))
    }

    @Test
    fun fitClampsToMinScaleForHugeContent() {
        val vp = Viewport.fit(Rect(0f, 0f, 100_000f, 100_000f), Size(400f, 400f))
        assertEquals(Viewport.MIN_SCALE, vp.scale)
    }

    @Test
    fun fitHandlesZeroSizedContent() {
        val content = Rect(5f, 5f, 5f, 5f)
        val vp = Viewport.fit(content, Size(400f, 400f))
        assertTrue(vp.scale.isFinite())
        assertOffsetClose(Offset(200f, 200f), vp.worldToScreen(content.center))
    }

    @Test
    fun wheelZoomFactorDirections() {
        assertTrue(Viewport.wheelZoomFactor(-1f) > 1f, "wheel up must zoom in")
        assertTrue(Viewport.wheelZoomFactor(1f) < 1f, "wheel down must zoom out")
        assertEquals(1f, Viewport.wheelZoomFactor(0f))
    }

    @Test
    fun wheelZoomFactorIsContinuous() {
        assertEquals(exp(0.1f), Viewport.wheelZoomFactor(-1f), 1e-5f)
        assertEquals(
            Viewport.wheelZoomFactor(-1f) * Viewport.wheelZoomFactor(-1f),
            Viewport.wheelZoomFactor(-2f),
            1e-4f,
        )
    }

    @Test
    fun wheelZoomFactorClampsExtremeDeltas() {
        val hugeIn = Viewport.wheelZoomFactor(-1000f)
        val hugeOut = Viewport.wheelZoomFactor(1000f)
        assertTrue(hugeIn.isFinite() && hugeIn <= exp(0.35f) + 1e-4f)
        assertTrue(hugeOut.isFinite() && hugeOut >= exp(-0.35f) - 1e-4f)
    }
}
