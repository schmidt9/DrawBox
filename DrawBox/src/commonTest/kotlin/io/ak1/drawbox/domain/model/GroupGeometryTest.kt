package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupGeometryTest {

    private fun rect(
        id: String,
        x: Float,
        y: Float,
        z: Int = 0,
        rotation: Float = 0f,
    ): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(x, y), Offset(x + 100f, y + 100f)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
        rotation = rotation,
    )

    private fun assertRectEquals(expected: Rect, actual: Rect, tolerance: Float = 0.01f) {
        assertTrue(
            abs(expected.left - actual.left) <= tolerance &&
                abs(expected.top - actual.top) <= tolerance &&
                abs(expected.right - actual.right) <= tolerance &&
                abs(expected.bottom - actual.bottom) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    @Test
    fun boundsIsUnionOfChildrenAndNests() {
        val a = rect("a", 0f, 0f)
        val b = rect("b", 200f, 50f)
        val inner = Element.Group(id = "inner", children = listOf(a, b))
        val outer = Element.Group(id = "outer", children = listOf(inner))

        assertEquals(Rect(0f, 0f, 300f, 150f), outer.bounds())
    }

    @Test
    fun visualBoundsIncludeRotation() {
        val square = rect("a", 0f, 0f).copy(rotation = 45f)
        // A 100x100 square rotated 45° around its center spans ~141.4 in both axes.
        val visual = square.visualAabb()
        val expected = 50f * kotlin.math.sqrt(2f)
        assertTrue(abs(visual.left - (50f - expected)) < 0.1f, "left=${visual.left}")
        assertTrue(abs(visual.right - (50f + expected)) < 0.1f, "right=${visual.right}")
    }

    @Test
    fun hitTestDescendsIntoRotatedGroupAndIgnoresHollowInterior() {
        val left = rect("left", 0f, 0f)
        val right = rect("right", 300f, 0f)
        val group = Element.Group(
            id = "g",
            children = listOf(left, right),
            rotation = 90f,
        )
        val pivot = group.bounds().center
        // Left child occupies x 0..100 in the group frame; pick its left edge.
        val hitPoint = rotateAround(Offset(5f, 50f), pivot, 90f)
        assertTrue(group.hitTest(hitPoint))
        // The gap between the two children stays hollow even inside group bounds.
        assertFalse(group.hitTest(rotateAround(Offset(200f, 50f), pivot, 90f)))
    }

    @Test
    fun translateMovesAllDescendants() {
        val group = Element.Group(
            id = "g",
            children = listOf(rect("a", 0f, 0f), rect("b", 200f, 0f)),
        )
        val moved = group.translate(Offset(10f, 20f)) as Element.Group
        assertEquals(Rect(10f, 20f, 310f, 120f), moved.bounds())
    }

    @Test
    fun withBoundsScalesGeometryNotStrokeOrFont() {
        val path = Element.Path(
            id = "p",
            samples = listOf(
                Element.PathSample(Offset(0f, 0f), 3f),
                Element.PathSample(Offset(100f, 100f), 3f),
            ),
            strokeColor = Color.Black,
            strokeWidth = 3f,
            alpha = 1f,
        )
        val text = Element.Text(
            id = "t",
            text = "Hello",
            fontSize = 20f,
            color = Color.Black,
            topLeft = Offset(200f, 0f),
            wrapWidth = 100f,
            measuredHeight = 24f,
        )
        val group = Element.Group(id = "g", children = listOf(path, text))

        val scaled = group.withBounds(Rect(0f, 0f, 600f, 200f)) as Element.Group
        val scaledPath = scaled.children[0] as Element.Path
        val scaledText = scaled.children[1] as Element.Text

        assertEquals(3f, scaledPath.strokeWidth)
        assertEquals(listOf(3f, 3f), scaledPath.samples.map { it.width })
        assertEquals(20f, scaledText.fontSize)
        assertEquals(24f, scaledText.measuredHeight)
        assertEquals(200f, scaledText.wrapWidth)
        assertEquals(Rect(0f, 0f, 600f, 200f), scaled.bounds())
    }

    @Test
    fun rotationIsPersistedOnTheGroup() {
        val group = Element.Group(id = "g", children = listOf(rect("a", 0f, 0f)))
        assertEquals(45f, (group.withRotation(45f) as Element.Group).rotation)
    }

    @Test
    fun bakedIntoParentPreservesRenderedPosition() {
        val leaf = rect("a", 20f, 30f).copy(rotation = 17f)
        val group = Element.Group(
            id = "g",
            children = listOf(leaf),
            rotation = 33f,
        )
        val pivot = group.bounds().center
        val baked = leaf.bakedIntoParent(pivot, group.rotation)

        // The rendered leaf keeps its shape/size; its center orbits the pivot
        // and its rotation composes.
        val expectedCenter = rotateAround(leaf.bounds().center, pivot, group.rotation)
        assertRectEquals(
            Rect(
                expectedCenter.x - leaf.bounds().width / 2f,
                expectedCenter.y - leaf.bounds().height / 2f,
                expectedCenter.x + leaf.bounds().width / 2f,
                expectedCenter.y + leaf.bounds().height / 2f,
            ),
            baked.bounds(),
            tolerance = 0.1f,
        )
        assertEquals(17f + 33f, baked.rotation)
    }

    @Test
    fun bakedGroupChildPreservesRenderedPosition() {
        val childGroup = Element.Group(
            id = "child",
            children = listOf(rect("a", 50f, 50f), rect("b", 150f, 70f)),
            rotation = 12f,
        )
        val parent = Element.Group(
            id = "parent",
            children = listOf(childGroup),
            rotation = 40f,
        )
        val pivot = parent.bounds().center
        val baked = childGroup.bakedIntoParent(pivot, parent.rotation) as Element.Group

        val expectedCenter = rotateAround(childGroup.bounds().center, pivot, parent.rotation)
        assertEquals(12f + 40f, baked.rotation)
        assertTrue(
            abs(baked.bounds().center.x - expectedCenter.x) < 0.1f &&
                abs(baked.bounds().center.y - expectedCenter.y) < 0.1f,
            "expected center $expectedCenter but was ${baked.bounds().center}",
        )
        // Child geometry moved with the group; relative layout is unchanged.
        assertEquals(listOf("a", "b"), baked.children.map { it.id })
    }
}
