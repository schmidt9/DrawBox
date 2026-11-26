package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.usecase.SvgExporter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextBoldTest {

    private fun text(bold: Boolean) = Element.Text(
        id = "text",
        text = "Bold",
        fontSize = 20f,
        color = Color.Black,
        topLeft = Offset(0f, 0f),
        wrapWidth = 120f,
        measuredHeight = 24f,
        bold = bold,
    )

    @Test
    fun boldTextExportsWithTheSvgFontWeight() {
        val boldSvg = SvgExporter.exportToSvg(listOf(text(true)))
        val normalSvg = SvgExporter.exportToSvg(listOf(text(false)))

        assertTrue(boldSvg.contains("""font-weight="bold""""))
        assertFalse(normalSvg.contains("font-weight"))
    }
}
