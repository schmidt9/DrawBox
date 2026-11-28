package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Layer
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.effectiveMode
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportReducerTest {

    private val reducer = Reducer(UseCase())

    private fun rect(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotation: Float = 0f,
        layerId: String? = null,
    ): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(left, top), Offset(right, bottom)),
        strokeColor = Color.Black,
        strokeWidth = 1f,
        rotation = rotation,
        layerId = layerId,
    )

    @Test
    fun panByUpdatesOffsetOnly() {
        val initial = State(viewport = Viewport(offset = Offset(10f, 10f), scale = 1.5f))
        val out = reducer.reduce(initial, Intent.PanBy(Offset(5f, -3f)))
        assertEquals(Offset(15f, 7f), out.viewport.offset)
        assertEquals(1.5f, out.viewport.scale)
    }

    @Test
    fun zoomByDoesNotSnapshotHistory() {
        val initial = State(history = emptyList())
        val out = reducer.reduce(initial, Intent.ZoomBy(2f, Offset(100f, 100f)))
        assertTrue(out.history.isEmpty(), "Viewport changes must not enter undo history")
        assertEquals(2f, out.viewport.scale)
    }

    @Test
    fun resetCameraReturnsIdentity() {
        val initial = State(viewport = Viewport(offset = Offset(1000f, 1000f), scale = 4f))
        val out = reducer.reduce(initial, Intent.ResetCamera)
        assertEquals(Offset.Zero, out.viewport.offset)
        assertEquals(1f, out.viewport.scale)
    }

    @Test
    fun setTempPanFlipsFlagAndEffectiveMode() {
        val initial = State(mode = Mode.PEN)
        assertEquals(Mode.PEN, initial.effectiveMode)
        val held = reducer.reduce(initial, Intent.SetTempPan(true))
        assertTrue(held.tempPanActive)
        assertEquals(Mode.PAN, held.effectiveMode)
        val released = reducer.reduce(held, Intent.SetTempPan(false))
        assertFalse(released.tempPanActive)
        assertEquals(Mode.PEN, released.effectiveMode)
    }

    @Test
    fun insertingAPathLeavesViewportAlone() {
        val initial = State(
            mode = Mode.PEN,
            viewport = Viewport(offset = Offset(50f, 50f), scale = 2f),
        )
        val out = reducer.reduce(initial, Intent.InsertNewPath(Offset(10f, 10f)))
        assertEquals(initial.viewport, out.viewport)
        assertTrue(out.elements.size == 1 && out.elements[0] is Element.Path)
    }

    @Test
    fun fitToScreenCentersVisibleContent() {
        val element = rect("a", 0f, 0f, 100f, 50f)
        val initial = State(
            elements = listOf(element),
            viewport = Viewport(offset = Offset(999f, -999f), scale = 3f),
        )
        val out = reducer.reduce(initial, Intent.FitToScreen(Size(400f, 400f)))
        assertEquals(4f, out.viewport.scale)
        assertEquals(Offset(0f, 100f), out.viewport.offset)
        assertEquals(
            Offset(200f, 200f),
            out.viewport.worldToScreen(element.bounds().center),
        )
        assertTrue(out.history.isEmpty(), "Fit must not enter undo history")
    }

    @Test
    fun fitToScreenIgnoresHiddenLayers() {
        val visible = rect("v", 0f, 0f, 100f, 100f, layerId = "shown")
        val hidden = rect("h", 0f, 0f, 10_000f, 10_000f, layerId = "hidden")
        val initial = State(
            elements = listOf(visible, hidden),
            layers = listOf(Layer(id = "hidden", name = "Hidden", isVisible = false)),
        )
        val out = reducer.reduce(initial, Intent.FitToScreen(Size(400f, 400f)))
        assertEquals(4f, out.viewport.scale)
    }

    @Test
    fun fitToScreenKeepsLockedLayers() {
        // Locked layers are not interactive but still rendered, so they must
        // expand the fitted bounds.
        val visible = rect("v", 0f, 0f, 100f, 100f, layerId = "shown")
        val locked = rect("l", 0f, 0f, 10_000f, 10_000f, layerId = "locked")
        val initial = State(
            elements = listOf(visible, locked),
            layers = listOf(Layer(id = "locked", name = "Locked", isLocked = true)),
        )
        val out = reducer.reduce(initial, Intent.FitToScreen(Size(400f, 400f)))
        assertEquals(Viewport.MIN_SCALE, out.viewport.scale)
    }

    @Test
    fun fitToScreenNoOpOnEmptyCanvas() {
        val initial = State(viewport = Viewport(offset = Offset(123f, 456f), scale = 2f))
        val out = reducer.reduce(initial, Intent.FitToScreen(Size(400f, 400f)))
        assertEquals(initial.viewport, out.viewport)
    }

    @Test
    fun fitToScreenNoOpOnZeroViewportSize() {
        val initial = State(
            elements = listOf(rect("a", 0f, 0f, 100f, 100f)),
            viewport = Viewport(offset = Offset(123f, 456f), scale = 2f),
        )
        val out = reducer.reduce(initial, Intent.FitToScreen(Size.Zero))
        assertEquals(initial.viewport, out.viewport)
    }

    @Test
    fun fitToScreenAccountsForRotation() {
        // 100×100 rotated 45° occupies ≈141.42×141.42, so a 200×200 viewport
        // fits it at ≈1.414× (versus 2× for the unrotated square).
        val rotated = rect("r", 0f, 0f, 100f, 100f, rotation = 45f)
        val out = reducer.reduce(
            State(elements = listOf(rotated)),
            Intent.FitToScreen(Size(200f, 200f)),
        )
        assertTrue(
            abs(out.viewport.scale - 1.41421f) < 0.01f,
            "scale ${out.viewport.scale} should reflect the rotated AABB",
        )
    }
}
