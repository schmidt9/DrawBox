package io.ak1.drawbox

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ak1.drawbox.domain.model.TextAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Element.Text.fontSize` / `wrapWidth` / `measuredHeight` are world units, so
 * text layout must not depend on the device density or system font scale.
 * Before `WorldTextDensity` the layout was measured at the composition
 * density, which made the same document render ~3× larger on Android than on
 * desktop and broke two-letter labels onto a second line.
 */
class TextLayoutDensityTest {

    private fun layoutHeight(density: Float, text: String = "NE"): Float {
        val measurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = Density(density = density),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
        return TextLayoutCache().layoutFor(
            id = "text",
            text = text,
            fontFamilyKey = "sans",
            fontSize = 40f,
            alignment = TextAlignment.CENTER,
            wrapWidth = 80f,
            measurer = measurer,
        ).size.height.toFloat()
    }

    @Test
    fun layoutSizeIsIndependentOfDeviceDensity() {
        assertEquals(layoutHeight(1f), layoutHeight(3f), 1e-3f)
    }

    @Test
    fun twoLetterLabelFitsOnOneLine() {
        // A single 40 px line is ~47 px tall; wrapping would roughly double it.
        assertTrue(
            layoutHeight(3f) < 40f * 1.6f,
            "label wrapped: ${layoutHeight(3f)} px tall for a 40 px em",
        )
    }
}
