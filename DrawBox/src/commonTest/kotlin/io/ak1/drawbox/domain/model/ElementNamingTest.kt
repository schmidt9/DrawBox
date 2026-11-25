package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElementNamingTest {

    private fun rect(): Element.Shape = Element.Shape(
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(0f, 0f), Offset(10f, 10f)),
        strokeColor = Color.Black,
        strokeWidth = 1f,
    )

    private fun text(): Element.Text = Element.Text(
        text = "hello",
        fontSize = 12f,
        color = Color.Black,
        topLeft = Offset.Zero,
        wrapWidth = 100f,
        measuredHeight = 14f,
    )

    @Test
    fun addElementAssignsUniqueNamesPerKind() {
        val useCase = io.ak1.drawbox.domain.usecase.UseCase()
        var elements = emptyList<Element>()
        elements = useCase.addElement(rect(), elements)
        elements = useCase.addElement(rect(), elements)
        elements = useCase.addElement(text(), elements)

        assertEquals(
            listOf("Rectangle 1", "Rectangle 2", "Text 1"),
            elements.map { it.name },
        )
        assertEquals(elements.map { it.id }.toSet().size, elements.size)
    }

    @Test
    fun groupingNamesTheGroupAndKeepsChildNames() {
        val useCase = io.ak1.drawbox.domain.usecase.UseCase()
        var elements = emptyList<Element>()
        elements = useCase.addElement(rect(), elements)
        elements = useCase.addElement(rect(), elements)
        val ids = elements.mapTo(hashSetOf()) { it.id }

        val grouped = useCase.groupSelected(elements, emptyList(), ids)
        val group = grouped.single() as Element.Group
        assertEquals("Group 1", group.name)
        assertEquals(listOf("Rectangle 1", "Rectangle 2"), group.children.map { it.name })
    }

    @Test
    fun duplicateGetsFreshNamesIncludingDescendants() {
        val useCase = io.ak1.drawbox.domain.usecase.UseCase()
        var elements = emptyList<Element>()
        elements = useCase.addElement(rect(), elements)
        elements = useCase.addElement(rect(), elements)
        val ids = elements.mapTo(hashSetOf()) { it.id }
        elements = useCase.groupSelected(elements, emptyList(), ids)
        val groupId = elements.single().id

        val result = useCase.duplicateSelected(elements, emptyList(), setOf(groupId))!!
        val duplicate = result.elements.first { it.id in result.selectedIds } as Element.Group
        assertEquals("Group 2", duplicate.name)
        assertTrue(duplicate.children.all { it.name.startsWith("Rectangle ") })
        assertTrue(duplicate.children.none { it.name == "Rectangle 1" || it.name == "Rectangle 2" })
    }

    @Test
    fun normalizedNamesFillBlanksAndAvoidCollisions() {
        val named = rect().copy(name = "Rectangle 1")
        val blank = rect().copy(name = "")
        val normalized = listOf(named, blank).withNormalizedNames()
        assertEquals("Rectangle 1", normalized[0].name)
        assertEquals("Rectangle 2", normalized[1].name)
    }

    @Test
    fun sanitizeLayerIdsClearsUnknownAndNestedMembership() {
        val layers = listOf(Layer(id = "known", name = "Layer 1"))
        val topKnown = rect().copy(id = "a", layerId = "known")
        val topUnknown = rect().copy(id = "b", layerId = "missing")
        val nested = Element.Group(
            id = "g",
            children = listOf(rect().copy(layerId = "known")),
            layerId = null,
        )
        val sanitized = listOf(topKnown, topUnknown, nested).sanitizeLayerIds(layers)

        assertEquals("known", sanitized[0].layerId)
        assertEquals(null, sanitized[1].layerId)
        assertEquals(null, (sanitized[2] as Element.Group).children.single().layerId)
    }

    @Test
    fun visibleAndHittableHelpersTreatUnknownLayerIdsAsUnattached() {
        val free = rect().copy(id = "free")
        val hidden = rect().copy(id = "hidden", layerId = "l1")
        val locked = rect().copy(id = "locked", layerId = "l2")
        val state = State(
            elements = listOf(free, hidden, locked),
            layers = listOf(
                Layer(id = "l1", name = "Layer 1", isVisible = false),
                Layer(id = "l2", name = "Layer 2", isLocked = true),
            ),
        )
        assertEquals(listOf("free", "locked"), state.visibleElements().map { it.id })
        assertEquals(listOf("free"), state.hittableElements().map { it.id })
    }
}
