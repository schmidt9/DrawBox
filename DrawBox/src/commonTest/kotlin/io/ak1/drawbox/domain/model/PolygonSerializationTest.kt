package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.usecase.SvgExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolygonSerializationTest {

    private val polygon = Element.Shape(
        id = "polygon",
        shapeType = ShapeType.POLYGON,
        points = listOf(
            Offset(0f, 0f),
            Offset(100f, 0f),
            Offset(100f, 100f),
            Offset(0f, 100f),
        ),
        strokeColor = Color.Red,
        fillColor = Color.Blue,
        strokeWidth = 3f,
    )

    @Test
    fun jsonRoundTripPreservesPolygonVertices() {
        val json = DrawingSerializer.serialize(
            PayLoad(bgColor = Color.White, elements = listOf(polygon)),
        )
        val shape = DrawingSerializer.deserialize(json).elements.single() as Element.Shape
        assertEquals(ShapeType.POLYGON, shape.shapeType)
        assertEquals(polygon.points, shape.points)
    }

    @Test
    fun svgExportsPolygonWithAllPoints() {
        val svg = SvgExporter.exportToSvg(listOf(polygon))
        assertTrue(svg.contains("<polygon"))
        assertTrue(svg.contains("0.0,0.0"))
        assertTrue(svg.contains("100.0,100.0"))
    }
}
