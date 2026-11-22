package io.ak1.drawbox.domain.usecase

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ResizeHandle
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.StrokeStyle
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.bakedIntoParent
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.childrenAt
import io.ak1.drawbox.domain.model.connectorAnchor
import io.ak1.drawbox.domain.model.elementParentFrameVector
import io.ak1.drawbox.domain.model.elementWorldAabb
import io.ak1.drawbox.domain.model.findElementById
import io.ak1.drawbox.domain.model.hitTest
import io.ak1.drawbox.domain.model.mapElementById
import io.ak1.drawbox.domain.model.removeElementsByIds
import io.ak1.drawbox.domain.model.replaceScopeChildren
import io.ak1.drawbox.domain.model.resizeBounds
import io.ak1.drawbox.domain.model.topmostHit
import io.ak1.drawbox.domain.model.touched
import io.ak1.drawbox.domain.model.translate
import io.ak1.drawbox.domain.model.visualAabb
import io.ak1.drawbox.domain.model.withBounds
import io.ak1.drawbox.domain.model.withRotation
import io.ak1.drawbox.domain.model.withZIndex
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.Uuid

/** Result of [UseCase.duplicateSelected]. */
data class DuplicateResult(
    val elements: List<Element>,
    val selectedIds: Set<String>,
)

class UseCase {
    // Element operations
    fun addElement(element: Element, currentElements: List<Element>): List<Element> {
        val newElement = when (element) {
            is Element.Path -> element.copy(zIndex = currentElements.size)
            is Element.Shape -> element.copy(zIndex = currentElements.size)
            is Element.Image -> element.copy(zIndex = currentElements.size)
            is Element.Text -> element.copy(zIndex = currentElements.size)
            is Element.Group -> element.copy(zIndex = currentElements.size)
        }
        return currentElements + newElement
    }

    fun updateElement(element: Element, currentElements: List<Element>): List<Element> {
        return currentElements.mapElementById(element.id) { element }
    }

    fun deleteElement(elementId: String, currentElements: List<Element>): List<Element> {
        return currentElements.removeElementsByIds(setOf(elementId))
    }

    // Path operations
    @OptIn(ExperimentalTime::class)
    fun insertNewPath(
        offset: Offset,
        color: Color,
        width: Float,
        alpha: Float,
        strokeStyle: StrokeStyle = StrokeStyle.SOLID,
        pressure: Float = 1f,
    ): Element.Path {
        val now = Clock.System.now().toEpochMilliseconds()
        return Element.Path(
            samples = listOf(Element.PathSample(offset, width * pressure)),
            strokeColor = color,
            strokeWidth = width,
            alpha = alpha,
            strokeStyle = strokeStyle,
            createdAt = now,
            modifiedAt = now,
        )
    }

    fun updateLatestPath(
        newPoint: Offset,
        currentElements: List<Element>,
        pressure: Float = 1f,
    ): List<Element> {
        if (currentElements.isEmpty()) return currentElements

        val lastElement = currentElements.last()
        return if (lastElement is Element.Path) {
            // Decimate sub-pixel samples: a pen drag at 60 Hz easily produces points
            // with sub-world-pixel spacing. The visual difference is invisible but
            // every extra sample grows the Path we rebuild during the active stroke.
            val lastSample = lastElement.samples.lastOrNull()
            if (lastSample != null) {
                val dx = newPoint.x - lastSample.position.x
                val dy = newPoint.y - lastSample.position.y
                if (dx * dx + dy * dy < MIN_PEN_POINT_DIST_SQ) return currentElements
            }
            val newWidth = lastElement.strokeWidth * pressure
            val newSample = Element.PathSample(newPoint, newWidth)
            // The seed sample (inserted by InsertNewPath on tap / drag-start) was
            // created without a pressure reading — Compose's tap and drag-start
            // callbacks don't expose pointer pressure, only position. That seed
            // sits at full strokeWidth while the next sample arrives modulated
            // by pen pressure, which renders as a visible "bigger dot" at the
            // stroke origin. On the first update tick, retroactively conform the
            // seed's width to this tick's pressure so the start cap matches the
            // rest of the stroke.
            val updatedSamples = if (lastElement.samples.size == 1 &&
                lastElement.samples[0].width != newWidth
            ) {
                listOf(lastElement.samples[0].copy(width = newWidth), newSample)
            } else {
                lastElement.samples + newSample
            }
            currentElements.dropLast(1) + lastElement.copy(
                samples = updatedSamples,
            ).touched()
        } else {
            currentElements
        }
    }

    // Shape operations
    @OptIn(ExperimentalTime::class)
    fun insertNewShape(
        shapeType: ShapeType,
        offset: Offset,
        color: Color,
        width: Float,
        cornerRadius: Float = 0f,
        strokeStyle: StrokeStyle = StrokeStyle.SOLID,
        fillColor: Color? = null,
        strokeEnabled: Boolean = true,
    ): Element.Shape {
        val now = Clock.System.now().toEpochMilliseconds()
        return Element.Shape(
            shapeType = shapeType,
            points = listOf(offset),
            strokeColor = color,
            strokeWidth = width,
            cornerRadius = cornerRadius,
            strokeStyle = strokeStyle,
            fillColor = fillColor,
            strokeEnabled = strokeEnabled,
            createdAt = now,
            modifiedAt = now,
        )
    }

    @OptIn(ExperimentalTime::class)
    fun insertNewPolygon(
        offset: Offset,
        color: Color,
        width: Float,
        strokeStyle: StrokeStyle = StrokeStyle.SOLID,
        fillColor: Color? = null,
        strokeEnabled: Boolean = true,
    ): Element.Shape = insertNewShape(
        shapeType = ShapeType.POLYGON,
        offset = offset,
        color = color,
        width = width,
        strokeStyle = strokeStyle,
        fillColor = fillColor,
        strokeEnabled = strokeEnabled,
    )

    // Text operations
    @OptIn(ExperimentalTime::class)
    fun insertText(
        text: String,
        position: Offset,
        fontSize: Float,
        fontFamilyKey: String,
        alignment: TextAlignment,
        color: Color,
    ): Element.Text {
        val now = Clock.System.now().toEpochMilliseconds()
        // Initial guess: a single-line block at fontSize*1.2 tall. The
        // renderer dispatches Intent.SyncTextMeasuredHeight after the first
        // layout pass, so this value is only ever visible for one frame.
        return Element.Text(
            text = text,
            fontFamilyKey = fontFamilyKey,
            fontSize = fontSize,
            color = color,
            alignment = alignment,
            topLeft = position,
            wrapWidth = DEFAULT_TEXT_BOX_WIDTH,
            measuredHeight = (fontSize * 1.2f).coerceAtLeast(fontSize),
            createdAt = now,
            modifiedAt = now,
        )
    }

    /**
     * Renderer-driven height sync — the canvas measures a text block, sees
     * the rendered height differs from [Element.Text.measuredHeight] and
     * dispatches [io.ak1.drawbox.domain.model.Intent.SyncTextMeasuredHeight]
     * to bring the model in line. Bypasses history so this fixed-point
     * convergence doesn't consume undo slots.
     */
    fun syncTextMeasuredHeight(
        elements: List<Element>,
        id: String,
        height: Float,
    ): List<Element> = elements.mapElementById(id) { el ->
        if (el is Element.Text) el.copy(measuredHeight = height) else el
    }

    /** Replace the [text] field of a single [Element.Text]. Snapshots history. */
    fun updateText(
        elements: List<Element>,
        id: String,
        text: String,
    ): List<Element> = elements.mapElementById(id) { el ->
        if (el is Element.Text) el.copy(text = text).touched() else el
    }

    /** Set font size on every selected [Element.Text]. */
    fun setSelectedFontSize(
        elements: List<Element>,
        ids: Set<String>,
        size: Float,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        if (el is Element.Text) el.copy(fontSize = size).touched() else el
    }

    /** Set text alignment on every selected [Element.Text]. */
    fun setSelectedTextAlignment(
        elements: List<Element>,
        ids: Set<String>,
        alignment: TextAlignment,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        if (el is Element.Text) el.copy(alignment = alignment).touched() else el
    }

    /** Set font family key on every selected [Element.Text]. */
    fun setSelectedFontFamily(
        elements: List<Element>,
        ids: Set<String>,
        fontFamilyKey: String,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        if (el is Element.Text) el.copy(fontFamilyKey = fontFamilyKey).touched() else el
    }

    // Image operations
    @OptIn(ExperimentalTime::class)
    fun insertImage(
        bytes: ByteArray,
        position: Offset,
        intrinsicSize: Size,
    ): Element.Image {
        val now = Clock.System.now().toEpochMilliseconds()
        // Fit the placed image into a sensible default extent: clamp the longer
        // side to MAX_PLACED_EXTENT world pixels and preserve aspect ratio.
        // Hosts that want the source size verbatim can resize via
        // SetElementBounds immediately after dispatch.
        val intrinsicW = intrinsicSize.width.coerceAtLeast(1f)
        val intrinsicH = intrinsicSize.height.coerceAtLeast(1f)
        val longer = maxOf(intrinsicW, intrinsicH)
        val scale = if (longer > MAX_PLACED_EXTENT) MAX_PLACED_EXTENT / longer else 1f
        val placedW = intrinsicW * scale
        val placedH = intrinsicH * scale
        // Anchor the AABB on [position]'s center so the drop point lands in the
        // middle of the placed image — matches the user's mental model when
        // dropping or pasting at the cursor.
        val topLeft = Offset(position.x - placedW * 0.5f, position.y - placedH * 0.5f)
        val bottomRight = Offset(topLeft.x + placedW, topLeft.y + placedH)
        return Element.Image(
            bytes = bytes,
            intrinsicSize = intrinsicSize,
            points = listOf(topLeft, bottomRight),
            createdAt = now,
            modifiedAt = now,
        )
    }

    fun updateLatestShape(newPoint: Offset, currentElements: List<Element>): List<Element> {
        if (currentElements.isEmpty()) return currentElements

        val lastElement = currentElements.last()
        return if (lastElement is Element.Shape) {
            // Shapes use exactly two points (start + current end). Appending on
            // every drag tick — as the original code did — bloats `points` with
            // the cursor's whole path, leaving bounds() bigger than the rendered
            // shape and ballooning serialized JSON. Replace the second slot.
            val start = lastElement.points.firstOrNull() ?: newPoint
            val updatedPoints = listOf(start, newPoint)
            currentElements.dropLast(1) + lastElement.copy(points = updatedPoints).touched()
        } else {
            currentElements
        }
    }

    fun appendPolygonPoint(
        elements: List<Element>,
        id: String,
        point: Offset,
    ): List<Element> = elements.map { el ->
        if (el.id == id && el is Element.Shape && el.shapeType == ShapeType.POLYGON) {
            val last = el.points.lastOrNull()
            if (last != null && (last - point).getDistance() < POLYGON_POINT_EPSILON) {
                el
            } else {
                el.copy(points = el.points + point).touched()
            }
        } else {
            el
        }
    }

    fun updateLatestPolygonPoint(
        elements: List<Element>,
        id: String,
        point: Offset,
    ): List<Element> = elements.map { el ->
        if (el.id == id && el is Element.Shape && el.shapeType == ShapeType.POLYGON) {
            // The first drag starts from a one-vertex polygon. Append the
            // moving endpoint instead of replacing the start vertex; once a
            // second vertex exists, subsequent updates move that endpoint.
            if (el.points.size < 2) {
                el.copy(points = el.points + point).touched()
            } else {
                el.copy(points = el.points.dropLast(1) + point).touched()
            }
        } else {
            el
        }
    }

    // Selection operations

    /** Topmost element at `point`, or null if nothing was hit. */
    fun hitTopmost(elements: List<Element>, point: Offset, tolerance: Float): Element? =
        topmostHit(elements, point, tolerance)

    /**
     * Set of direct-scope element IDs whose rendered bounds intersect `rect`.
     * [groupPath] scopes the selection to the entered group's children; their
     * world AABBs account for ancestor rotations.
     */
    fun selectInRect(
        elements: List<Element>,
        rect: Rect,
        groupPath: List<String> = emptyList(),
    ): Set<String> {
        val norm = Rect(
            left = minOf(rect.left, rect.right),
            top = minOf(rect.top, rect.bottom),
            right = maxOf(rect.left, rect.right),
            bottom = maxOf(rect.top, rect.bottom),
        )
        return elements.childrenAt(groupPath)
            .filter { child ->
                (elements.elementWorldAabb(child.id) ?: child.visualAabb()).overlaps(norm)
            }
            .map { it.id }
            .toSet()
    }

    /** Translate every element whose id is in `ids` by `delta` (world space). */
    fun translateSelected(
        elements: List<Element>,
        ids: Set<String>,
        delta: Offset,
    ): List<Element> {
        if (ids.isEmpty()) return elements
        fun visit(scope: List<Element>): List<Element> = scope.map { el ->
            when {
                el.id in ids -> el.translate(
                    elements.elementParentFrameVector(el.id, delta),
                )
                el is Element.Group -> el.copy(children = visit(el.children))
                else -> el
            }
        }
        return visit(elements)
    }

    /** Replace the bounds of a single element, wherever it lives in the tree. */
    fun setElementBounds(
        elements: List<Element>,
        id: String,
        bounds: Rect,
    ): List<Element> = elements.mapElementById(id) { it.withBounds(bounds) }

    /** Replace the rotation of a single element (degrees). */
    fun setElementRotation(
        elements: List<Element>,
        id: String,
        degrees: Float,
    ): List<Element> = elements.mapElementById(id) { it.withRotation(degrees) }

    /**
     * Recompute new bounds from a resize-handle drag and apply them. Single-
     * element only; multi-selection callers should iterate or refuse.
     */
    fun resizeSingle(
        elements: List<Element>,
        id: String,
        handle: ResizeHandle,
        pointer: Offset,
    ): List<Element> {
        val target = elements.findElementById(id) ?: return elements
        val newBounds = resizeBounds(target.bounds(), target.rotation, handle, pointer)
        return setElementBounds(elements, id, newBounds)
    }

    /** Delete every element whose id is in `ids`, including subtrees. */
    fun deleteSelected(elements: List<Element>, ids: Set<String>): List<Element> =
        elements.removeElementsByIds(ids)

    /**
     * Group the selected direct children of [groupPath] into a new
     * [Element.Group]. Requires at least two selections. The new group takes
     * the topmost selected z-order slot; siblings are renumbered.
     */
    @OptIn(ExperimentalTime::class)
    fun groupSelected(
        elements: List<Element>,
        groupPath: List<String>,
        ids: Set<String>,
    ): List<Element> {
        val now = Clock.System.now().toEpochMilliseconds()
        val scope = elements.childrenAt(groupPath)
        val selected = scope.filter { it.id in ids }.sortedBy { it.zIndex }
        if (selected.size < 2) return elements

        val selectedIds = selected.mapTo(hashSetOf()) { it.id }
        val ordered = scope.sortedBy { it.zIndex }
        val topmostIndex = ordered.indexOfLast { it.id in selectedIds }
        val group = Element.Group(
            children = selected.mapIndexed { index, child ->
                child.withZIndex(index).touched(now)
            },
            createdAt = now,
            modifiedAt = now,
        )
        val rebuilt = buildList {
            ordered.forEachIndexed { index, element ->
                if (index == topmostIndex) add(group)
                if (element.id !in selectedIds) add(element)
            }
            if (topmostIndex == -1) add(group)
        }.mapIndexed { index, element -> element.withZIndex(index) }

        return elements.replaceScopeChildren(groupPath, rebuilt)
    }

    /**
     * Ungroup the single selected group at [groupPath]. The group's rotation is
     * baked into its children so their visual position is unchanged.
     */
    fun ungroupSelected(
        elements: List<Element>,
        groupPath: List<String>,
        ids: Set<String>,
    ): List<Element> {
        if (ids.size != 1) return elements
        val scope = elements.childrenAt(groupPath)
        val group = scope.firstOrNull { it.id == ids.first() } as? Element.Group ?: return elements

        val pivot = group.bounds().center
        val baked = group.children.map { it.bakedIntoParent(pivot, group.rotation) }
        val ordered = scope.sortedBy { it.zIndex }
        val rebuilt = buildList {
            ordered.forEach { element ->
                if (element.id == group.id) addAll(baked) else add(element)
            }
        }.sortedBy { it.zIndex }
            .mapIndexed { index, element -> element.withZIndex(index) }

        return elements.replaceScopeChildren(groupPath, rebuilt)
    }

    /**
     * Deep-copy selected direct children of [groupPath], assign fresh IDs to
     * every copied node, offset the copies, and return them selected. Bindings
     * pointing inside the duplicated set are remapped; external bindings stay
     * attached to their originals.
     */
    @OptIn(ExperimentalTime::class)
    fun duplicateSelected(
        elements: List<Element>,
        groupPath: List<String>,
        ids: Set<String>,
    ): DuplicateResult? {
        val scope = elements.childrenAt(groupPath)
        val selected = scope.filter { it.id in ids }.sortedBy { it.zIndex }
        if (selected.isEmpty()) return null

        val now = Clock.System.now().toEpochMilliseconds()
        val idMap = HashMap<String, String>()
        fun assignNewIds(element: Element) {
            idMap[element.id] = Uuid.random().toString()
            if (element is Element.Group) element.children.forEach(::assignNewIds)
        }
        selected.forEach(::assignNewIds)

        fun clone(element: Element): Element = when (element) {
            is Element.Path -> element.copy(
                id = idMap.getValue(element.id),
                createdAt = now,
                modifiedAt = now,
            )
            is Element.Image -> element.copy(
                id = idMap.getValue(element.id),
                createdAt = now,
                modifiedAt = now,
            )
            is Element.Text -> element.copy(
                id = idMap.getValue(element.id),
                createdAt = now,
                modifiedAt = now,
            )
            is Element.Shape -> element.copy(
                id = idMap.getValue(element.id),
                startBinding = element.startBinding?.let { idMap[it] ?: it },
                endBinding = element.endBinding?.let { idMap[it] ?: it },
                createdAt = now,
                modifiedAt = now,
            )
            is Element.Group -> element.copy(
                id = idMap.getValue(element.id),
                children = element.children.map(::clone),
                createdAt = now,
                modifiedAt = now,
            )
        }

        val offset = elements.elementParentFrameVector(
            selected.first().id,
            DUPLICATE_OFFSET_WORLD,
        )
        val maxZ = scope.maxOfOrNull { it.zIndex } ?: -1
        val clones = selected
            .map { clone(it).translate(offset) }
            .mapIndexed { index, element -> element.withZIndex(maxZ + 1 + index) }
        val rebuiltScope = propagateBindings(scope + clones)

        return DuplicateResult(
            elements = elements.replaceScopeChildren(groupPath, rebuiltScope),
            selectedIds = clones.mapTo(hashSetOf()) { it.id },
        )
    }

    /**
     * Remove every element whose body is within `radius` of `point`. Reuses the
     * same [hitTest] used by selection, with the eraser disk radius substituted
     * for the pick tolerance — which means stroke width is already accounted
     * for. Returns the original list instance when nothing was hit so callers
     * can skip useless state churn.
     */
    fun eraseAt(
        elements: List<Element>,
        point: Offset,
        radius: Float,
    ): List<Element> {
        if (elements.isEmpty()) return elements
        val survivors = elements.filterNot { it.hitTest(point, radius) }
        return if (survivors.size == elements.size) elements else survivors
    }

    /** Promote selected elements to the top of the z-order. */
    fun bringToFront(elements: List<Element>, ids: Set<String>): List<Element> {
        if (ids.isEmpty()) return elements
        val maxZ = elements.maxOfOrNull { it.zIndex } ?: 0
        var next = maxZ + 1
        return elements.map { el ->
            if (el.id in ids) when (el) {
                is Element.Path -> el.copy(zIndex = next++).touched()
                is Element.Shape -> el.copy(zIndex = next++).touched()
                is Element.Image -> el.copy(zIndex = next++).touched()
                is Element.Text -> el.copy(zIndex = next++).touched()
                is Element.Group -> el.copy(zIndex = next++).touched()
            } else el
        }
    }

    /** Push selected elements to the bottom of the z-order. */
    fun sendToBack(elements: List<Element>, ids: Set<String>): List<Element> {
        if (ids.isEmpty()) return elements
        val minZ = elements.minOfOrNull { it.zIndex } ?: 0
        var next = minZ - 1
        // Walk in current draw order so relative order among selected is preserved.
        val orderedSelected = elements.filter { it.id in ids }.map { it.id }
        val assignments = orderedSelected.reversed().associateWith { next-- }
        return elements.map { el ->
            val newZ = assignments[el.id]
            if (newZ != null) when (el) {
                is Element.Path -> el.copy(zIndex = newZ).touched()
                is Element.Shape -> el.copy(zIndex = newZ).touched()
                is Element.Image -> el.copy(zIndex = newZ).touched()
                is Element.Text -> el.copy(zIndex = newZ).touched()
                is Element.Group -> el.copy(zIndex = newZ).touched()
            } else el
        }
    }

    /** Recolor every selected element's stroke. */
    fun setSelectedStrokeColor(
        elements: List<Element>,
        ids: Set<String>,
        color: Color,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        when (el) {
            is Element.Path -> el.copy(strokeColor = color).touched()
            is Element.Shape -> el.copy(strokeColor = color).touched()
            // Images have no stroke — recolor is a no-op rather than an error,
            // so multi-selecting a mix of shapes and images still works.
            is Element.Image -> el
            // Text reuses the "stroke color" intent as a unified tint so the
            // existing color picker recolors text without a separate intent.
            is Element.Text -> el.copy(color = color).touched()
            is Element.Group -> el
        }
    }

    /**
     * Set the fill color of every selected shape. `null` clears the fill so the
     * shape renders stroke-only. Paths and images have no fill concept and are
     * left untouched — multi-selecting a mix still works.
     */
    fun setSelectedFillColor(
        elements: List<Element>,
        ids: Set<String>,
        color: Color?,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        when (el) {
            is Element.Shape -> el.copy(fillColor = color).touched()
            is Element.Path -> el
            is Element.Image -> el
            is Element.Text -> el
            is Element.Group -> el
        }
    }

    /**
     * Toggle the stroke pass on every selected shape. Paths are always
     * stroked, so they're left untouched.
     */
    fun setSelectedStrokeEnabled(
        elements: List<Element>,
        ids: Set<String>,
        enabled: Boolean,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        when (el) {
            is Element.Shape -> el.copy(strokeEnabled = enabled).touched()
            is Element.Path -> el
            is Element.Image -> el
            is Element.Text -> el
            is Element.Group -> el
        }
    }

    /** Re-stroke every selected element. */
    fun setSelectedStrokeWidth(
        elements: List<Element>,
        ids: Set<String>,
        width: Float,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        when (el) {
            // For Path: rewrite every sample's width to the new value so the
            // stroke becomes uniform. The user-facing "set stroke width" intent
            // is "make this stroke this thick everywhere," not "leave the
            // pressure-driven shape but change the default for new samples."
            is Element.Path -> el.copy(
                strokeWidth = width,
                samples = el.samples.map { it.copy(width = width) },
            ).touched()
            is Element.Shape -> el.copy(strokeWidth = width).touched()
            is Element.Image -> el
            is Element.Text -> el
            is Element.Group -> el
        }
    }

    /**
     * Set the corner radius on selected RECTANGLE / TRIANGLE shapes. Other
     * element types in the selection are left untouched.
     */
    fun setSelectedCornerRadius(
        elements: List<Element>,
        ids: Set<String>,
        radius: Float,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        if (el is Element.Shape && el.shapeType.supportsCornerRadius()) {
            el.copy(cornerRadius = radius).touched()
        } else el
    }

    /** Set the stroke pattern on every selected element (shape OR freehand path). */
    fun setSelectedStrokeStyle(
        elements: List<Element>,
        ids: Set<String>,
        style: StrokeStyle,
    ): List<Element> = applyStyleToSelection(elements, ids) { el ->
        when (el) {
            is Element.Shape -> el.copy(strokeStyle = style).touched()
            is Element.Path -> el.copy(strokeStyle = style).touched()
            is Element.Image -> el
            is Element.Text -> el
            is Element.Group -> el
        }
    }

    // ===== Line / arrow editing =====

    /** Overwrite the points list of a single element. */
    fun setElementPoints(
        elements: List<Element>,
        id: String,
        newPoints: List<Offset>,
    ): List<Element> = elements.mapElementById(id) { el ->
        when (el) {
            // setElementPoints today is only called by LINE/ARROW endpoint
            // dragging, but the sealed `when` forces a Path branch. Preserve
            // existing widths by index when the list length is unchanged; fall
            // back to strokeWidth for any extra slots.
            is Element.Path -> el.copy(
                samples = newPoints.mapIndexed { i, p ->
                    val w = el.samples.getOrNull(i)?.width ?: el.strokeWidth
                    Element.PathSample(p, w)
                },
            ).touched()
            is Element.Shape -> el.copy(points = newPoints).touched()
            is Element.Image -> el.copy(points = newPoints).touched()
            // Text geometry is topLeft + wrapWidth + measuredHeight; the
            // point-list intent doesn't apply. Treat as a no-op so multi-
            // selecting LINE / ARROW + Text still works.
            is Element.Text -> el
            is Element.Group -> el
        }
    }

    /** Update the curve deflection of a single LINE/ARROW shape. */
    fun setLineBend(
        elements: List<Element>,
        id: String,
        bend: Offset,
    ): List<Element> = elements.mapElementById(id) { el ->
        if (el is Element.Shape && el.shapeType.isLineLike()) {
            el.copy(bend = bend).touched()
        } else el
    }

    /**
     * Attach connector bindings to an arrow: each endpoint binds to the topmost
     * non-arrow/line shape it sits inside, or to null if it sits in empty space.
     * When this call is what FIRST makes the arrow connect both endpoints, it
     * also seeds a default perpendicular bend so the connector reads as a curve
     * rather than overlapping with the straight center-to-center line.
     */
    fun finalizeArrowBindings(
        elements: List<Element>,
        arrowId: String,
    ): List<Element> = finalizeArrowInScope(elements, arrowId)

    private fun finalizeArrowInScope(scope: List<Element>, arrowId: String): List<Element> {
        val target = scope.firstOrNull { it.id == arrowId } as? Element.Shape
        if (target == null) {
            return scope.map { element ->
                if (element is Element.Group) {
                    element.copy(children = finalizeArrowInScope(element.children, arrowId))
                } else {
                    element
                }
            }
        }
        if (target.shapeType != ShapeType.ARROW || target.points.size < 2) return scope
        val candidates = scope
            .asSequence()
            .filterIsInstance<Element.Shape>()
            .filter { it.id != arrowId && it.shapeType.isBindable() }
            .sortedByDescending { it.zIndex }
            .toList()
        // Strict bounds.contains() is too unforgiving: dropping an arrow
        // endpoint a few pixels outside a shape produces no binding even when
        // the user clearly meant to connect there. Inflate the AABB by a
        // tolerance so near-misses bind, then propagateBindings will snap the
        // endpoint to the actual visible boundary.
        val startId = candidates.firstOrNull {
            it.bounds().inflate(BINDING_HIT_TOLERANCE).contains(target.points[0])
        }?.id
        val endId = candidates.firstOrNull {
            it.bounds().inflate(BINDING_HIT_TOLERANCE).contains(target.points.last())
        }?.id

        val freshFullyConnected = startId != null && endId != null &&
            (target.startBinding != startId || target.endBinding != endId) &&
            target.bend == Offset.Zero
        val seededBend = if (freshFullyConnected) {
            // Predict where propagateBindings will snap the endpoints to and size
            // the bend relative to THAT segment. Using the raw drag points (often
            // near shape centers) overshoots once snapping moves the endpoints to
            // the boundary — the curve would bulge way past the visible line.
            val startShape = candidates.first { it.id == startId }
            val endShape = candidates.first { it.id == endId }
            val startSnapped = startShape.connectorAnchor(endShape.bounds().center)
            val endSnapped = endShape.connectorAnchor(startShape.bounds().center)
            defaultConnectorBend(startSnapped, endSnapped)
        } else target.bend

        return scope.map { el ->
            if (el.id != arrowId) el
            else (el as Element.Shape).copy(
                startBinding = startId,
                endBinding = endId,
                bend = seededBend,
            ).touched()
        }
    }

    /**
     * Update every arrow's endpoints so they terminate on the bound shape's
     * boundary along the line toward the other endpoint (or its bound center).
     * Idempotent — safe to call after any element mutation. Detaches bindings
     * whose target no longer exists. Bindings are resolved among siblings in
     * the same group frame.
     */
    fun propagateBindings(elements: List<Element>): List<Element> = propagateBindingsInScope(elements)

    private fun propagateBindingsInScope(scope: List<Element>): List<Element> {
        val byId = scope.filterIsInstance<Element.Shape>().associateBy { it.id }
        return scope.map { el ->
            when (el) {
                is Element.Group -> el.copy(children = propagateBindingsInScope(el.children))
                is Element.Shape -> {
                    if (el.shapeType != ShapeType.ARROW ||
                        (el.startBinding == null && el.endBinding == null) ||
                        el.points.size < 2
                    ) {
                        return@map el
                    }
                    val startTarget = el.startBinding?.let { byId[it] }
                    val endTarget = el.endBinding?.let { byId[it] }

                    // Direction reference for boundary clipping: the OTHER endpoint's
                    // bound center if available, else its raw position. We compute the
                    // boundary intersection from each bound shape's center toward that
                    // reference point so the arrow visually starts/ends at the edge.
                    val endRef = endTarget?.bounds()?.center ?: el.points.last()
                    val startRef = startTarget?.bounds()?.center ?: el.points[0]
                    val newStart = startTarget?.connectorAnchor(endRef) ?: el.points[0]
                    val newEnd = endTarget?.connectorAnchor(startRef) ?: el.points.last()
                    val newStartBinding = if (startTarget == null) null else el.startBinding
                    val newEndBinding = if (endTarget == null) null else el.endBinding
                    // Skip touching the arrow when nothing actually changed — propagateBindings
                    // runs after every mutation and most calls are no-ops for any given arrow.
                    val unchanged = newStart == el.points[0] && newEnd == el.points.last() &&
                        newStartBinding == el.startBinding && newEndBinding == el.endBinding
                    if (unchanged) el else el.copy(
                        points = listOf(newStart, newEnd),
                        startBinding = newStartBinding,
                        endBinding = newEndBinding,
                    ).touched()
                }
                else -> el
            }
        }
    }

    /**
     * Apply [transform] to selected elements. Selecting a group applies the
     * transform to every descendant leaf; individual selections apply directly.
     */
    private fun applyStyleToSelection(
        elements: List<Element>,
        ids: Set<String>,
        transform: (Element) -> Element,
    ): List<Element> = elements.map { el ->
        when {
            el.id in ids -> applyToSubtree(el, transform)
            el is Element.Group -> el.copy(
                children = applyStyleToSelection(el.children, ids, transform),
            )
            else -> el
        }
    }

    private fun applyToSubtree(element: Element, transform: (Element) -> Element): Element =
        if (element is Element.Group) {
            element.copy(children = element.children.map { applyToSubtree(it, transform) }).touched()
        } else {
            transform(element)
        }
}

/** Diagonal offset applied to duplicated roots, in the current scope frame. */
private val DUPLICATE_OFFSET_WORLD = Offset(16f, 16f)

/**
 * Minimum world-space distance² between consecutive freehand pen samples.
 * Samples closer than 1 world pixel are dropped — they bloat the active stroke
 * and don't change what the user sees. Squared so we can compare without sqrt.
 */
private const val MIN_PEN_POINT_DIST_SQ: Float = 1f

/** Consecutive polygon vertices closer than this are treated as duplicates. */
private const val POLYGON_POINT_EPSILON: Float = 0.5f

/**
 * World-pixel cap on the longer side of an image placed via [UseCase.insertImage].
 * Large source images (e.g. 4096×3000 screenshots) would otherwise dominate the
 * default-zoom viewport and force the user to immediately resize. 600 px feels
 * roughly notebook-size on a 1× canvas — still readable, easy to grow.
 */
private const val MAX_PLACED_EXTENT: Float = 600f

/**
 * Default world-pixel width for a freshly inserted text block. Wide enough to
 * fit a typical sentence at default font size without immediate re-wrap, and
 * narrow enough that the wrap box's edge-handle is visible inside the
 * default-zoom viewport.
 */
private const val DEFAULT_TEXT_BOX_WIDTH: Float = 240f

/**
 * Slop, in world pixels, applied to a bindable shape's AABB when deciding
 * whether an arrow endpoint counts as "dropped on" that shape. ~24 world-px is
 * enough that hand-eye inaccuracy doesn't break the connector intent but small
 * enough that distinct shapes don't compete for the same drop.
 */
private const val BINDING_HIT_TOLERANCE: Float = 24f

/** Perpendicular bend ~20% of the line length, counter-clockwise of direction. */
private fun defaultConnectorBend(start: Offset, end: Offset): Offset {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.5f) return Offset.Zero
    val ux = dx / len
    val uy = dy / len
    val mag = len * 0.2f
    // (-uy, ux) is the counter-clockwise perpendicular.
    return Offset(-uy * mag, ux * mag)
}

private fun ShapeType.isBindable(): Boolean = when (this) {
    ShapeType.RECTANGLE, ShapeType.CIRCLE, ShapeType.TRIANGLE, ShapeType.POLYGON -> true
    ShapeType.LINE, ShapeType.ARROW -> false
}

private fun ShapeType.isLineLike(): Boolean =
    this == ShapeType.LINE || this == ShapeType.ARROW

/** Shape types whose visual outline can be rounded at the corners. */
fun ShapeType.supportsCornerRadius(): Boolean =
    this == ShapeType.RECTANGLE || this == ShapeType.TRIANGLE
