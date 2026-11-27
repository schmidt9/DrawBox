package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextRunSerializationTest {

    private fun page(text: Element.Text) = PagesPayLoad(
        activePageId = "p1",
        pages = listOf(
            PagePayLoad(
                id = "p1",
                name = "Page 1",
                bgColor = Color.White,
                elements = listOf(text),
            ),
        ),
    )

    private fun text(
        text: String = "Hello world",
        bold: Boolean = false,
        italic: Boolean = false,
        runs: List<TextRun> = emptyList(),
    ) = Element.Text(
        id = "t",
        text = text,
        fontSize = 24f,
        color = Color.Black,
        topLeft = Offset(10f, 20f),
        wrapWidth = 240f,
        measuredHeight = 30f,
        bold = bold,
        italic = italic,
        runs = runs,
    )

    private fun roundTrip(text: Element.Text): Element.Text {
        val json = PagesDrawingSerializer.serialize(page(text))
        val loaded = PagesDrawingSerializer.deserialize(json).pages.single()
        return assertIs<Element.Text>(loaded.elements.single())
    }

    @Test
    fun richTextSurvivesTheRoundTrip() {
        val source = text(
            text = "Hello world",
            italic = true,
            runs = listOf(
                TextRun(
                    0, 5,
                    bold = true,
                    fontFamilyKey = "serif",
                    fontSize = 32f,
                    alignment = TextAlignment.CENTER,
                ),
                TextRun(6, 11, italic = false),
            ),
        )

        val loaded = roundTrip(source)

        assertTrue(loaded.italic, "block italic flag")
        assertEquals(
            listOf(
                TextRun(0, 5, bold = true, fontFamilyKey = "serif", fontSize = 32f, alignment = TextAlignment.CENTER),
                TextRun(6, 11, italic = false),
            ),
            loaded.runs,
        )
    }

    @Test
    fun uniformTextWritesNoRunsOrItalic() {
        val json = PagesDrawingSerializer.serialize(page(text()))

        assertFalse(json.contains("\"runs\""), json)
        assertFalse(json.contains("\"italic\""), json)
    }

    @Test
    fun versionIsFour() {
        val json = PagesDrawingSerializer.serialize(page(text()))

        assertTrue(json.contains("\"version\": 4"), json)
    }

    @Test
    fun legacyTextWithoutRunsLoadsUniform() {
        val json = """
            {
              "version": 3,
              "activePageId": "p1",
              "pages": [
                {
                  "id": "p1",
                  "name": "Page 1",
                  "bgColor": "#ffffffff",
                  "elements": [
                    {
                      "id": "t",
                      "type": "Text",
                      "zIndex": 0,
                      "points": [],
                      "strokeColor": "#ff000000",
                      "strokeWidth": 0.0,
                      "text": "Old",
                      "fontSize": 20.0,
                      "alignment": "CENTER"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val loaded = PagesDrawingSerializer.deserialize(json).pages.single()
        val loadedText = assertIs<Element.Text>(loaded.elements.single())

        assertEquals("Old", loadedText.text)
        assertEquals(TextAlignment.CENTER, loadedText.alignment)
        assertFalse(loadedText.italic)
        assertEquals(emptyList(), loadedText.runs)
    }

    @Test
    fun malformedRunsAreSanitizedOnLoad() {
        val json = """
            {
              "version": 4,
              "activePageId": "p1",
              "pages": [
                {
                  "id": "p1",
                  "name": "Page 1",
                  "bgColor": "#ffffffff",
                  "elements": [
                    {
                      "id": "t",
                      "type": "Text",
                      "zIndex": 0,
                      "points": [],
                      "strokeColor": "#ff000000",
                      "strokeWidth": 0.0,
                      "text": "abcd",
                      "fontSize": 20.0,
                      "runs": [
                        { "start": -5, "end": 2, "bold": true },
                        { "start": 1, "end": 99, "italic": true },
                        { "start": 3, "end": 3, "fontSize": 50.0 },
                        { "start": 2, "end": 3, "alignment": "NONSENSE" }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val loaded = PagesDrawingSerializer.deserialize(json).pages.single()
        val loadedText = assertIs<Element.Text>(loaded.elements.single())

        // Clamped and trimmed first-wins, the empty run is dropped, and the
        // unknown alignment string inherits the block alignment.
        assertEquals(
            listOf(
                TextRun(0, 2, bold = true),
                TextRun(2, 4, italic = true),
            ),
            loadedText.runs,
        )
    }
}
