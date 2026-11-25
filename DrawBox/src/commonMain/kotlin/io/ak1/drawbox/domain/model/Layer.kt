@file:OptIn(ExperimentalUuidApi::class)

package io.ak1.drawbox.domain.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A named, page-local group of top-level elements with independent visibility
 * and lock state.
 *
 * Layers are listed in creation order and never affect z-order: membership
 * only controls whether attached elements render and whether they can be
 * selected. Elements reference a layer by [Element.layerId]; only top-level
 * elements carry a meaningful value.
 *
 * @property id Stable identifier, persisted in the document JSON.
 * @property name Page-unique, user-editable display name.
 * @property isVisible When false every attached top-level element is hidden
 *   from the canvas and from exports.
 * @property isLocked When true every attached top-level element is visible but
 *   cannot be selected or otherwise interacted with.
 */
data class Layer(
    val id: String = Uuid.random().toString(),
    val name: String,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
)

/** Trims [raw] for layer-name comparisons and storage. */
fun normalizeLayerName(raw: String): String = raw.trim()

/** Case-insensitive membership check; [excludingId] allows case-only renames. */
fun isLayerNameTaken(
    candidate: String,
    layers: List<Layer>,
    excludingId: String? = null,
): Boolean {
    val normalized = normalizeLayerName(candidate)
    if (normalized.isEmpty()) return false
    return layers.any {
        it.id != excludingId && it.name.equals(normalized, ignoreCase = true)
    }
}

/** First free `Layer 1`, `Layer 2`, … name, in creation order. */
fun defaultLayerName(layers: List<Layer>): String {
    var index = 1
    while (isLayerNameTaken("Layer $index", layers)) index++
    return "Layer $index"
}

/** Returns [base] when free, otherwise the first free `base 2`, `base 3`, … */
fun uniqueLayerName(base: String, layers: List<Layer>): String {
    val normalized = normalizeLayerName(base).ifEmpty { "Layer" }
    if (!isLayerNameTaken(normalized, layers)) return normalized
    var index = 2
    while (true) {
        val candidate = "$normalized $index"
        if (!isLayerNameTaken(candidate, layers)) return candidate
        index++
    }
}

/** The layer [this] belongs to, or null when unattached/unknown. */
fun Element.layerOf(layers: List<Layer>): Layer? =
    layerId?.let { id -> layers.firstOrNull { it.id == id } }

/** Copy with a new layer id, preserving [Element.touched] semantics. */
fun Element.withLayerId(layerId: String?): Element = copyElementLayerId(layerId).touched()

/** Copy with a new display name, preserving [Element.touched] semantics. */
fun Element.withName(name: String): Element = when (this) {
    is Element.Path -> copy(name = name).touched()
    is Element.Image -> copy(name = name).touched()
    is Element.Text -> copy(name = name).touched()
    is Element.Shape -> copy(name = name).touched()
    is Element.Group -> copy(name = name).touched()
}

/** Copy with a new layer id without bumping [Element.modifiedAt]. */
internal fun Element.copyElementLayerId(layerId: String?): Element = when (this) {
    is Element.Path -> copy(layerId = layerId)
    is Element.Image -> copy(layerId = layerId)
    is Element.Text -> copy(layerId = layerId)
    is Element.Shape -> copy(layerId = layerId)
    is Element.Group -> copy(layerId = layerId)
}

/** Recursively collects the ids of [rootIds] and all of their descendants. */
fun List<Element>.subtreeIdsOf(rootIds: Set<String>): Set<String> {
    if (rootIds.isEmpty()) return emptySet()
    val result = mutableSetOf<String>()
    forEach { element ->
        if (element.id in rootIds) result += listOf(element).flattenElements().map { it.id }
    }
    return result
}

/** Clears layer ids on every descendant; top-level ids are preserved. */
fun List<Element>.clearDescendantLayerIds(): List<Element> = map { element ->
    when (element) {
        is Element.Group -> element.copy(
            children = element.children.map { it.clearLayerIdDeep() },
        )
        else -> element
    }
}

private fun Element.clearLayerIdDeep(): Element = when (this) {
    is Element.Path -> if (layerId == null) this else copy(layerId = null)
    is Element.Image -> if (layerId == null) this else copy(layerId = null)
    is Element.Text -> if (layerId == null) this else copy(layerId = null)
    is Element.Shape -> if (layerId == null) this else copy(layerId = null)
    is Element.Group -> copy(
        layerId = null,
        children = children.map { it.clearLayerIdDeep() },
    )
}

/**
 * Drops layer references that no longer resolve (a deleted or imported layer)
 * and enforces "top-level only" membership by clearing descendants. Unattached
 * elements stay on the canvas exactly as before.
 */
fun List<Element>.sanitizeLayerIds(layers: List<Layer>): List<Element> {
    val ids = layers.mapTo(HashSet()) { it.id }
    return clearDescendantLayerIds().map { element ->
        if (element.layerId != null && element.layerId !in ids) {
            element.copyElementLayerId(null)
        } else {
            element
        }
    }
}

/**
 * Truncates [groupPath] when its outermost entered group is excluded (hidden
 * or locked). The existing `sanitizeGroupPath` only drops groups that were
 * deleted, so this covers layer-state changes.
 */
fun List<Element>.sanitizeGroupPathForExcludedIds(
    groupPath: List<String>,
    excludedTopLevelIds: Set<String>,
): List<String> {
    if (groupPath.isEmpty() || excludedTopLevelIds.isEmpty()) return groupPath
    return if (groupPath.first() in excludedTopLevelIds) emptyList() else groupPath
}

/** Top-level element ids whose layer is hidden. */
fun State.hiddenLayerTopLevelIds(): Set<String> {
    if (layers.isEmpty()) return emptySet()
    val hidden = layers.filterNot { it.isVisible }.mapTo(HashSet()) { it.id }
    if (hidden.isEmpty()) return emptySet()
    return elements
        .filter { it.layerId != null && it.layerId in hidden }
        .mapTo(HashSet()) { it.id }
}

/**
 * Top-level element ids that must not be interacted with. Hidden layers are
 * always excluded; [includeLocked] additionally excludes locked layers (the
 * eraser/marquee paths and selection all want this).
 */
fun State.excludedTopLevelIds(includeLocked: Boolean = true): Set<String> {
    if (layers.isEmpty()) return emptySet()
    val blocked = layers
        .filter { !it.isVisible || (includeLocked && it.isLocked) }
        .mapTo(HashSet()) { it.id }
    if (blocked.isEmpty()) return emptySet()
    return elements
        .filter { it.layerId != null && it.layerId in blocked }
        .mapTo(HashSet()) { it.id }
}

/** Elements to render/export: hidden layers omitted, locked layers kept. */
fun State.visibleElements(): List<Element> {
    val hidden = hiddenLayerTopLevelIds()
    return if (hidden.isEmpty()) elements else elements.filter { it.id !in hidden }
}

/** Elements the pointer may interact with: hidden and locked layers omitted. */
fun State.hittableElements(): List<Element> {
    val excluded = excludedTopLevelIds(includeLocked = true)
    return if (excluded.isEmpty()) elements else elements.filter { it.id !in excluded }
}

/** Page-level variant used by previews/exporters that only have a payload. */
fun List<Element>.visibleUnderLayers(layers: List<Layer>): List<Element> {
    if (layers.isEmpty()) return this
    val hidden = layers.filterNot { it.isVisible }.mapTo(HashSet()) { it.id }
    if (hidden.isEmpty()) return this
    return filter { it.layerId == null || it.layerId !in hidden }
}
