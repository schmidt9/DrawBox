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
    fun withBoundsScalesGroupTextButNotStrokeOrSampleWidth() {
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

        // Old group bounds are 300 x 100, so this is a uniform 2x resize.
        val scaled = group.withBounds(Rect(0f, 0f, 600f, 200f)) as Element.Group
        val scaledPath = scaled.children[0] as Element.Path
        val scaledText = scaled.children[1] as Element.Text

        assertEquals(3f, scaledPath.strokeWidth)
        assertEquals(listOf(3f, 3f), scaledPath.samples.map { it.width })
        assertEquals(40f, scaledText.fontSize)
        assertEquals(48f, scaledText.measuredHeight)
        assertEquals(200f, scaledText.wrapWidth)
        assertEquals(Rect(0f, 0f, 600f, 200f), scaled.bounds())
    }

    @Test
    fun standaloneTextResizeKeepsItsFontSize() {
        val text = Element.Text(
            id = "t",
            text = "Hello",
            fontSize = 20f,
            color = Color.Black,
            topLeft = Offset(0f, 0f),
            wrapWidth = 100f,
            measuredHeight = 24f,
        )

        val scaled = text.withBounds(Rect(0f, 0f, 300f, 72f)) as Element.Text

        assertEquals(20f, scaled.fontSize)
        assertEquals(300f, scaled.wrapWidth)
    }

    @Test
    fun withBoundsScalesNestedGroupTextOnce() {
        val text = Element.Text(
            id = "t",
            text = "子",
            fontSize = 20f,
            color = Color.Black,
            topLeft = Offset(0f, 0f),
            wrapWidth = 100f,
            measuredHeight = 24f,
        )
        val inner = Element.Group(id = "inner", children = listOf(text))
        val outer = Element.Group(id = "outer", children = listOf(inner))

        // Old bounds are 100 x 24; 400 x 48 gives a 4x width and 2x height
        // factor. Text size follows width, so the font quadruples.
        val scaled = outer.withBounds(Rect(0f, 0f, 400f, 48f)) as Element.Group
        val scaledInner = scaled.children.single() as Element.Group
        val scaledText = scaledInner.children.single() as Element.Text

        // The factor applies exactly once, not compounded at each group level.
        assertEquals(80f, scaledText.fontSize)
    }

    @Test
    fun edgeResizeRoundTripRestoresGroupTextSize() {
        val text = Element.Text(
            id = "t",
            text = "Hello",
            fontSize = 20f,
            color = Color.Black,
            topLeft = Offset(0f, 0f),
            wrapWidth = 100f,
            measuredHeight = 24f,
        )
        val group = Element.Group(id = "g", children = listOf(text))
        val original = group.bounds()

        // Width edge: shrink to half, then drag the same edge back out.
        val narrow = group.withBounds(
            Rect(original.left, original.top, original.left + original.width / 2f, original.bottom),
        )
        assertEquals(
            10f,
            ((narrow as Element.Group).children.single() as Element.Text).fontSize,
            0.01f,
        )
        val wide = narrow.withBounds(original)
        assertEquals(
            20f,
            ((wide as Element.Group).children.single() as Element.Text).fontSize,
            0.01f,
        )
    }

    @Test
    fun heightOnlyResizeLeavesGroupTextSizeAlone() {
        val text = Element.Text(
            id = "t",
            text = "Hello",
            fontSize = 20f,
            color = Color.Black,
            topLeft = Offset(0f, 0f),
            wrapWidth = 100f,
            measuredHeight = 24f,
        )
        val group = Element.Group(id = "g", children = listOf(text))
        val original = group.bounds()

        // Glyph size tracks width; stretching the diagram vertically must not
        // shrink or grow the labels.
        val tall = group.withBounds(
            Rect(original.left, original.top, original.right, original.top + original.height * 2f),
        )
        assertEquals(
            20f,
            ((tall as Element.Group).children.single() as Element.Text).fontSize,
            0.01f,
        )
    }

    @Test
    fun repeatedWidthResizeFramesDoNotRatchetGroupText() {
        val text = Element.Text(
            id = "t",
            text = "Hello",
            fontSize = 20f,
            color = Color.Black,
            topLeft = Offset(0f, 0f),
            wrapWidth = 100f,
            measuredHeight = 24f,
        )
        val original = Element.Group(id = "g", children = listOf(text))
        val bounds = original.bounds()
        var current: Element = original

        fun resizeToWidth(width: Float) {
            current = current.withBounds(
                Rect(bounds.left, bounds.top, bounds.left + width, bounds.bottom),
            )
        }

        // The app applies every drag frame to the previous frame's element.
        // Grow to 2x width, then drag the same edge back to the start.
        for (step in 1..20) resizeToWidth(bounds.width * (1f + step / 20f))
        val grownFont = ((current as Element.Group).children.single() as Element.Text).fontSize
        assertEquals(40f, grownFont, 0.01f)

        for (step in 19 downTo 0) resizeToWidth(bounds.width * (1f + step / 20f))
        val restoredFont = ((current as Element.Group).children.single() as Element.Text).fontSize
        assertEquals(20f, restoredFont, 0.01f)
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

    @Test
    fun elementRenderedWorldPointAppliesOwnRotationThenAncestors() {
        val text = Element.Text(
            id = "t",
            text = "X",
            fontSize = 10f,
            color = Color.Black,
            topLeft = Offset(10f, 20f),
            wrapWidth = 100f,
            measuredHeight = 20f,
            rotation = 90f,
        )
        // Top-left rotated 90° around the wrap-box centre (60, 30).
        val ownRotated = rotateAround(text.topLeft, text.bounds().center, 90f)
        assertPointEquals(Offset(70f, -20f), ownRotated)

        // No rotation: identity.
        val plain = text.copy(rotation = 0f)
        assertPointEquals(
            plain.topLeft,
            listOf(plain).elementRenderedWorldPoint(plain.id, plain.topLeft),
        )

        // Own rotation applies first, then the ancestor group's rotation.
        val group = Element.Group(id = "g", children = listOf(text), rotation = 90f)
        val expected = rotateAround(ownRotated, group.bounds().center, 90f)
        assertPointEquals(
            expected,
            listOf(group).elementRenderedWorldPoint(text.id, text.topLeft),
        )
        // The ancestor-only helper deliberately does not rotate the corner.
        assertEquals(
            text.topLeft,
            listOf(text).elementWorldPoint(text.id, text.topLeft),
        )
    }

    private fun assertPointEquals(expected: Offset, actual: Offset, tolerance: Float = 0.001f) {
        assertTrue(
            abs(expected.x - actual.x) <= tolerance &&
                abs(expected.y - actual.y) <= tolerance,
            "expected $expected but was $actual",
        )
    }
}
