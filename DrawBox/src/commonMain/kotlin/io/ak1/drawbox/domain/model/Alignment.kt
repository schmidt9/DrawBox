package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Smart alignment snapping used by the SELECT-mode move and resize drags.
 *
 * Everything here is pure geometry in world space so the gesture layer can stay
 * thin and the rules stay unit-testable. Guides are transient interaction chrome;
 * they are never stored in [State], serialized, or exported.
 */

internal enum class AlignmentGuideOrientation { Vertical, Horizontal }

/**
 * One dotted guide line. [position] is the world x of a vertical line or the
 * world y of a horizontal line; [start]/[end] are the span along the
 * perpendicular axis.
 */
internal data class AlignmentGuide(
    val orientation: AlignmentGuideOrientation,
    val position: Float,
    val start: Float,
    val end: Float,
)

internal data class AlignmentSnap(
    val correction: Offset,
    val guides: List<AlignmentGuide>,
) {
    companion object {
        val None = AlignmentSnap(Offset.Zero, emptyList())
    }
}

/**
 * Move-snap bookkeeping. [baseBounds] is the *unsnapped* cumulative bounds so a
 * snap can be released exactly; [lastCorrection] is the correction already
 * reflected in the emitted deltas.
 */
internal data class MoveSnapState(
    val baseBounds: Rect,
    val lastCorrection: Offset = Offset.Zero,
)

internal data class MoveSnapStep(
    val emitDelta: Offset,
    val next: MoveSnapState,
    val guides: List<AlignmentGuide>,
)

internal data class ResizeSnapResult(
    val parentPointerCorrection: Offset,
    val guides: List<AlignmentGuide>,
)

// Same feature index matches itself only: left↔left, center↔center, right↔right.
private const val MIN_RESIZE_SLOPE = 0.05f
private const val MAX_RESIZE_CORRECTION_FACTOR = 4f

// ==================== Target collection ====================

internal fun unionRects(rects: Iterable<Rect>): Rect? {
    val iterator = rects.iterator()
    if (!iterator.hasNext()) return null
    var result = iterator.next()
    while (iterator.hasNext()) {
        val other = iterator.next()
        result = Rect(
            left = min(result.left, other.left),
            top = min(result.top, other.top),
            right = max(result.right, other.right),
            bottom = max(result.bottom, other.bottom),
        )
    }
    return result
}

/** [roots] plus every descendant, for excluding a moved/resized subtree. */
internal fun List<Element>.subtreeIds(roots: Set<String>): Set<String> {
    if (roots.isEmpty()) return emptySet()
    val result = mutableSetOf<String>()
    fun visit(element: Element, inside: Boolean) {
        val within = inside || element.id in roots
        if (within) result += element.id
        if (element is Element.Group) {
            element.children.forEach { visit(it, inside = within) }
        }
    }
    forEach { visit(it, inside = false) }
    return result
}

/**
 * Subtrees of every rotated ancestor group of [ids]. Moving one child of a
 * rotated group shifts the group's bounds center (its rotation pivot), which
 * visually moves siblings — so those siblings are unreliable targets.
 */
internal fun List<Element>.rotatedAncestorSubtreeIds(ids: Set<String>): Set<String> {
    val roots = mutableSetOf<String>()
    ids.forEach { id ->
        pathToElement(id)?.dropLast(1)?.forEach { ancestor ->
            if (ancestor is Element.Group && ancestor.rotation != 0f) {
                roots += ancestor.id
            }
        }
    }
    return subtreeIds(roots)
}

internal fun List<Element>.hasRotatedAncestor(id: String): Boolean =
    pathToElement(id)?.dropLast(1)?.any {
        it is Element.Group && it.rotation != 0f
    } == true

/** Rotation-aware world AABB of the whole moving selection. */
internal fun List<Element>.alignmentMovingBounds(ids: Set<String>): Rect? =
    unionRects(ids.mapNotNull { elementWorldAabb(it) })

/**
 * World AABBs of alignment targets: non-group leaves, excluding the given
 * subtree/hidden ids and arrows bound to a moving element. Groups are not
 * targets on their own; their leaves take part individually.
 */
internal fun List<Element>.alignmentTargetRects(
    excludedSubtreeIds: Set<String>,
    hiddenIds: Set<String>,
    movingIds: Set<String>,
): List<Rect> {
    val hiddenSubtrees = subtreeIds(hiddenIds)
    return flattenElements()
        .filter { it !is Element.Group }
        .filter { it.id !in excludedSubtreeIds && it.id !in hiddenSubtrees }
        .filter { leaf ->
            val arrow = leaf as? Element.Shape
            if (arrow == null || arrow.shapeType != ShapeType.ARROW) {
                true
            } else {
                val boundToMoving =
                    (arrow.startBinding != null && arrow.startBinding in movingIds) ||
                        (arrow.endBinding != null && arrow.endBinding in movingIds)
                !boundToMoving
            }
        }
        .mapNotNull { elementWorldAabb(it.id) }
}

// ==================== Move snapping ====================

private fun xFeatures(rect: Rect): List<Float> =
    listOf(rect.left, rect.center.x, rect.right)

private fun yFeatures(rect: Rect): List<Float> =
    listOf(rect.top, rect.center.y, rect.bottom)

private data class AxisSnap(
    val delta: Float,
    val linePosition: Float,
    val targetIndex: Int,
    val featureKind: Int,
    val perpendicularGap: Float,
)

private fun isBetter(candidate: AxisSnap, current: AxisSnap): Boolean {
    val candidateDelta = abs(candidate.delta)
    val currentDelta = abs(current.delta)
    if (candidateDelta != currentDelta) return candidateDelta < currentDelta
    if (candidate.perpendicularGap != current.perpendicularGap) {
        return candidate.perpendicularGap < current.perpendicularGap
    }
    if (candidate.targetIndex != current.targetIndex) {
        return candidate.targetIndex < current.targetIndex
    }
    return candidate.featureKind < current.featureKind
}

private fun bestAxisSnap(
    movingValues: List<Float>,
    targets: List<Rect>,
    threshold: Float,
    movingPerpendicularCenter: Float,
    targetFeatures: (Rect) -> List<Float>,
    perpendicularCenter: (Rect) -> Float,
): AxisSnap? {
    var best: AxisSnap? = null
    targets.forEachIndexed { index, target ->
        val values = targetFeatures(target)
        val gap = abs(movingPerpendicularCenter - perpendicularCenter(target))
        for (kind in movingValues.indices) {
            val delta = values[kind] - movingValues[kind]
            if (abs(delta) > threshold) continue
            val candidate = AxisSnap(
                delta = delta,
                linePosition = values[kind],
                targetIndex = index,
                featureKind = kind,
                perpendicularGap = gap,
            )
            val current = best
            if (current == null || isBetter(candidate, current)) {
                best = candidate
            }
        }
    }
    return best
}

/**
 * Snap the moving AABB's sides/center to the same feature of nearby targets on
 * each axis independently. The returned correction applies both axes at once.
 */
internal fun snapMovingBounds(
    moving: Rect,
    targets: List<Rect>,
    threshold: Float,
    mergeEpsilon: Float,
): AlignmentSnap {
    if (targets.isEmpty() || threshold <= 0f) return AlignmentSnap.None

    val xSnap = bestAxisSnap(
        movingValues = xFeatures(moving),
        targets = targets,
        threshold = threshold,
        movingPerpendicularCenter = moving.center.y,
        targetFeatures = ::xFeatures,
        perpendicularCenter = { it.center.y },
    )
    val ySnap = bestAxisSnap(
        movingValues = yFeatures(moving),
        targets = targets,
        threshold = threshold,
        movingPerpendicularCenter = moving.center.x,
        targetFeatures = ::yFeatures,
        perpendicularCenter = { it.center.x },
    )
    if (xSnap == null && ySnap == null) return AlignmentSnap.None

    val correction = Offset(xSnap?.delta ?: 0f, ySnap?.delta ?: 0f)
    val snapped = moving.translate(correction)
    val guides = buildList {
        xSnap?.let { snap ->
            buildGuide(
                orientation = AlignmentGuideOrientation.Vertical,
                linePosition = snap.linePosition,
                moving = snapped,
                targets = targets,
                mergeEpsilon = mergeEpsilon,
            )?.let(::add)
        }
        ySnap?.let { snap ->
            buildGuide(
                orientation = AlignmentGuideOrientation.Horizontal,
                linePosition = snap.linePosition,
                moving = snapped,
                targets = targets,
                mergeEpsilon = mergeEpsilon,
            )?.let(::add)
        }
    }
    return AlignmentSnap(correction, guides)
}

/**
 * One move tick. The emitted delta compensates for a previous snap so the
 * cumulative position equals the raw drag path plus the *current* correction.
 */
internal fun stepMoveSnap(
    state: MoveSnapState,
    rawDelta: Offset,
    targets: List<Rect>,
    threshold: Float,
    mergeEpsilon: Float,
): MoveSnapStep {
    val candidate = state.baseBounds.translate(rawDelta)
    val snap = snapMovingBounds(candidate, targets, threshold, mergeEpsilon)
    return MoveSnapStep(
        emitDelta = rawDelta + snap.correction - state.lastCorrection,
        next = MoveSnapState(candidate, snap.correction),
        guides = snap.guides,
    )
}

// ==================== Guide building ====================

private fun buildGuide(
    orientation: AlignmentGuideOrientation,
    linePosition: Float,
    moving: Rect,
    targets: List<Rect>,
    mergeEpsilon: Float,
): AlignmentGuide? {
    val vertical = orientation == AlignmentGuideOrientation.Vertical
    var start = if (vertical) moving.top else moving.left
    var end = if (vertical) moving.bottom else moving.right

    targets.forEach { target ->
        val features = if (vertical) xFeatures(target) else yFeatures(target)
        if (features.any { abs(it - linePosition) <= mergeEpsilon }) {
            start = min(start, if (vertical) target.top else target.left)
            end = max(end, if (vertical) target.bottom else target.right)
        }
    }
    if (end - start <= 0f) return null
    return AlignmentGuide(orientation, linePosition, start, end)
}

// ==================== Resize snapping ====================

private data class ResizeAxisSnap(
    val pointerDelta: Float,
    val linePosition: Float,
    val isCenter: Boolean,
)

/**
 * Snap a resize drag in candidate-AABB space.
 *
 * [aabbOf] maps a pointer (in the element's parent frame; equal to world when
 * there is no rotated ancestor) to the AABB the resize would produce. [edgeX] /
 * [edgeY] are the moving edge directions from the handle (‑1, 0 or +1). The
 * feature delta is divided by the feature's slope along the pointer axis, so a
 * center snap moves the pointer further than an edge snap and rotated AABBs,
 * circles, text, and text-containing groups are handled uniformly.
 */
internal fun snapResizePointer(
    rawPointer: Offset,
    aabbOf: (Offset) -> Rect,
    edgeX: Int,
    edgeY: Int,
    targets: List<Rect>,
    threshold: Float,
    mergeEpsilon: Float,
): ResizeSnapResult {
    if (targets.isEmpty() || threshold <= 0f || (edgeX == 0 && edgeY == 0)) {
        return ResizeSnapResult(Offset.Zero, emptyList())
    }

    var pointer = rawPointer
    val xSnap = solveResizeAxis(
        pointer = pointer,
        aabbOf = aabbOf,
        axisX = true,
        edge = edgeX,
        targets = targets,
        threshold = threshold,
    )
    if (xSnap != null) pointer += Offset(xSnap.pointerDelta, 0f)

    val ySnap = solveResizeAxis(
        pointer = pointer,
        aabbOf = aabbOf,
        axisX = false,
        edge = edgeY,
        targets = targets,
        threshold = threshold,
    )
    if (ySnap != null) pointer += Offset(0f, ySnap.pointerDelta)

    val finalAabb = aabbOf(pointer)
    val guides = buildList {
        xSnap?.let { snap ->
            val finalFeature = resizeFeature(finalAabb, axisX = true, edge = edgeX,
                isCenter = snap.isCenter)
            if (abs(finalFeature - snap.linePosition) <= max(mergeEpsilon, 0.5f)) {
                buildGuide(
                    orientation = AlignmentGuideOrientation.Vertical,
                    linePosition = snap.linePosition,
                    moving = finalAabb,
                    targets = targets,
                    mergeEpsilon = mergeEpsilon,
                )?.let(::add)
            }
        }
        ySnap?.let { snap ->
            val finalFeature = resizeFeature(finalAabb, axisX = false, edge = edgeY,
                isCenter = snap.isCenter)
            if (abs(finalFeature - snap.linePosition) <= max(mergeEpsilon, 0.5f)) {
                buildGuide(
                    orientation = AlignmentGuideOrientation.Horizontal,
                    linePosition = snap.linePosition,
                    moving = finalAabb,
                    targets = targets,
                    mergeEpsilon = mergeEpsilon,
                )?.let(::add)
            }
        }
    }
    return ResizeSnapResult(pointer - rawPointer, guides)
}

private fun solveResizeAxis(
    pointer: Offset,
    aabbOf: (Offset) -> Rect,
    axisX: Boolean,
    edge: Int,
    targets: List<Rect>,
    threshold: Float,
): ResizeAxisSnap? {
    if (edge == 0) return null
    val step = 1f
    val stepOffset = if (axisX) Offset(step, 0f) else Offset(0f, step)
    val raw = aabbOf(pointer)
    val plus = aabbOf(pointer + stepOffset)
    val minus = aabbOf(pointer - stepOffset)

    val edgeRaw = resizeFeature(raw, axisX, edge, isCenter = false)
    val centerRaw = resizeFeature(raw, axisX, edge, isCenter = true)
    val edgeSlope = (resizeFeature(plus, axisX, edge, false) -
        resizeFeature(minus, axisX, edge, false)) / (2f * step)
    val centerSlope = (resizeFeature(plus, axisX, edge, true) -
        resizeFeature(minus, axisX, edge, true)) / (2f * step)

    var best: ResizeAxisSnap? = null
    var bestDelta = Float.MAX_VALUE
    var bestGap = Float.MAX_VALUE
    var bestIndex = Int.MAX_VALUE
    var bestCenter = false

    targets.forEachIndexed { index, target ->
        val targetEdge = resizeFeature(target, axisX, edge, isCenter = false)
        val targetCenter = resizeFeature(target, axisX, edge, isCenter = true)
        val gap = if (axisX) {
            abs(raw.center.y - target.center.y)
        } else {
            abs(raw.center.x - target.center.x)
        }

        fun consider(targetValue: Float, rawValue: Float, slope: Float, isCenter: Boolean) {
            val delta = targetValue - rawValue
            if (abs(delta) > threshold) return
            if (abs(slope) < MIN_RESIZE_SLOPE) return
            val pointerDelta = delta / slope
            if (abs(pointerDelta) > MAX_RESIZE_CORRECTION_FACTOR * threshold) return
            val better = abs(delta) < bestDelta ||
                (abs(delta) == bestDelta && (
                    gap < bestGap ||
                        (gap == bestGap && (
                            index < bestIndex ||
                                (index == bestIndex && !isCenter && bestCenter)
                            ))
                    ))
            if (better) {
                bestDelta = abs(delta)
                bestGap = gap
                bestIndex = index
                bestCenter = isCenter
                best = ResizeAxisSnap(
                    pointerDelta = pointerDelta,
                    linePosition = targetValue,
                    isCenter = isCenter,
                )
            }
        }

        consider(targetEdge, edgeRaw, edgeSlope, isCenter = false)
        consider(targetCenter, centerRaw, centerSlope, isCenter = true)
    }
    return best
}

private fun resizeFeature(
    rect: Rect,
    axisX: Boolean,
    edge: Int,
    isCenter: Boolean,
): Float = when {
    axisX && isCenter -> rect.center.x
    axisX && edge > 0 -> rect.right
    axisX && edge < 0 -> rect.left
    !axisX && isCenter -> rect.center.y
    !axisX && edge > 0 -> rect.bottom
    else -> rect.top
}
