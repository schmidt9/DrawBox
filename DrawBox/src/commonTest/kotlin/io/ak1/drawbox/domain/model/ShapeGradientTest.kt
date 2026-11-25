package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.usecase.SvgExporter
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeGradientTest {

    private fun gradient(start: Offset = Offset(0f, 0f), end: Offset = Offset(0f, 100f)) =
        ShapeGradient(
            start = start,
            end = end,
            stops = listOf(
                GradientStop(0f, Color.Transparent),
                GradientStop(1f, Color.Green),
            ),
        )

    private fun gradientRect(): Element.Shape = Element.Shape(
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(0f, 0f), Offset(100f, 60f)),
        strokeColor = Color.Black,
        fillColor = null,
        strokeWidth = 1f,
        fillGradient = gradient(),
    )

    @Test
    fun gradientCountsAsFillForHitTesting() {
        val shape = gradientRect()
        assertTrue(shape.hitTest(Offset(50f, 30f)))

        val strokeOnly = shape.copy(fillGradient = null)
        assertFalse(strokeOnly.hitTest(Offset(50f, 30f)))
    }

    @Test
    fun translateMovesGradientEndpoints() {
        val moved = gradientRect().translate(Offset(10f, 20f))
        val shape = moved as Element.Shape
        assertEquals(Offset(10f, 20f), shape.fillGradient?.start)
        assertEquals(Offset(10f, 120f), shape.fillGradient?.end)
    }

    @Test
    fun resizeScalesGradientEndpoints() {
        val resized = gradientRect().withBounds(Rect(0f, 0f, 200f, 120f))
        val shape = resized as Element.Shape
        assertEquals(Offset(0f, 0f), shape.fillGradient?.start)
        assertEquals(Offset(0f, 200f), shape.fillGradient?.end)
    }

    @Test
    fun rotationKeepsGradientInTheUnrotatedFrame() {
        val rotated = gradientRect().withRotation(45f) as Element.Shape
        assertEquals(gradient(), rotated.fillGradient)
    }

    @Test
    fun dtoAndJsonRoundTripPreserveGradient() {
        val shape = gradientRect()
        val restored = shape.toDto().toElement() as Element.Shape
        assertEquals(shape.fillGradient, restored.fillGradient)

        val payload = PayLoad(
            bgColor = Color.White,
            elements = listOf(shape),
        )
        val json = DrawingSerializer.serialize(payload)
        val back = DrawingSerializer.deserialize(json)
        assertEquals(
            shape.fillGradient,
            (back.elements.single() as Element.Shape).fillGradient,
        )
    }

    @Test
    fun svgEmitsGradientDefAndUrlFill() {
        val svg = SvgExporter.exportToSvg(listOf(gradientRect()))
        assertTrue(svg.contains("<linearGradient"), svg)
        assertTrue(svg.contains("""fill="url(#grad-"""), svg)
        assertTrue(svg.contains("""stop-opacity="0.0""""), svg)
        assertTrue(svg.contains("""stop-opacity="1.0""""), svg)
    }

    @Test
    fun settingSolidFillClearsGradient() {
        val shape = gradientRect()
        val updated = UseCase()
            .setSelectedFillColor(listOf(shape), setOf(shape.id), Color.Red)
            .single() as Element.Shape
        assertEquals(Color.Red, updated.fillColor)
        assertNull(updated.fillGradient)
    }
}
