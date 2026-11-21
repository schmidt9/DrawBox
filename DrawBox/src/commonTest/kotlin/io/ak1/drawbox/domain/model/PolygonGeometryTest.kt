package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonGeometryTest {

    private fun polygon(
        points: List<Offset> = listOf(
            Offset(0f, 0f),
            Offset(100f, 0f),
            Offset(100f, 100f),
            Offset(0f, 100f),
        ),
        fillColor: Color? = null,
    ): Element.Shape = Element.Shape(
        id = "polygon",
        shapeType = ShapeType.POLYGON,
        points = points,
        strokeColor = Color.Black,
        fillColor = fillColor,
        strokeWidth = 2f,
    )

    @Test
    fun boundsUseAllVertices() {
        val shape = polygon(
            listOf(Offset(10f, 20f), Offset(50f, -10f), Offset(80f, 40f)),
        )
        assertEquals(Rect(10f, -10f, 80f, 40f), shape.bounds())
    }

    @Test
    fun resizeScalesEveryVertex() {
        val resized = polygon().withBounds(Rect(0f, 0f, 50f, 50f)) as Element.Shape
        assertEquals(4, resized.points.size)
        assertEquals(Offset(50f, 0f), resized.points[1])
        assertEquals(Offset(50f, 50f), resized.points[2])
        assertEquals(Offset(0f, 50f), resized.points[3])
    }

    @Test
    fun filledPolygonHitsInteriorButStrokeOnlyHitsEdges() {
        val filled = polygon(fillColor = Color.Blue)
        val strokeOnly = polygon(fillColor = null)
        assertTrue(filled.hitTest(Offset(50f, 50f), tolerance = 1f))
        assertFalse(strokeOnly.hitTest(Offset(50f, 50f), tolerance = 1f))
        assertTrue(strokeOnly.hitTest(Offset(50f, 0f), tolerance = 1f))
    }
}
