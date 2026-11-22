package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.usecase.SvgExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupSerializationTest {

    private fun rect(id: String, x: Float, z: Int = 0): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(x, 0f), Offset(x + 100f, 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
    )

    @Test
    fun nestedGroupRoundTripPreservesHierarchyOrderAndRotation() {
        val inner = Element.Group(
            id = "inner",
            children = listOf(rect("a", 0f, 0), rect("b", 120f, 1)),
            zIndex = 0,
            rotation = 15f,
        )
        val outer = Element.Group(
            id = "outer",
            children = listOf(inner, rect("c", 300f, 1)),
            zIndex = 0,
            rotation = 42f,
        )

        val json = DrawingSerializer.serialize(
            PayLoad(bgColor = Color.White, elements = listOf(outer)),
        )
        val loaded = DrawingSerializer.deserialize(json).elements.single()
        val group = assertIs<Element.Group>(loaded)

        assertEquals("outer", group.id)
        assertEquals(42f, group.rotation)
        assertTrue(json.contains("\"children\""))
        assertEquals(listOf("inner", "c"), group.children.map { it.id })

        val loadedInner = assertIs<Element.Group>(group.children.first())
        assertEquals(15f, loadedInner.rotation)
        assertEquals(listOf("a", "b"), loadedInner.children.map { it.id })
    }

    @Test
    fun legacyJsonWithoutChildrenStillLoadsLeaves() {
        val json = """
            {
              "bgColor": "#ffffffff",
              "elements": [
                {
                  "id": "a",
                  "type": "Shape",
                  "zIndex": 0,
                  "shapeType": "RECTANGLE",
                  "points": ["0.0,0.0", "100.0,100.0"],
                  "strokeColor": "#000000ff",
                  "strokeWidth": 2.0
                }
              ]
            }
        """.trimIndent()

        val payload = DrawingSerializer.deserialize(json)
        val shape = assertIs<Element.Shape>(payload.elements.single())
        assertEquals("a", shape.id)
        assertEquals(ShapeType.RECTANGLE, shape.shapeType)
    }

    @Test
    fun svgRecursesIntoGroupsAndEmitsGroupRotation() {
        val inner = Element.Group(
            id = "inner",
            children = listOf(rect("a", 0f)),
            rotation = 10f,
        )
        val outer = Element.Group(
            id = "outer",
            children = listOf(inner),
            rotation = 30f,
        )

        val svg = SvgExporter.exportToSvg(listOf(outer))

        assertTrue(svg.contains("<rect"), "leaf rectangle must be exported")
        assertTrue(svg.contains("rotate(30.0"), "outer group rotation missing")
        assertTrue(svg.contains("rotate(10.0"), "inner group rotation missing")
    }
}
