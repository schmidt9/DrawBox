package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PagesSerializationTest {

    private fun rect(id: String, x: Float, z: Int = 0): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(x, 0f), Offset(x + 100f, 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
    )

    @Test
    fun twoPageRoundTripPreservesIdsNamesBackgroundsAndElements() {
        val payload = PagesPayLoad(
            activePageId = "page-b",
            pages = listOf(
                PagePayLoad(
                    id = "page-a",
                    name = "Page 1",
                    bgColor = Color.White,
                    elements = listOf(rect("a", 0f)),
                ),
                PagePayLoad(
                    id = "page-b",
                    name = "Sketch",
                    bgColor = Color(0xFFFF0000),
                    elements = listOf(rect("b", 200f), rect("c", 400f)),
                ),
            ),
        )

        val json = PagesDrawingSerializer.serialize(payload)
        val loaded = PagesDrawingSerializer.deserialize(json)

        assertEquals("page-b", loaded.activePageId)
        assertEquals(listOf("page-a", "page-b"), loaded.pages.map { it.id })
        assertEquals(listOf("Page 1", "Sketch"), loaded.pages.map { it.name })
        assertEquals("#ffffffff", loaded.pages[0].bgColor.toHexString())
        assertEquals("#ff0000ff", loaded.pages[1].bgColor.toHexString())
        assertEquals(listOf("a"), loaded.pages[0].elements.map { it.id })
        assertEquals(listOf("b", "c"), loaded.pages[1].elements.map { it.id })
        assertTrue(json.contains("\"version\": 3"))
    }

    @Test
    fun nestedGroupAndTextSurvivePageRoundTrip() {
        val group = Element.Group(
            id = "group",
            zIndex = 0,
            rotation = 15f,
            children = listOf(rect("child", 0f), rect("child-2", 140f, 1)),
        )
        val text = Element.Text(
            id = "text",
            text = "Hello",
            fontSize = 24f,
            color = Color.Black,
            topLeft = Offset(10f, 20f),
            wrapWidth = 240f,
            measuredHeight = 30f,
            zIndex = 1,
        )

        val json = PagesDrawingSerializer.serialize(
            PagesPayLoad(
                activePageId = "p1",
                pages = listOf(
                    PagePayLoad(
                        id = "p1",
                        name = "Page 1",
                        bgColor = Color.White,
                        elements = listOf(group, text),
                    ),
                ),
            ),
        )
        val loaded = PagesDrawingSerializer.deserialize(json).pages.single()

        val loadedGroup = assertIs<Element.Group>(loaded.elements[0])
        assertEquals(15f, loadedGroup.rotation)
        assertEquals(listOf("child", "child-2"), loadedGroup.children.map { it.id })

        val loadedText = assertIs<Element.Text>(loaded.elements[1])
        assertEquals("Hello", loadedText.text)
        assertEquals(Offset(10f, 20f), loadedText.topLeft)
        assertEquals(240f, loadedText.wrapWidth)
    }

    @Test
    fun oldSinglePageJsonFailsToDeserialize() {
        val legacyJson = """
            {
              "bgColor": "#ffffffff",
              "elements": []
            }
        """.trimIndent()

        assertFails { PagesDrawingSerializer.deserialize(legacyJson) }
    }

    @Test
    fun unknownKeysAreIgnored() {
        val json = """
            {
              "version": 2,
              "activePageId": "p1",
              "extraTopLevel": true,
              "pages": [
                {
                  "id": "p1",
                  "name": "Page 1",
                  "bgColor": "#ffffffff",
                  "elements": [],
                  "extraPageField": 42
                }
              ]
            }
        """.trimIndent()

        val loaded = PagesDrawingSerializer.deserialize(json)

        assertEquals("p1", loaded.activePageId)
        assertEquals("Page 1", loaded.pages.single().name)
    }
}
