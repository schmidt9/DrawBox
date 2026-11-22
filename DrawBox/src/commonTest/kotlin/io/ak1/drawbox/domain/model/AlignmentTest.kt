package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlignmentTest {

    private fun rect(
        id: String,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        rotation: Float = 0f,
        z: Int = 0,
    ): Element.Shape = Element.Shape(
        id = id,
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset(l, t), Offset(r, b)),
        strokeColor = Color.Black,
        strokeWidth = 2f,
        zIndex = z,
        rotation = rotation,
    )

    private fun assertNear(expected: Float, actual: Float, eps: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")
    }

    private fun assertOffsetNear(expected: Offset, actual: Offset, eps: Float = 0.01f) {
        assertNear(expected.x, actual.x, eps)
        assertNear(expected.y, actual.y, eps)
    }

    // ==================== Move snapping ====================

    @Test
    fun moveSnapsSameEdgeAndDrawsSpanningGuide() {
        val target = Rect(100f, 0f, 200f, 100f)
        val moving = Rect(103f, 200f, 253f, 300f)

        val snap = snapMovingBounds(moving, listOf(target), threshold = 6f, mergeEpsilon = 0.5f)

        assertOffsetNear(Offset(-3f, 0f), snap.correction)
        val guide = snap.guides.single()
        assertEquals(AlignmentGuideOrientation.Vertical, guide.orientation)
        assertNear(100f, guide.position)
        assertNear(0f, guide.start)
        assertNear(300f, guide.end)
    }

    @Test
    fun moveSnapsCenterOnXAndEdgeOnYSimultaneously() {
        val target = Rect(100f, 100f, 200f, 200f)
        val moving = Rect(52f, 103f, 252f, 303f)

        val snap = snapMovingBounds(moving, listOf(target), threshold = 6f, mergeEpsilon = 0.5f)

        assertOffsetNear(Offset(-2f, -3f), snap.correction)
        assertEquals(2, snap.guides.size)
        val vertical = snap.guides.first { it.orientation == AlignmentGuideOrientation.Vertical }
        val horizontal = snap.guides.first { it.orientation == AlignmentGuideOrientation.Horizontal }
        assertNear(150f, vertical.position) // center x
        assertNear(100f, horizontal.position) // top edge
    }

    @Test
    fun thresholdIsInclusiveAndJustPastIsRejected() {
        val target = Rect(100f, 0f, 200f, 100f)

        val inclusive = snapMovingBounds(
            Rect(106f, 300f, 406f, 400f),
            listOf(target),
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )
        assertOffsetNear(Offset(-6f, 0f), inclusive.correction)

        val outside = snapMovingBounds(
            Rect(106.01f, 300f, 406.01f, 400f),
            listOf(target),
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )
        assertEquals(AlignmentSnap.None, outside)
    }

    @Test
    fun moveNeverSnapsLeftEdgeOntoRightEdge() {
        val target = Rect(100f, 0f, 200f, 100f)
        // Left edge sits 3px from the target's right edge, but feature kinds differ.
        val moving = Rect(197f, 300f, 297f, 400f)

        val snap = snapMovingBounds(moving, listOf(target), threshold = 6f, mergeEpsilon = 0.5f)

        assertEquals(AlignmentSnap.None, snap)
    }

    @Test
    fun rotatedTargetSnapsByWorldAabb() {
        val target = rect("target", 0f, 0f, 100f, 100f, rotation = 45f)
        val elements = listOf(target)
        val targets = elements.alignmentTargetRects(
            excludedSubtreeIds = emptySet(),
            hiddenIds = emptySet(),
            movingIds = emptySet(),
        )
        val half = 50f * sqrt(2f)
        val aabbLeft = 50f - half

        val moving = Rect(aabbLeft + 2f, 300f, aabbLeft + 102f, 400f)
        val snap = snapMovingBounds(moving, targets, threshold = 6f, mergeEpsilon = 0.5f)

        assertNear(-2f, snap.correction.x)
        assertNear(0f, snap.correction.y)
    }

    @Test
    fun multiSelectionUsesUnionBounds() {
        val elements = listOf(
            rect("a", 0f, 0f, 100f, 100f),
            rect("b", 200f, 0f, 300f, 100f),
        )

        val bounds = elements.alignmentMovingBounds(setOf("a", "b"))

        assertEquals(Rect(0f, 0f, 300f, 100f), bounds)
    }

    @Test
    fun guideMergesCoLinearTargetsIntoOneSpan() {
        val moving = Rect(0f, 0f, 100f, 100f)
        val targets = listOf(
            Rect(0f, -50f, 100f, 50f),
            Rect(0f, 200f, 100f, 300f),
        )

        val snap = snapMovingBounds(moving, targets, threshold = 6f, mergeEpsilon = 0.5f)

        assertOffsetNear(Offset.Zero, snap.correction)
        val guide = snap.guides.single()
        assertEquals(AlignmentGuideOrientation.Vertical, guide.orientation)
        assertNear(0f, guide.position)
        assertNear(-50f, guide.start)
        assertNear(300f, guide.end)
    }

    @Test
    fun stepMoveSnapTelescopesAndReleasesExactly() {
        val initial = Rect(0f, 0f, 100f, 100f)
        val target = Rect(5f, 200f, 105f, 300f)
        var state = MoveSnapState(initial)
        var total = Offset.Zero
        var guides: List<AlignmentGuide> = emptyList()

        listOf(Offset(2f, 0f), Offset(1f, 0f), Offset(0.5f, 0f)).forEach { delta ->
            val step = stepMoveSnap(state, delta, listOf(target), 6f, 0.5f)
            total += step.emitDelta
            state = step.next
            guides = step.guides
        }

        val applied = initial.translate(total)
        assertNear(5f, applied.left) // snapped to the target's left edge
        assertEquals(1, guides.size)

        val released = stepMoveSnap(state, Offset(20f, 0f), listOf(target), 6f, 0.5f)
        val releasedApplied = initial.translate(total + released.emitDelta)
        assertNear(23.5f, releasedApplied.left) // raw path: 3.5 + 20
        assertTrue(released.guides.isEmpty())
    }

    // ==================== Target exclusion ====================

    @Test
    fun targetsExcludeSelectedSubtreeHiddenIdsAndBoundArrows() {
        val child = rect("child", 10f, 10f, 50f, 50f)
        val group = Element.Group(id = "g", children = listOf(child), rotation = 45f)
        val sibling = rect("sib", 60f, 10f, 90f, 50f)
        val hidden = rect("hidden", 400f, 400f, 500f, 500f)
        val arrow = Element.Shape(
            id = "arrow",
            shapeType = ShapeType.ARROW,
            points = listOf(Offset(0f, 0f), Offset(10f, 10f)),
            strokeColor = Color.Black,
            strokeWidth = 2f,
            startBinding = "moving",
        )
        val other = rect("other", 600f, 0f, 700f, 100f)
        val elements = listOf(group, sibling, hidden, arrow, other)

        val targets = elements.alignmentTargetRects(
            excludedSubtreeIds = elements.subtreeIds(setOf("g")),
            hiddenIds = setOf("hidden"),
            movingIds = setOf("moving"),
        )

        assertEquals(2, targets.size) // sibling + other only
        assertTrue(elements.hasRotatedAncestor("child"))
        assertFalse(elements.hasRotatedAncestor("sib"))
        assertTrue("g" in elements.rotatedAncestorSubtreeIds(setOf("child")))
        assertTrue("child" in elements.rotatedAncestorSubtreeIds(setOf("child")))
    }

    // ==================== Resize snapping ====================

    @Test
    fun resizeRightEdgeSnapsToSameEdge() {
        val targets = listOf(Rect(50f, 300f, 150f, 400f))
        val result = snapResizePointer(
            rawPointer = Offset(147f, 50f),
            aabbOf = { p -> Rect(0f, 0f, p.x, 100f) },
            edgeX = 1,
            edgeY = 0,
            targets = targets,
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )

        assertNear(3f, result.parentPointerCorrection.x)
        assertNear(0f, result.parentPointerCorrection.y)
        val guide = result.guides.single()
        assertEquals(AlignmentGuideOrientation.Vertical, guide.orientation)
        assertNear(150f, guide.position)
    }

    @Test
    fun resizeCenterSnapNeedsDoublePointerMove() {
        val targets = listOf(Rect(30f, 300f, 130f, 400f)) // center x = 80
        val raw = Offset(157f, 50f) // raw center x = 78.5

        val result = snapResizePointer(
            rawPointer = raw,
            aabbOf = { p -> Rect(0f, 0f, p.x, 100f) },
            edgeX = 1,
            edgeY = 0,
            targets = targets,
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )

        assertNear(3f, result.parentPointerCorrection.x)
        assertNear(80f, (raw.x + result.parentPointerCorrection.x) / 2f)
    }

    @Test
    fun resizeEdgeHandleIgnoresIdleAxis() {
        val result = snapResizePointer(
            rawPointer = Offset(50f, 3f),
            aabbOf = { _ -> Rect(0f, 0f, 100f, 100f) },
            edgeX = 1,
            edgeY = 0,
            targets = listOf(Rect(-100f, 0f, 10f, 100f)),
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )

        assertOffsetNear(Offset.Zero, result.parentPointerCorrection)
        assertTrue(result.guides.isEmpty())
    }

    @Test
    fun resizeRotatedElementLandsAabbEdgeOnGuide() {
        val shape = rect("rotated", 0f, 0f, 100f, 100f, rotation = 45f)
        val handle = ResizeHandle.Right
        val aabbOf: (Offset) -> Rect = { p ->
            shape.withBounds(resizeBoundsForElement(shape, handle, p)).visualAabb()
        }
        val raw = Offset(100f, 50f)
        val rawAabb = aabbOf(raw)
        val targetRight = rawAabb.right + 3f
        val targets = listOf(
            Rect(targetRight - 500f, rawAabb.top - 500f, targetRight, rawAabb.top - 100f),
        )

        val result = snapResizePointer(
            rawPointer = raw,
            aabbOf = aabbOf,
            edgeX = 1,
            edgeY = 0,
            targets = targets,
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )

        val finalAabb = aabbOf(raw + result.parentPointerCorrection)
        assertNear(targetRight, finalAabb.right, 0.5f)
        assertTrue(result.guides.isNotEmpty())
    }

    @Test
    fun textResizeSnapsWidthButNotIgnoredBottom() {
        val text = Element.Text(
            id = "text",
            text = "Hello",
            fontSize = 16f,
            color = Color.Black,
            topLeft = Offset(0f, 0f),
            wrapWidth = 100f,
            measuredHeight = 50f,
        )
        val rightAabb: (Offset) -> Rect = { p ->
            text.withBounds(resizeBoundsForElement(text, ResizeHandle.Right, p)).visualAabb()
        }
        val rightSnap = snapResizePointer(
            rawPointer = Offset(197f, 0f),
            aabbOf = rightAabb,
            edgeX = 1,
            edgeY = 0,
            targets = listOf(Rect(100f, 300f, 200f, 400f)),
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )
        val snappedRight = rightAabb(Offset(197f + rightSnap.parentPointerCorrection.x, 0f))
        assertNear(200f, snappedRight.right)

        val bottomAabb: (Offset) -> Rect = { p ->
            text.withBounds(resizeBoundsForElement(text, ResizeHandle.Bottom, p)).visualAabb()
        }
        val bottomSnap = snapResizePointer(
            rawPointer = Offset(50f, 53f),
            aabbOf = bottomAabb,
            edgeX = 0,
            edgeY = 1,
            targets = listOf(Rect(0f, 0f, 100f, 53f)),
            threshold = 6f,
            mergeEpsilon = 0.5f,
        )
        assertOffsetNear(Offset.Zero, bottomSnap.parentPointerCorrection)
    }
}
