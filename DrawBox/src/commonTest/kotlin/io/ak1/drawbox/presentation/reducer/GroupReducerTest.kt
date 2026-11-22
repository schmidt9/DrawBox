package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.rotateAround
import io.ak1.drawbox.domain.model.visualAabb
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupReducerTest {

    private fun rect(
        id: String,
        x: Float,
        y: Float,
        z: Int = 0,
    ): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(x, y), Offset(x + 100f, y + 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
    )

    private fun controller(vararg elements: Element): DrawBoxController {
        val ordered = elements.mapIndexed { index, element ->
            element.withZ(index)
        }
        return DrawBoxController(
            Reducer(UseCase()),
            State(elements = ordered, mode = Mode.SELECT),
        )
    }

    private fun Element.withZ(index: Int): Element = when (this) {
        is Element.Shape -> copy(zIndex = index)
        is Element.Path -> copy(zIndex = index)
        is Element.Text -> copy(zIndex = index)
        is Element.Image -> copy(zIndex = index)
        is Element.Group -> copy(zIndex = index)
    }

    private fun selectAll(controller: DrawBoxController) {
        controller.onIntent(
            Intent.CommitMarquee(Rect(-10f, -10f, 1000f, 1000f)),
        )
    }

    @Test
    fun groupSelectedCreatesOneGroupAndIsUndoable() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()

        val state = controller.state.value
        val group = state.elements.single()
        assertIs<Element.Group>(group)
        assertEquals(listOf("a", "b"), group.children.map { it.id })
        assertEquals(setOf(group.id), state.selectedIds)
        assertTrue(controller.canUndo.value)

        controller.undo()
        assertEquals(listOf("a", "b"), controller.state.value.elements.map { it.id })
    }

    @Test
    fun groupRequiresTwoSelections() {
        val controller = controller(rect("a", 0f, 0f))
        controller.onIntent(Intent.SelectAt(Offset(5f, 50f)))
        controller.groupSelection()
        assertEquals(1, controller.state.value.elements.size)
        assertIs<Element.Shape>(controller.state.value.elements.single())
    }

    @Test
    fun ungroupRestoresChildrenAndSelectsThem() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        controller.ungroupSelection()

        val state = controller.state.value
        assertEquals(listOf("a", "b"), state.elements.map { it.id })
        assertEquals(setOf("a", "b"), state.selectedIds)
        assertTrue(state.groupPath.isEmpty())

        controller.undo()
        val restored = controller.state.value.elements.single()
        assertIs<Element.Group>(restored)
    }

    @Test
    fun clickOnGroupChildSelectsOutermostGroup() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        val groupId = controller.state.value.selectedIds.single()

        controller.clearSelection()
        controller.onIntent(Intent.SelectAt(Offset(5f, 50f)))

        assertEquals(setOf(groupId), controller.state.value.selectedIds)
        assertTrue(controller.state.value.groupPath.isEmpty())
    }

    @Test
    fun doubleTapEntersGroupAndSelectsClickedChild() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        val groupId = controller.state.value.selectedIds.single()

        controller.onIntent(Intent.DoubleTapAt(Offset(5f, 50f)))

        val state = controller.state.value
        assertEquals(listOf(groupId), state.groupPath)
        assertEquals(setOf("a"), state.selectedIds)

        controller.exitGroup()
        assertEquals(emptyList(), controller.state.value.groupPath)
        assertEquals(setOf(groupId), controller.state.value.selectedIds)

        controller.onIntent(Intent.DoubleTapAt(Offset(5f, 50f)))
        assertEquals(listOf(groupId), controller.state.value.groupPath)
    }

    @Test
    fun emptyTapLeavesAllGroups() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        controller.onIntent(Intent.DoubleTapAt(Offset(5f, 50f)))
        assertEquals(1, controller.state.value.groupPath.size)

        controller.onIntent(Intent.SelectAt(Offset(900f, 900f)))

        assertTrue(controller.state.value.groupPath.isEmpty())
        assertTrue(controller.state.value.selectedIds.isEmpty())
    }

    @Test
    fun nestedGroupsAndScopedSelection() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        val innerId = controller.state.value.selectedIds.single()

        controller.onIntent(Intent.AddElement(rect("c", 400f, 0f)))
        controller.onIntent(Intent.CommitMarquee(Rect(-10f, -10f, 1000f, 1000f)))
        controller.groupSelection()

        val outer = controller.state.value.elements.single() as Element.Group
        assertEquals(setOf(innerId, "c"), outer.children.map { it.id }.toSet())

        controller.onIntent(Intent.DoubleTapAt(Offset(5f, 50f)))
        assertEquals(listOf(outer.id), controller.state.value.groupPath)
        assertEquals(setOf(innerId), controller.state.value.selectedIds)

        controller.onIntent(Intent.DoubleTapAt(Offset(5f, 50f)))
        assertEquals(listOf(outer.id, innerId), controller.state.value.groupPath)
        assertEquals(setOf("a"), controller.state.value.selectedIds)
    }

    @Test
    fun ungroupRotatedGroupPreservesVisualPositions() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        val groupId = controller.state.value.selectedIds.single()
        val before = controller.state.value.elements.single() as Element.Group
        val pivot = before.bounds().center
        controller.onIntent(Intent.SetElementRotation(groupId, 25f))

        controller.ungroupSelection()

        val children = controller.state.value.elements
        assertEquals(listOf("a", "b"), children.map { it.id })
        before.children.forEach { original ->
            val rotated = original.visualAabb().let { b ->
                val corners = listOf(
                    b.topLeft,
                    Offset(b.right, b.top),
                    b.bottomRight,
                    Offset(b.left, b.bottom),
                ).map { rotateAround(it, pivot, 25f) }
                Rect(
                    corners.minOf { it.x },
                    corners.minOf { it.y },
                    corners.maxOf { it.x },
                    corners.maxOf { it.y },
                )
            }
            val actual = children.first { it.id == original.id }.visualAabb()
            assertTrue(
                abs(rotated.left - actual.left) < 0.1f &&
                    abs(rotated.top - actual.top) < 0.1f &&
                    abs(rotated.right - actual.right) < 0.1f &&
                    abs(rotated.bottom - actual.bottom) < 0.1f,
                "child ${original.id}: expected $rotated but was $actual",
            )
        }
    }

    @Test
    fun groupRotationPersistsAcrossExitEnterAndUndoRedo() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        val groupId = controller.state.value.selectedIds.single()

        controller.onIntent(Intent.SetElementRotation(groupId, 30f))
        assertEquals(30f, (controller.state.value.elements.single() as Element.Group).rotation)

        // Enter through a point on the rotated child.
        val groupCenter = (controller.state.value.elements.single() as Element.Group).bounds().center
        controller.onIntent(
            Intent.DoubleTapAt(rotateAround(Offset(5f, 50f), groupCenter, 30f)),
        )
        controller.exitGroup()

        val group = controller.state.value.elements.single() as Element.Group
        assertEquals(30f, group.rotation)
    }

    @Test
    fun movingNestedChildUsesParentFrame() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        val groupId = controller.state.value.selectedIds.single()
        controller.onIntent(Intent.SetElementRotation(groupId, 90f))
        val groupCenter = (controller.state.value.elements.single() as Element.Group).bounds().center
        controller.onIntent(
            Intent.DoubleTapAt(rotateAround(Offset(5f, 50f), groupCenter, 90f)),
        )
        assertEquals(setOf("a"), controller.state.value.selectedIds)

        controller.onIntent(Intent.BeginTransform)
        controller.onIntent(Intent.MoveSelected(Offset(0f, 10f)))

        val moved = controller.state.value.elements.single() as Element.Group
        val child = moved.children.first { it.id == "a" } as Element.Shape
        // World +Y movement inside a 90°-rotated group maps to local +X.
        assertTrue(abs(child.points[0].x - 10f) < 0.01f, "x=${child.points[0].x}")
        assertTrue(abs(child.points[0].y - 0f) < 0.01f, "y=${child.points[0].y}")
    }

    @Test
    fun styleOnSelectedGroupRecursesIntoMembers() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        controller.setSelectionColor(Color.Red)

        val group = controller.state.value.elements.single() as Element.Group
        assertTrue(group.children.all { (it as Element.Shape).strokeColor == Color.Red })
        // One snapshot for the group style change.
        controller.undo()
        val restored = controller.state.value.elements.single() as Element.Group
        assertTrue(restored.children.all { (it as Element.Shape).strokeColor == Color.Black })
    }

    @Test
    fun deleteSelectedGroupRemovesSubtree() {
        val controller = controller(rect("a", 0f, 0f), rect("b", 200f, 0f))
        selectAll(controller)
        controller.groupSelection()
        controller.deleteSelected()
        assertTrue(controller.state.value.elements.isEmpty())
        assertTrue(controller.state.value.selectedIds.isEmpty())
        controller.undo()
        assertEquals(1, controller.state.value.elements.size)
    }

    @Test
    fun nestedGroupsSerializeRoundTrip() {
        val inner = Element.Group(
            id = "inner",
            children = listOf(rect("a", 0f, 0f)),
            rotation = 12f,
        )
        val outer = Element.Group(
            id = "outer",
            children = listOf(inner, rect("b", 300f, 0f)),
            rotation = 40f,
        )
        val json = DrawingSerializer.serialize(
            PayLoad(bgColor = Color.White, elements = listOf(outer)),
        )
        val loaded = DrawingSerializer.deserialize(json).elements.single() as Element.Group
        assertEquals("outer", loaded.id)
        assertEquals(40f, loaded.rotation)
        val loadedInner = loaded.children.first() as Element.Group
        assertEquals("inner", loadedInner.id)
        assertEquals(12f, loadedInner.rotation)
        assertEquals(setOf("a", "b"), loaded.flattenIds())
    }

    private fun Element.flattenIds(): Set<String> = when (this) {
        is Element.Group -> children.flatMap { it.flattenIds() }.toSet()
        else -> setOf(id)
    }
}
