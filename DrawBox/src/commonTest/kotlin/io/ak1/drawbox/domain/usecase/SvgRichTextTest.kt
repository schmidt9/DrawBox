package io.ak1.drawbox.domain.usecase

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.TextRun
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgRichTextTest {

    private fun text(
        body: String = "Hello world",
        alignment: TextAlignment = TextAlignment.LEFT,
        bold: Boolean = false,
        italic: Boolean = false,
        runs: List<TextRun> = emptyList(),
    ) = Element.Text(
        id = "t",
        text = body,
        fontSize = 20f,
        color = Color.Black,
        alignment = alignment,
        topLeft = Offset(0f, 0f),
        wrapWidth = 400f,
        measuredHeight = 24f,
        bold = bold,
        italic = italic,
        runs = runs,
    )

    private fun svg(element: Element.Text) = SvgExporter.exportToSvg(listOf(element))

    @Test
    fun blockItalicExportsFontStyle() {
        val svg = svg(text(italic = true))
        assertTrue(svg.contains("""font-style="italic""""), svg)
    }

    @Test
    fun uniformTextStillHasNoRunMarkup() {
        val svg = svg(text())
        assertFalse(svg.contains("font-weight"), svg)
        assertFalse(svg.contains("font-style"), svg)
        assertTrue(svg.contains("<tspan"), svg)
    }

    @Test
    fun boldRunEmitsASpanOverride() {
        val svg = svg(text(runs = listOf(TextRun(0, 5, bold = true))))
        assertTrue(svg.contains("""<tspan font-weight="bold">Hello</tspan>"""), svg)
        assertTrue(svg.contains(" world"), svg)
    }

    @Test
    fun explicitNormalOverABoldBaseEmitsNormalWeight() {
        val svg = svg(text(bold = true, runs = listOf(TextRun(0, 5, bold = false))))
        assertTrue(svg.contains("""font-weight="bold""""), svg) // the <text> base
        assertTrue(svg.contains("""<tspan font-weight="normal">Hello</tspan>"""), svg)
    }

    @Test
    fun sizeAndFamilyRunsEmitSpans() {
        val svg = svg(
            text(runs = listOf(TextRun(0, 5, fontFamilyKey = "serif", fontSize = 32f))),
        )
        assertTrue(svg.contains("font-family=\"serif\""), svg)
        assertTrue(svg.contains("font-size=\"32.0\""), svg)
    }

    @Test
    fun perParagraphAlignmentUsesPerLineAnchors() {
        val svg = svg(
            text(
                body = "aa\nbb",
                alignment = TextAlignment.LEFT,
                runs = listOf(TextRun(0, 3, alignment = TextAlignment.CENTER)),
            ),
        )
        assertTrue(svg.contains("""text-anchor="middle""""), svg)
        assertTrue(svg.contains("""text-anchor="start""""), svg)
    }
}
