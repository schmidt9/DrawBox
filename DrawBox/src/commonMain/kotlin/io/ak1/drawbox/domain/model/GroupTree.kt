package io.ak1.drawbox.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Pure helpers for the nested [Element.Group] tree.
 *
 * Coordinates in a group's children are expressed in that group's local frame
 * (before the group's own rotation). These helpers never mutate; they return
 * rebuilt ancestor groups whenever a descendant changes so reference-identity
 * caches invalidate correctly.
 */

/** All elements, outermost groups first, including nested children. */
fun List<Element>.flattenElements(): List<Element> = buildList {
    fun visit(element: Element) {
        add(element)
        if (element is Element.Group) element.children.forEach(::visit)
    }
    this@flattenElements.forEach(::visit)
}

/** All non-group leaves in the tree. */
fun List<Element>.flattenLeaves(): List<Element> = flattenElements().filter { it !is Element.Group }

fun Element.containsElementId(id: String): Boolean =
    this.id == id || (this is Element.Group && children.any { it.containsElementId(id) })

fun Element.containsAnyId(ids: Set<String>): Boolean =
    id in ids || (this is Element.Group && children.any { it.containsAnyId(ids) })

fun List<Element>.findElementById(id: String): Element? {
    for (element in this) {
        if (element.id == id) return element
        if (element is Element.Group) {
            element.children.findElementById(id)?.let { return it }
        }
    }
    return null
}

/**
 * Path from the outermost element down to [id], inclusive. Null when the id is
 * not in the tree.
 */
fun List<Element>.pathToElement(id: String): List<Element>? {
    for (element in this) {
        if (element.id == id) return listOf(element)
        if (element is Element.Group) {
            element.children.pathToElement(id)?.let { return listOf(element) + it }
        }
    }
    return null
}

/** ID of the top-level ancestor of [id], or null when absent. */
fun List<Element>.topLevelAncestorId(id: String): String? =
    pathToElement(id)?.firstOrNull()?.id

fun List<Element>.allElementIds(): Set<String> =
    flattenElements().mapTo(linkedSetOf()) { it.id }

/** Direct selectable children of the entered group path. */
fun List<Element>.childrenAt(groupPath: List<String>): List<Element> {
    var scope: List<Element> = this
    for (groupId in groupPath) {
        val group = scope.firstOrNull { it.id == groupId } as? Element.Group ?: return emptyList()
        scope = group.children
    }
    return scope
}

/** Replace the children of the group at [groupPath], or the top level when empty. */
fun List<Element>.replaceScopeChildren(
    groupPath: List<String>,
    children: List<Element>,
): List<Element> {
    if (groupPath.isEmpty()) return children
    val head = groupPath.first()
    return map { element ->
        if (element.id == head && element is Element.Group) {
            element.copy(
                children = element.children.replaceScopeChildren(
                    groupPath.drop(1),
                    children,
                ),
            )
        } else {
            element
        }
    }
}

/** Copy the element with a new z-order slot, preserving all other fields. */
fun Element.withZIndex(index: Int): Element = when (this) {
    is Element.Path -> copy(zIndex = index)
    is Element.Shape -> copy(zIndex = index)
    is Element.Image -> copy(zIndex = index)
    is Element.Text -> copy(zIndex = index)
    is Element.Group -> copy(zIndex = index)
}

/** Drop path entries that no longer resolve to a group chain. */
fun List<Element>.sanitizeGroupPath(groupPath: List<String>): List<String> {
    val valid = mutableListOf<String>()
    var scope: List<Element> = this
    for (groupId in groupPath) {
        val group = scope.firstOrNull { it.id == groupId } as? Element.Group ?: break
        valid += groupId
        scope = group.children
    }
    return valid
}

/** Rebuild every ancestor group instance when [id] changes. */
fun List<Element>.mapElementById(id: String, transform: (Element) -> Element): List<Element> =
    map { element ->
        when {
            element.id == id -> transform(element)
            element is Element.Group -> element.copy(
                children = element.children.mapElementById(id, transform),
            )
            else -> element
        }
    }

/**
 * Apply [transform] to every element whose id is in [ids]. A matched group is
 * transformed as a whole and its descendants are not visited again.
 */
fun List<Element>.mapElementsByIds(
    ids: Set<String>,
    transform: (Element) -> Element,
): List<Element> = map { element ->
    when {
        element.id in ids -> transform(element)
        element is Element.Group -> element.copy(
            children = element.children.mapElementsByIds(ids, transform),
        )
        else -> element
    }
}

fun List<Element>.mapLeaves(transform: (Element) -> Element): List<Element> = map { element ->
    if (element is Element.Group) {
        element.copy(children = element.children.mapLeaves(transform))
    } else {
        transform(element)
    }
}

fun List<Element>.removeElementById(id: String): List<Element> {
    val ids = setOf(id)
    return removeElementsByIds(ids)
}

fun List<Element>.removeElementsByIds(ids: Set<String>): List<Element> = buildList {
    for (element in this@removeElementsByIds) {
        if (element.id in ids) continue
        if (element is Element.Group) {
            val children = element.children.removeElementsByIds(ids)
            if (children.isEmpty()) continue
            add(element.copy(children = children))
        } else {
            add(element)
        }
    }
}

/** Tap resolution at the current group scope. */
data class TapResolution(
    /** Outermost element through the hit leaf, empty on a miss. */
    val hitPath: List<Element> = emptyList(),
    /** Element that should become selected, or null on a miss. */
    val target: Element? = null,
    /** True when the hit lies inside the currently entered group path. */
    val insideEnteredScope: Boolean = false,
)

/** Walk down the tree and return the outermost-to-leaf hit path. */
fun List<Element>.resolveHitPath(
    point: Offset,
    tolerance: Float,
): List<Element> {
    for (element in sortedByDescending { it.zIndex }) {
        hitPathFor(element, point, tolerance)?.let { return it }
    }
    return emptyList()
}

private fun hitPathFor(element: Element, point: Offset, tolerance: Float): List<Element>? {
    if (!element.hitTest(point, tolerance)) return null
    if (element !is Element.Group) return listOf(element)

    val b = element.bounds()
    val local = if (element.rotation == 0f) {
        point
    } else {
        rotateAround(point, b.center, -element.rotation)
    }
    // A group hitTest already proved that some child hits; find that child.
    for (child in element.children.sortedByDescending { it.zIndex }) {
        hitPathFor(child, local, tolerance)?.let { return listOf(element) + it }
    }
    return listOf(element)
}

/**
 * Resolve a tap into the node selectable at [groupPath]. When the hit path runs
 * through the entered group, the target is the group's direct child on the hit
 * path; otherwise the outermost ancestor is the target.
 */
fun List<Element>.resolveTap(
    groupPath: List<String>,
    point: Offset,
    tolerance: Float,
): TapResolution {
    val path = resolveHitPath(point, tolerance)
    if (path.isEmpty()) return TapResolution()
    val pathIds = path.map { it.id }
    val inside = groupPath.isNotEmpty() &&
        pathIds.size > groupPath.size &&
        pathIds.subList(0, groupPath.size) == groupPath
    val target = if (inside) path[groupPath.size] else path.first()
    return TapResolution(hitPath = path, target = target, insideEnteredScope = inside)
}

/**
 * Map a point from [id]'s local frame into the world frame, applying every
 * ancestor group's rotation (outermost first).
 */
fun List<Element>.elementWorldPoint(id: String, localPoint: Offset): Offset {
    val path = pathToElement(id) ?: return localPoint
    var point = localPoint
    for (ancestor in path.dropLast(1)) {
        if (ancestor is Element.Group && ancestor.rotation != 0f) {
            point = rotateAround(point, ancestor.bounds().center, ancestor.rotation)
        }
    }
    return point
}

/**
 * Map a point from [id]'s local frame into the world frame as it is actually
 * drawn: the element's own rotation around its bounds centre first, then the
 * ancestor group rotations via [elementWorldPoint]. Use this for chrome that
 * must follow a leaf's rendered corner, such as the inline text editor's
 * anchor; [elementWorldPoint] deliberately ignores the element's own rotation.
 */
fun List<Element>.elementRenderedWorldPoint(id: String, localPoint: Offset): Offset {
    val element = pathToElement(id)?.lastOrNull() ?: return localPoint
    val rotated = if (element.rotation == 0f) {
        localPoint
    } else {
        rotateAround(localPoint, element.bounds().center, element.rotation)
    }
    return elementWorldPoint(id, rotated)
}

/**
 * Map a world point into the frame of [id]'s parent element (the frame its
 * bounds and rotation live in), undoing ancestor group rotations.
 */
fun List<Element>.pointerInElementParentFrame(id: String, worldPoint: Offset): Offset {
    val path = pathToElement(id) ?: return worldPoint
    var point = worldPoint
    for (ancestor in path.dropLast(1)) {
        if (ancestor is Element.Group && ancestor.rotation != 0f) {
            point = rotateAround(point, ancestor.bounds().center, -ancestor.rotation)
        }
    }
    return point
}

/**
 * Rotate a world-space translation vector into [id]'s parent frame. Used when
 * moving a nested element: positions live in the ancestor's rotated frame.
 */
fun List<Element>.elementParentFrameVector(id: String, worldVector: Offset): Offset {
    val path = pathToElement(id) ?: return worldVector
    var vector = worldVector
    for (ancestor in path.dropLast(1)) {
        if (ancestor is Element.Group && ancestor.rotation != 0f) {
            vector = rotateAround(vector, Offset.Zero, -ancestor.rotation)
        }
    }
    return vector
}

/** World-space AABB of [id]'s rendered shape, including ancestor rotations. */
fun List<Element>.elementWorldAabb(id: String): Rect? {
    val path = pathToElement(id) ?: return null
    val element = path.last()
    val visual = element.visualAabb()
    var corners = listOf(
        visual.topLeft,
        Offset(visual.right, visual.top),
        visual.bottomRight,
        Offset(visual.left, visual.bottom),
    )
    for (ancestor in path.dropLast(1)) {
        if (ancestor is Element.Group && ancestor.rotation != 0f) {
            val pivot = ancestor.bounds().center
            corners = corners.map { rotateAround(it, pivot, ancestor.rotation) }
        }
    }
    return Rect(
        left = corners.minOf { it.x },
        top = corners.minOf { it.y },
        right = corners.maxOf { it.x },
        bottom = corners.maxOf { it.y },
    )
}
