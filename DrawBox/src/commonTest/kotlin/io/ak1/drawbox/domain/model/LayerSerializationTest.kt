package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LayerSerializationTest {

    private fun rect(id: String): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset.Zero, Offset(100f, 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        name = "Rectangle $id",
        layerId = "l1",
    )

    @Test
    fun pageLayersAndElementMembershipRoundTrip() {
        val payload = PagesPayLoad(
            activePageId = "p1",
            pages = listOf(
                PagePayLoad(
                    id = "p1",
                    name = "Page 1",
                    bgColor = Color.White,
                    elements = listOf(rect("a")),
                    layers = listOf(
                        Layer(id = "l1", name = "Foreground", isVisible = false, isLocked = true),
                    ),
                ),
            ),
        )

        val json = PagesDrawingSerializer.serialize(payload)
        assertTrue(json.contains("\"version\": 4"))
        val loaded = PagesDrawingSerializer.deserialize(json)

        val page = loaded.pages.single()
        assertEquals(listOf("Foreground"), page.layers.map { it.name })
        assertEquals(false, page.layers.single().isVisible)
        assertEquals(true, page.layers.single().isLocked)
        val element = page.elements.single()
        assertEquals("Rectangle a", element.name)
        assertEquals("l1", element.layerId)
    }

    @Test
    fun legacyV2JsonGetsEmptyLayersAndGeneratedNames() {
        val json = """
            {
              "version": 2,
              "activePageId": "p1",
              "pages": [
                {
                  "id": "p1",
                  "name": "Page 1",
                  "bgColor": "#ffffffff",
                  "elements": [
                    {
                      "id": "a",
                      "type": "Shape",
                      "zIndex": 0,
                      "points": ["0,0", "100,100"],
                      "strokeColor": "#000000ff",
                      "strokeWidth": 2,
                      "shapeType": "RECTANGLE"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val loaded = PagesDrawingSerializer.deserialize(json)
        val page = loaded.pages.single()
        assertTrue(page.layers.isEmpty())
        assertEquals("Rectangle 1", page.elements.single().name)
        assertEquals(null, page.elements.single().layerId)
    }

    @Test
    fun unknownLayerReferencesAreClearedOnLoad() {
        val payload = PagesPayLoad(
            activePageId = "p1",
            pages = listOf(
                PagePayLoad(
                    id = "p1",
                    name = "Page 1",
                    bgColor = Color.White,
                    elements = listOf(rect("a")),
                    layers = emptyList(),
                ),
            ),
        )
        val loaded = PagesDrawingSerializer.deserialize(
            PagesDrawingSerializer.serialize(payload),
        )
        assertEquals(null, loaded.pages.single().elements.single().layerId)
    }

    @Test
    fun singlePageDrawingSerializerCarriesLayers() {
        val payload = PayLoad(
            bgColor = Color.White,
            elements = listOf(rect("a")),
            layers = listOf(Layer(id = "l1", name = "Layer 1")),
        )
        val loaded = DrawingSerializer.deserialize(DrawingSerializer.serialize(payload))
        assertNotNull(loaded.layers.singleOrNull())
        assertEquals("Layer 1", loaded.layers.single().name)
        assertEquals("l1", loaded.elements.single().layerId)
    }

    @Test
    fun layerNameHelpersAreCaseInsensitiveAndUnique() {
        val layers = listOf(
            Layer(id = "l1", name = "Layer 1"),
            Layer(id = "l2", name = "Layer 2"),
        )
        assertTrue(isLayerNameTaken("layer 1", layers))
        assertEquals(false, isLayerNameTaken("layer 1", layers, excludingId = "l1"))
        assertEquals("Layer 3", defaultLayerName(layers))
        assertEquals("Layer 1 2", uniqueLayerName("Layer 1", layers))
    }
}
