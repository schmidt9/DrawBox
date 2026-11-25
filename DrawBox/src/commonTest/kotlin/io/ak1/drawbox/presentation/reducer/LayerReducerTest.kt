package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Layer
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.StateSnapshot
import io.ak1.drawbox.domain.model.hittableElements
import io.ak1.drawbox.domain.model.visibleElements
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayerReducerTest {

    private val reducer = Reducer(UseCase())

    private fun rect(
        id: String,
        x: Float = 0f,
        z: Int = 0,
        layerId: String? = null,
    ): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(x, 0f), Offset(x + 100f, 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
        layerId = layerId,
    )

    private fun layer(
        id: String,
        name: String,
        visible: Boolean = true,
        locked: Boolean = false,
    ) = Layer(id = id, name = name, isVisible = visible, isLocked = locked)

    @Test
    fun addLayerAppendsInCreationOrderAndSnapshots() {
        val out = reducer.reduce(State(), Intent.AddLayer("l1", "Layer 1"))
        val out2 = reducer.reduce(out, Intent.AddLayer("l2", "Layer 2"))
        assertEquals(listOf("l1", "l2"), out2.layers.map { it.id })
        assertEquals(2, out2.history.size)
    }

    @Test
    fun addLayerRejectsDuplicateNamesCaseInsensitively() {
        val state = State(layers = listOf(layer("l1", "Layer 1")))
        val out = reducer.reduce(state, Intent.AddLayer("l2", " layer 1 "))
        assertEquals(state, out)
    }

    @Test
    fun renameAllowsCaseOnlyChangeButRejectsAnotherLayersName() {
        val state = State(
            layers = listOf(layer("l1", "Layer 1"), layer("l2", "Layer 2")),
        )
        val caseOnly = reducer.reduce(state, Intent.RenameLayer("l1", "LAYER 1"))
        assertEquals("LAYER 1", caseOnly.layers.first { it.id == "l1" }.name)

        val collision = reducer.reduce(state, Intent.RenameLayer("l1", "layer 2"))
        assertEquals(state, collision)
    }

    @Test
    fun setElementsLayerAssignsTopLevelAncestorForNestedSelection() {
        val child = rect("child", layerId = null)
        val group = Element.Group(
            id = "group",
            children = listOf(child),
            layerId = null,
        )
        val state = State(
            elements = listOf(group),
            layers = listOf(layer("l1", "Layer 1")),
        )
        val out = reducer.reduce(
            state,
            Intent.SetElementsLayer(setOf("child"), "l1"),
        )
        val updated = out.elements.single() as Element.Group
        assertEquals("l1", updated.layerId)
        // Only top-level membership is recorded.
        assertTrue(updated.children.none { it.layerId != null })
    }

    @Test
    fun setElementsLayerNullDetachesWithoutChangingZOrder() {
        val shape = rect("s", z = 7, layerId = "l1")
        val state = State(
            elements = listOf(shape),
            layers = listOf(layer("l1", "Layer 1")),
        )
        val out = reducer.reduce(state, Intent.SetElementsLayer(setOf("s"), null))
        val updated = out.elements.single() as Element.Shape
        assertEquals(null, updated.layerId)
        assertEquals(7, updated.zIndex)
    }

    @Test
    fun hidingLayerClearsSelectionAndExcludesFromHitTesting() {
        val shape = rect("s", layerId = "l1")
        val state = State(
            elements = listOf(shape),
            layers = listOf(layer("l1", "Layer 1")),
            mode = Mode.SELECT,
            selectedIds = setOf("s"),
        )
        val out = reducer.reduce(state, Intent.SetLayerVisible("l1", false))
        assertTrue(out.selectedIds.isEmpty())
        assertTrue(out.visibleElements().isEmpty())
        assertTrue(out.hittableElements().isEmpty())

        val hit = reducer.reduce(out, Intent.SelectAt(Offset(50f, 50f)))
        assertTrue(hit.selectedIds.isEmpty())
    }

    @Test
    fun lockedLayerShapesStayVisibleButAreNotHittable() {
        val shape = rect("s", layerId = "l1")
        val state = State(
            elements = listOf(shape),
            layers = listOf(layer("l1", "Layer 1")),
            mode = Mode.SELECT,
            selectedIds = setOf("s"),
        )
        val out = reducer.reduce(state, Intent.SetLayerLocked("l1", true))
        assertEquals(1, out.visibleElements().size)
        assertTrue(out.hittableElements().isEmpty())
        assertTrue(out.selectedIds.isEmpty())

        val hit = reducer.reduce(out, Intent.SelectAt(Offset(50f, 50f)))
        assertTrue(hit.selectedIds.isEmpty())
    }

    @Test
    fun marqueeSkipsHiddenLayerShapes() {
        val hidden = rect("hidden", layerId = "l1")
        val visible = rect("visible", x = 200f)
        val state = State(
            elements = listOf(hidden, visible),
            layers = listOf(layer("l1", "Layer 1", visible = false)),
            mode = Mode.SELECT,
        )
        val out = reducer.reduce(state, Intent.CommitMarquee(Rect(0f, 0f, 400f, 400f)))
        assertEquals(setOf("visible"), out.selectedIds)
    }

    @Test
    fun eraserLeavesLockedLayerShapesInPlace() {
        val shape = rect("s", layerId = "l1")
        val state = State(
            elements = listOf(shape),
            layers = listOf(layer("l1", "Layer 1", locked = true)),
            mode = Mode.ERASER,
        )
        val out = reducer.reduce(state, Intent.EraseAt(Offset(50f, 50f), 10f))
        assertEquals(listOf("s"), out.elements.map { it.id })
        assertTrue(out.history.isEmpty())
    }

    @Test
    fun deleteLayerRemovesAttachedShapesAndUndoRestoresBoth() {
        val attached = rect("attached", layerId = "l1")
        val free = rect("free", x = 200f)
        val state = State(
            elements = listOf(attached, free),
            layers = listOf(layer("l1", "Layer 1")),
            mode = Mode.SELECT,
            selectedIds = setOf("attached"),
        )
        val deleted = reducer.reduce(state, Intent.DeleteLayer("l1"))
        assertTrue(deleted.layers.isEmpty())
        assertEquals(listOf("free"), deleted.elements.map { it.id })
        assertTrue(deleted.selectedIds.isEmpty())

        val undone = reducer.reduce(deleted, Intent.Undo)
        assertEquals(listOf("l1"), undone.layers.map { it.id })
        assertEquals(listOf("attached", "free"), undone.elements.map { it.id })
    }

    @Test
    fun undoRestoresLayerVisibilityState() {
        val shape = rect("s", layerId = "l1")
        val state = State(
            elements = listOf(shape),
            layers = listOf(layer("l1", "Layer 1")),
        )
        val hidden = reducer.reduce(state, Intent.SetLayerVisible("l1", false))
        assertTrue(hidden.visibleElements().isEmpty())

        val undone = reducer.reduce(hidden, Intent.Undo)
        assertTrue(undone.layers.single().isVisible)
        assertEquals(1, undone.visibleElements().size)
    }

    @Test
    fun undoOfLayerCreationRemovesLayer() {
        val created = reducer.reduce(State(), Intent.AddLayer("l1", "Layer 1"))
        val undone = reducer.reduce(created, Intent.Undo)
        assertTrue(undone.layers.isEmpty())
    }

    @Test
    fun layersDoNotEnterSnapshotThroughNonLayerIntents() {
        val state = State(
            history = listOf(StateSnapshot()),
            elements = listOf(rect("s")),
            selectedIds = setOf("s"),
        )
        val changed = reducer.reduce(state, Intent.SetSelectedStrokeColor(Color.Red))
        assertEquals(2, changed.history.size)
        assertIs<StateSnapshot>(changed.history.last())
    }
}
