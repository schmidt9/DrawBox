package io.ak1.drawbox.domain.model

/**
 * First free `base 1`, `base 2`, … name. Comparison is case-insensitive so
 * element names are unique regardless of capitalization.
 */
fun uniqueElementName(base: String, existingNames: Collection<String>): String {
    var index = 1
    while (true) {
        val candidate = "$base $index"
        val taken = existingNames.any { it.equals(candidate, ignoreCase = true) }
        if (!taken) return candidate
        index++
    }
}

/** Human-readable kind used as the prefix of an auto-assigned element name. */
fun Element.defaultElementNameBase(): String = when (this) {
    is Element.Path -> "Stroke"
    is Element.Image -> "Image"
    is Element.Text -> "Text"
    is Element.Group -> "Group"
    is Element.Shape -> when (shapeType) {
        ShapeType.RECTANGLE -> "Rectangle"
        ShapeType.CIRCLE -> "Circle"
        ShapeType.TRIANGLE -> "Triangle"
        ShapeType.ARROW -> "Arrow"
        ShapeType.LINE -> "Line"
        ShapeType.POLYGON -> "Polygon"
    }
}

/**
 * Assigns a fresh unique name to this element and every descendant, recording
 * each chosen name in [used] (lowercase).
 */
fun Element.withFreshNames(used: MutableSet<String>): Element = when (this) {
    is Element.Group -> {
        val fresh = uniqueElementName(defaultElementNameBase(), used)
        used += fresh.lowercase()
        copy(name = fresh, children = children.map { it.withFreshNames(used) })
    }
    else -> copyElementName(fresh = uniqueElementName(defaultElementNameBase(), used).also {
        used += it.lowercase()
    })
}

/** Copy with a new display name without bumping [Element.modifiedAt]. */
internal fun Element.copyElementName(fresh: String): Element = when (this) {
    is Element.Path -> copy(name = fresh)
    is Element.Image -> copy(name = fresh)
    is Element.Text -> copy(name = fresh)
    is Element.Shape -> copy(name = fresh)
    is Element.Group -> copy(name = fresh)
}

/**
 * Fills blank names on elements loaded from legacy documents. Existing names
 * are preserved and reserved first, so generated names never collide.
 */
fun List<Element>.withNormalizedNames(): List<Element> {
    val used = mutableSetOf<String>()
    fun reserve(element: Element) {
        if (element.name.isNotBlank()) used += element.name.lowercase()
        if (element is Element.Group) element.children.forEach(::reserve)
    }
    forEach(::reserve)

    fun assign(element: Element): Element {
        val finalName = element.name.ifBlank {
            uniqueElementName(element.defaultElementNameBase(), used).also {
                used += it.lowercase()
            }
        }
        return when (element) {
            is Element.Group -> element.copy(
                name = finalName,
                children = element.children.map(::assign),
            )
            else -> element.copyElementName(finalName)
        }
    }
    return map(::assign)
}
