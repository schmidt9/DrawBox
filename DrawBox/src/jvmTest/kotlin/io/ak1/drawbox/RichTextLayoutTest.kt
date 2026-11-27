package io.ak1.drawbox

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rich-text runs must reach the real text layout: span styles for family,
 * size, weight and style, and paragraph styles for alignment. These use a real
 * [TextMeasurer] (Skiko is on the jvmTest classpath), unlike the structural
 * commonTest suite.
 */
class RichTextLayoutTest {

    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr,
    )

    private fun layout(
        text: String,
        runs: List<TextRun> = emptyList(),
        fontSize: Float = 20f,
        fontFamilyKey: String = "sans",
        alignment: TextAlignment = TextAlignment.LEFT,
        wrapWidth: Float = 300f,
    ) = TextLayoutCache().layoutFor(
        id = "text",
        text = text,
        fontFamilyKey = fontFamilyKey,
        fontSize = fontSize,
        alignment = alignment,
        wrapWidth = wrapWidth,
        measurer = measurer,
        runs = runs,
    )

    @Test
    fun boldRunReachesTheLayoutAsASpanStyle() {
        val result = layout("abc", runs = listOf(TextRun(0, 3, bold = true)))
        assertTrue(
            result.layoutInput.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold },
            "bold span missing: ${result.layoutInput.text.spanStyles}",
        )
    }

    @Test
    fun italicRunReachesTheLayoutAsASpanStyle() {
        val result = layout("abc", runs = listOf(TextRun(0, 3, italic = true)))
        assertTrue(
            result.layoutInput.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic },
            "italic span missing: ${result.layoutInput.text.spanStyles}",
        )
    }

    @Test
    fun spanFontSizeChangesTheMeasuredHeight() {
        val base = layout("abc")
        val larger = layout("abc", runs = listOf(TextRun(0, 3, fontSize = 60f)))
        assertTrue(
            larger.size.height > base.size.height,
            "a 60 px run (${larger.size.height}) should be taller than 20 px (${base.size.height})",
        )
    }

    @Test
    fun monospaceRunChangesTheMeasuredWidth() {
        val text = "iiiiiiiiii"
        val sans = layout(text, wrapWidth = 1000f)
        val mono = layout(text, runs = listOf(TextRun(0, text.length, fontFamilyKey = "mono")), wrapWidth = 1000f)
        assertTrue(
            mono.size.width >= sans.size.width,
            "monospace run (${mono.size.width}) should be at least as wide as sans (${sans.size.width})",
        )
    }

    @Test
    fun paragraphAlignmentOverrideCentersTheLine() {
        val left = layout("abc", alignment = TextAlignment.LEFT)
        val centered = layout(
            "abc",
            runs = listOf(TextRun(0, 3, alignment = TextAlignment.CENTER)),
        )
        assertEquals(0f, left.getLineLeft(0), 0.5f)
        assertTrue(
            centered.getLineLeft(0) > 0f,
            "centered paragraph should be offset from the left edge: ${centered.getLineLeft(0)}",
        )
    }

    @Test
    fun perParagraphAlignmentKeepsOtherParagraphsAtTheBase() {
        val text = "aa\nbb"
        val result = layout(
            text,
            runs = listOf(TextRun(0, 3, alignment = TextAlignment.RIGHT)),
        )
        assertTrue(result.getLineLeft(0) > 0f, "first paragraph should be right-aligned")
        assertEquals(0f, result.getLineLeft(result.lineCount - 1), 0.5f)
    }
}
