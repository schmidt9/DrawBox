package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DuplicateReducerTest {

    private fun rect(id: String, x: Float, y: Float, z: Int = 0): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(x, y), Offset(x + 100f, y + 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
    )

    private fun arrow(
        id: String,
        startBinding: String?,
        endBinding: String?,
        z: Int = 0,
    ): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.ARROW,
        points = listOf(Offset(50f, 50f), Offset(250f, 50f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
        startBinding = startBinding,
        endBinding = endBinding,
    )

    private fun controller(
        elements: List<Element>,
        selectedIds: Set<String> = emptySet(),
        groupPath: List<String> = emptyList(),
    ): DrawBoxController {
        val ordered = elements.mapIndexed { index, element -> element.withZ(index) }
        return DrawBoxController(
            Reducer(UseCase()),
            State(
                elements = ordered,
                mode = Mode.SELECT,
                selectedIds = selectedIds,
                groupPath = groupPath,
            ),
        )
    }

    private fun Element.withZ(index: Int): Element = when (this) {
        is Element.Shape -> copy(zIndex = index)
        is Element.Path -> copy(zIndex = index)
        is Element.Text -> copy(zIndex = index)
        is Element.Image -> copy(zIndex = index)
        is Element.Group -> copy(zIndex = index)
    }

    @Test
    fun duplicateSingleLeafAddsOffsetCopyAndSelectsIt() {
        val controller = controller(listOf(rect("a", 0f, 0f)), selectedIds = setOf("a"))

        controller.duplicateSelection()

        val state = controller.state.value
        assertEquals(2, state.elements.size)
        assertEquals(1, state.selectedIds.size)
        val copy = assertIs<Element.Shape>(
            state.elements.first { it.id != "a" },
        )
        assertEquals(state.selectedIds.single(), copy.id)
        assertNotEquals("a", copy.id)
        assertEquals(Offset(16f, 16f), copy.points[0])
        assertEquals(Offset(116f, 116f), copy.points.last())
        assertTrue(copy.zIndex > 0)
        assertEquals(1, state.history.size)

        controller.undo()
        assertEquals(listOf("a"), controller.state.value.elements.map { it.id })
    }

    @Test
    fun duplicateMultipleSelectsAllCopiesAndKeepsRelativeZOrder() {
        val controller = controller(
            listOf(rect("a", 0f, 0f), rect("b", 200f, 0f)),
            selectedIds = setOf("a", "b"),
        )

        controller.duplicateSelection()

        val state = controller.state.value
        assertEquals(4, state.elements.size)
        assertEquals(2, state.selectedIds.size)
        val copies = state.elements.filter { it.id !in setOf("a", "b") }
            .sortedBy { it.zIndex }
        assertEquals(2, copies.size)
        assertEquals(setOf(copies[0].id, copies[1].id), state.selectedIds)
        assertTrue(copies[0].zIndex > 1 && copies[1].zIndex > copies[0].zIndex)
        assertEquals(Offset(16f, 16f), (copies[0] as Element.Shape).points[0])
        assertEquals(Offset(216f, 16f), (copies[1] as Element.Shape).points[0])
    }

    @Test
    fun duplicateGroupClonesNestedHierarchyWithFreshIds() {
        val inner = Element.Group(
            id = "inner",
            children = listOf(rect("a", 0f, 0f), rect("b", 120f, 0f)),
            rotation = 10f,
        )
        val outer = Element.Group(
            id = "outer",
            children = listOf(inner, rect("c", 300f, 0f)),
            zIndex = 0,
            rotation = 25f,
        )
        val controller = controller(listOf(outer), selectedIds = setOf("outer"))

        controller.duplicateSelection()

        val state = controller.state.value
        assertEquals(2, state.elements.size)
        val copy = assertIs<Element.Group>(state.elements.first { it.id != "outer" })
        assertEquals(state.selectedIds.single(), copy.id)
        assertEquals(25f, copy.rotation)
        assertEquals(2, copy.children.size)
        val copyInner = assertIs<Element.Group>(copy.children.first())
        assertEquals(10f, copyInner.rotation)
        assertEquals(2, copyInner.children.size)

        val originalIds = setOf("outer", "inner", "a", "b", "c")
        val copyIds = flattenIds(copy)
        assertTrue(copyIds.intersect(originalIds).isEmpty(), "cloned ids must be fresh")
        assertEquals(5, copyIds.size)

        // Offset is applied once at the root and inherited by descendants.
        val originalA = inner.children.first() as Element.Shape
        val copyA = copyInner.children.first() as Element.Shape
        assertEquals(originalA.points[0] + Offset(16f, 16f), copyA.points[0])
    }

    private fun flattenIds(element: Element): Set<String> = when (element) {
        is Element.Group -> element.children.flatMap { flattenIds(it) }.toSet() + element.id
        else -> setOf(element.id)
    }

    @Test
    fun duplicateInsideEnteredGroupStaysInScope() {
        val group = Element.Group(
            id = "g",
            children = listOf(rect("a", 0f, 0f), rect("b", 200f, 0f)),
        )
        val controller = controller(
            listOf(group),
            selectedIds = setOf("a"),
            groupPath = listOf("g"),
        )

        controller.duplicateSelection()

        val state = controller.state.value
        assertEquals(listOf("g"), state.groupPath)
        val updated = assertIs<Element.Group>(state.elements.single())
        assertEquals(3, updated.children.size)
        val copy = updated.children.first { it.id != "a" && it.id != "b" }
        assertEquals(state.selectedIds.single(), copy.id)
    }

    @Test
    fun duplicateInsideRotatedGroupOffsetsInWorldSpace() {
        val group = Element.Group(
            id = "g",
            children = listOf(rect("a", 0f, 0f)),
            rotation = 90f,
        )
        val controller = controller(
            listOf(group),
            selectedIds = setOf("a"),
            groupPath = listOf("g"),
        )

        controller.duplicateSelection()

        val updated = assertIs<Element.Group>(controller.state.value.elements.single())
        val original = updated.children.first { it.id == "a" } as Element.Shape
        val copy = updated.children.first { it.id != "a" } as Element.Shape
        // World +16,+16 maps to local +16,-16 in a 90°-rotated group.
        val delta = copy.points[0] - original.points[0]
        assertTrue(
            kotlin.math.abs(delta.x - 16f) < 0.01f &&
                kotlin.math.abs(delta.y + 16f) < 0.01f,
            "expected (16,-16) but was $delta",
        )
    }

    @Test
    fun duplicateRemapsInternalBindingsAndKeepsExternalBinding() {
        val target = rect("target", 0f, 0f, z = 0)
        val external = rect("external", 300f, 0f, z = 1)
        val arrow = arrow(
            id = "arrow",
            startBinding = "target",
            endBinding = "external",
            z = 2,
        )
        val controller = controller(
            listOf(target, external, arrow),
            selectedIds = setOf("arrow", "target"),
        )

        controller.duplicateSelection()

        val state = controller.state.value
        val copies = state.elements.filter { it.id in state.selectedIds }
        val copyArrow = copies.filterIsInstance<Element.Shape>()
            .single { it.shapeType == ShapeType.ARROW }
        val copyTarget = copies.filterIsInstance<Element.Shape>()
            .single { it.shapeType == ShapeType.RECTANGLE }

        assertEquals(copyTarget.id, copyArrow.startBinding)
        assertEquals("external", copyArrow.endBinding)

        val originalArrow = state.elements.first { it.id == "arrow" } as Element.Shape
        assertEquals("target", originalArrow.startBinding)
        assertEquals("external", originalArrow.endBinding)
    }

    @Test
    fun duplicateEmptySelectionIsNoOpAndDoesNotSnapshot() {
        val controller = controller(listOf(rect("a", 0f, 0f)))

        controller.duplicateSelection()

        val state = controller.state.value
        assertEquals(1, state.elements.size)
        assertTrue(state.history.isEmpty())
        assertTrue(state.selectedIds.isEmpty())
    }
}
