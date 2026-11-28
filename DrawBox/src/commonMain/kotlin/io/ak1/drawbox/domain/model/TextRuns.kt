package io.ak1.drawbox.domain.model

import kotlin.math.max
import kotlin.math.min

/**
 * Block-level style of an [Element.Text]. Runs override individual fields of
 * this style for substrings.
 */
data class TextBlockStyle(
    val fontFamilyKey: String = DEFAULT_FONT_FAMILY_KEY,
    val fontSize: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val alignment: TextAlignment = TextAlignment.LEFT,
)

/** Half-open character span of one paragraph; includes its terminating `\n`. */
data class TextParagraphSpan(val start: Int, val end: Int) {
    val isEmpty: Boolean get() = end <= start
}

/** Property of a [TextRun] that a whole-block change can clear. */
enum class TextRunProperty { FONT_FAMILY, FONT_SIZE, BOLD, ITALIC, ALIGNMENT }

/** The effective block style of a text element (its base, ignoring runs). */
fun Element.Text.blockStyle(): TextBlockStyle = TextBlockStyle(
    fontFamilyKey = fontFamilyKey,
    fontSize = fontSize,
    bold = bold,
    italic = italic,
    alignment = alignment,
)

// ==================== Paragraph geometry ====================

/**
 * Paragraph spans of [text]. A `\n` belongs to the paragraph it terminates.
 * A trailing `\n` produces a final zero-length span; empty text produces one
 * zero-length span. Zero-length spans cannot carry alignment overrides.
 */
fun textParagraphSpans(text: String): List<TextParagraphSpan> {
    val spans = mutableListOf<TextParagraphSpan>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            spans += TextParagraphSpan(start, i + 1)
            start = i + 1
        }
    }
    spans += TextParagraphSpan(start, text.length)
    return spans
}

/** Paragraph containing [offset]; the caret at the end belongs to the last one. */
fun paragraphSpanAt(text: String, offset: Int): TextParagraphSpan? {
    val clamped = offset.coerceIn(0, text.length)
    val spans = textParagraphSpans(text)
    spans.firstOrNull { clamped >= it.start && clamped < it.end }?.let { return it }
    // The caret after a trailing '\n' lands in an empty paragraph that cannot
    // carry alignment; use the previous paragraph so caret styling matches what
    // typing there inherits.
    return if (clamped == text.length) {
        spans.lastOrNull { !it.isEmpty } ?: spans.lastOrNull()
    } else {
        null
    }
}

/**
 * Whole paragraphs touched by [start]..[end]. A selection ending exactly at a
 * paragraph start does not include that paragraph.
 */
fun paragraphRangeForSelection(text: String, start: Int, end: Int): TextParagraphSpan? {
    val spans = textParagraphSpans(text).filterNot { it.isEmpty }
    if (spans.isEmpty()) return null
    val selStart = min(start, end).coerceIn(0, text.length)
    val selEnd = max(start, end).coerceIn(0, text.length)
    val first = spans.firstOrNull { selStart >= it.start && selStart < it.end } ?: spans.last()
    val lastOffset = if (selEnd > selStart) selEnd - 1 else selStart
    val last = spans.firstOrNull { lastOffset >= it.start && lastOffset < it.end } ?: first
    return TextParagraphSpan(first.start, max(first.end, last.end))
}

// ==================== Normalization ====================

/**
 * Canonical run list for [text] against [base]:
 * 1. clamp/sort and trim overlapping ranges (first run wins);
 * 2. split at paragraph boundaries so alignment scoping is unambiguous;
 * 3. collapse each paragraph's alignment to one representative run (the first),
 *    dropping it when it equals [base] alignment;
 * 4. drop values equal to [base] and runs that carry nothing;
 * 5. merge adjacent equal runs inside one paragraph.
 *
 * Idempotent for any input.
 */
fun normalizeTextRuns(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
): List<TextRun> {
    if (text.isEmpty()) return emptyList()
    val length = text.length
    val paragraphs = textParagraphSpans(text).filterNot { it.isEmpty }

    val clamped = runs.mapNotNull { run ->
        val start = run.start.coerceIn(0, length)
        val end = run.end.coerceIn(0, length)
        if (end <= start) null else run.copy(start = start, end = end)
    }.sortedWith(compareBy({ it.start }, { it.end }))

    val trimmed = mutableListOf<TextRun>()
    var coveredUntil = 0
    for (run in clamped) {
        val start = max(run.start, coveredUntil)
        if (run.end <= start) continue
        trimmed += run.copy(start = start)
        coveredUntil = run.end
    }

    // Split at paragraph boundaries.
    val split = mutableListOf<TextRun>()
    for (run in trimmed) {
        for (p in paragraphs) {
            if (p.end <= run.start) continue
            if (p.start >= run.end) break
            val s = max(run.start, p.start)
            val e = min(run.end, p.end)
            if (e > s) split += run.copy(start = s, end = e)
        }
    }

    // Per-paragraph alignment: first alignment-bearing run wins; keep one.
    val alignmentByParagraph = HashMap<Int, TextAlignment>()
    for (run in split) {
        val alignment = run.alignment ?: continue
        val paragraph = paragraphs.firstOrNull { run.start >= it.start && run.start < it.end }
            ?: continue
        alignmentByParagraph.getOrPut(paragraph.start) { alignment }
    }
    val detached = split.map { it.copy(alignment = null) }

    val withAlignment = detached.toMutableList()
    for ((paragraphStart, alignment) in alignmentByParagraph) {
        if (alignment == base.alignment) continue
        val firstInParagraph = withAlignment.indexOfFirst { run ->
            val p = paragraphs.firstOrNull { run.start >= it.start && run.start < it.end }
            p?.start == paragraphStart
        }
        if (firstInParagraph >= 0) {
            withAlignment[firstInParagraph] = withAlignment[firstInParagraph].copy(alignment = alignment)
        }
    }

    val cleaned = withAlignment.map { it.cleanAgainst(base) }
        .filterNot { it.hasNoOverrides() }

    // Merge adjacent equal runs within the same paragraph.
    val merged = mutableListOf<TextRun>()
    for (run in cleaned) {
        val previous = merged.lastOrNull()
        val sameParagraph = previous != null &&
            paragraphs.any { p ->
                previous.start >= p.start && previous.end <= p.end &&
                    run.start >= p.start && run.end <= p.end
            }
        if (previous != null && sameParagraph &&
            previous.end == run.start && previous.withoutRange() == run.withoutRange()
        ) {
            merged[merged.lastIndex] = previous.copy(end = run.end)
        } else {
            merged += run
        }
    }
    return merged
}

private fun TextRun.cleanAgainst(base: TextBlockStyle): TextRun = copy(
    fontFamilyKey = fontFamilyKey?.takeIf { it.isNotBlank() && it != base.fontFamilyKey },
    fontSize = fontSize?.takeIf { it.isFinite() && it > 0f && it != base.fontSize },
    bold = bold?.takeIf { it != base.bold },
    italic = italic?.takeIf { it != base.italic },
    alignment = alignment?.takeIf { it != base.alignment },
)

private fun TextRun.hasNoOverrides(): Boolean =
    fontFamilyKey == null && fontSize == null && bold == null &&
        italic == null && alignment == null

/** The style fields of the run, ignoring its character range. */
private fun TextRun.withoutRange(): TextRun = copy(start = 0, end = 0)

// ==================== Queries ====================

/** Effective style at [offset] (base fields overridden by the covering runs). */
fun resolveStyleAt(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    offset: Int,
): TextBlockStyle {
    if (text.isEmpty()) return base
    val index = offset.coerceIn(0, text.lastIndex)
    val run = runs.firstOrNull { index >= it.start && index < it.end }
    val paragraph = paragraphSpanAt(text, offset)
    val alignmentRun = paragraph?.let { p ->
        runs.firstOrNull {
            it.alignment != null && it.start >= p.start && it.start < p.end
        }
    }
    return base.copy(
        fontFamilyKey = run?.fontFamilyKey ?: base.fontFamilyKey,
        fontSize = run?.fontSize ?: base.fontSize,
        bold = run?.bold ?: base.bold,
        italic = run?.italic ?: base.italic,
        alignment = alignmentRun?.alignment ?: base.alignment,
    )
}

/** True when every character in [start]..[end] is effectively bold. */
fun selectionHasBold(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    start: Int,
    end: Int,
): Boolean = selectionAll(text, runs, base, start, end) { it.bold }

/** True when every character in [start]..[end] is effectively italic. */
fun selectionHasItalic(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    start: Int,
    end: Int,
): Boolean = selectionAll(text, runs, base, start, end) { it.italic }

private inline fun selectionAll(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    start: Int,
    end: Int,
    predicate: (TextBlockStyle) -> Boolean,
): Boolean {
    if (text.isEmpty()) return predicate(base)
    val from = min(start, end).coerceIn(0, text.lastIndex)
    val to = max(start, end).coerceIn(0, text.length)
    if (from >= to) return predicate(resolveStyleAt(text, runs, base, from))
    for (index in from until to) {
        if (!predicate(resolveStyleAt(text, runs, base, index))) return false
    }
    return true
}

// ==================== Mutations ====================

/**
 * Apply [fontFamilyKey]/[fontSize]/[bold]/[italic] (non-null fields only) to
 * [start]..[end]. Paragraph alignment overrides are preserved untouched; use
 * [applyParagraphAlignment] for those. The selection may be empty (no-op).
 */
fun applyTextSpanOverrides(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    start: Int,
    end: Int,
    fontFamilyKey: String? = null,
    fontSize: Float? = null,
    bold: Boolean? = null,
    italic: Boolean? = null,
): List<TextRun> {
    if (fontFamilyKey == null && fontSize == null && bold == null && italic == null) {
        return normalizeTextRuns(text, runs, base)
    }
    val normalized = normalizeTextRuns(text, runs, base)
    val selStart = min(start, end).coerceIn(0, text.length)
    val selEnd = max(start, end).coerceIn(0, text.length)
    if (selStart >= selEnd) return normalized

    // `sortedSetOf` is JVM-only; commonMain builds a set and sorts it.
    val boundaries = mutableSetOf(0, text.length, selStart, selEnd)
    for (run in normalized) {
        boundaries += run.start
        boundaries += run.end
    }
    for (p in textParagraphSpans(text)) {
        boundaries += p.start
        boundaries += p.end
    }

    val out = mutableListOf<TextRun>()
    val points = boundaries.sorted()
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        if (b <= a) continue
        val covering = normalized.firstOrNull { a >= it.start && a < it.end }
        var family = covering?.fontFamilyKey
        var size = covering?.fontSize
        var isBold = covering?.bold
        var isItalic = covering?.italic
        if (a >= selStart && b <= selEnd) {
            if (fontFamilyKey != null) family = fontFamilyKey
            if (fontSize != null) size = fontSize
            if (bold != null) isBold = bold
            if (italic != null) isItalic = italic
        }
        val alignment = covering?.alignment
        if (family != null || size != null || isBold != null ||
            isItalic != null || alignment != null
        ) {
            out += TextRun(a, b, family, size, isBold, isItalic, alignment)
        }
    }
    return normalizeTextRuns(text, out, base)
}

/**
 * Set paragraph alignment for the whole paragraphs touched by
 * [start]..[end]. Passing [TextBlockStyle.alignment] clears the overrides in
 * those paragraphs (back to the block base).
 */
fun applyParagraphAlignment(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    start: Int,
    end: Int,
    alignment: TextAlignment,
): List<TextRun> {
    val normalized = normalizeTextRuns(text, runs, base)
    val span = paragraphRangeForSelection(text, start, end) ?: return normalized
    val paragraphs = textParagraphSpans(text).filterNot { it.isEmpty }
        .filter { it.start < span.end && it.end > span.start }
    val result = normalized.toMutableList()
    for (paragraph in paragraphs) {
        val indices = result.indices.filter { i ->
            val run = result[i]
            run.start >= paragraph.start && run.start < paragraph.end
        }
        if (indices.isEmpty()) {
            if (alignment != base.alignment) {
                result += TextRun(paragraph.start, paragraph.end, alignment = alignment)
            }
            continue
        }
        for (i in indices) {
            result[i] = result[i].copy(alignment = null)
        }
        if (alignment != base.alignment) {
            val first = indices.first()
            result[first] = result[first].copy(alignment = alignment)
        }
    }
    return normalizeTextRuns(text, result, base)
}

/**
 * Remove [property]'s overrides from every run — used when a whole-block change
 * makes that property uniform. [base] must already carry the new value.
 */
fun clearTextRunOverride(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    property: TextRunProperty,
): List<TextRun> {
    if (runs.isEmpty()) return emptyList()
    val cleared = runs.map { run ->
        when (property) {
            TextRunProperty.FONT_FAMILY -> run.copy(fontFamilyKey = null)
            TextRunProperty.FONT_SIZE -> run.copy(fontSize = null)
            TextRunProperty.BOLD -> run.copy(bold = null)
            TextRunProperty.ITALIC -> run.copy(italic = null)
            TextRunProperty.ALIGNMENT -> run.copy(alignment = null)
        }
    }
    return normalizeTextRuns(text, cleared, base)
}

// ==================== Edit remapping ====================

/**
 * Remap [oldRuns] through the text change `oldText` → `newText`:
 * - shift runs after the edit, trim/split runs overlapping a deletion;
 * - give inserted text the effective style at the insertion point (typing
 *   inherits the caret style; replacements inherit the first removed char);
 * - keep paragraph alignment on the paragraphs touched by the edit.
 */
fun remapTextRunsAfterEdit(
    oldText: String,
    newText: String,
    oldRuns: List<TextRun>,
    base: TextBlockStyle,
): List<TextRun> {
    if (oldText == newText) return normalizeTextRuns(oldText, oldRuns, base)
    val old = normalizeTextRuns(oldText, oldRuns, base)
    val oldLength = oldText.length
    val newLength = newText.length

    var prefix = 0
    val maxPrefix = min(oldLength, newLength)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    val maxSuffix = maxPrefix - prefix
    while (suffix < maxSuffix &&
        oldText[oldLength - 1 - suffix] == newText[newLength - 1 - suffix]
    ) {
        suffix++
    }
    // Never split a surrogate pair at the diff boundary.
    if (prefix > 0 &&
        splitsSurrogatePair(oldText, prefix) &&
        splitsSurrogatePair(newText, prefix)
    ) {
        prefix--
        if (prefix + suffix < min(oldLength, newLength)) suffix++
    }

    val oldChangedEnd = oldLength - suffix
    val newChangedEnd = newLength - suffix
    val removed = oldChangedEnd - prefix
    val inserted = newChangedEnd - prefix
    val delta = inserted - removed

    val surviving = mutableListOf<TextRun>()
    for (run in old) {
        when {
            run.end <= prefix -> surviving += run
            run.start >= oldChangedEnd -> surviving += run.copy(
                start = run.start + delta,
                end = run.end + delta,
            )
            else -> {
                val leftEnd = min(run.end, prefix)
                if (leftEnd > run.start) surviving += run.copy(end = leftEnd)
                val rightStart = max(run.start, oldChangedEnd) + delta
                val rightEnd = run.end + delta
                if (rightEnd > rightStart) surviving += run.copy(start = rightStart, end = rightEnd)
            }
        }
    }

    val inheritanceOffset = when {
        oldLength == 0 -> 0
        removed > 0 -> prefix.coerceAtMost(oldText.lastIndex)
        prefix == 0 -> 0
        prefix == oldLength -> oldText.lastIndex
        oldText[prefix - 1] == '\n' -> prefix
        else -> prefix - 1
    }
    val inherited = resolveStyleAt(oldText, old, base, inheritanceOffset)
    if (inserted > 0) {
        val insertedRun = TextRun(
            start = prefix,
            end = newChangedEnd,
            fontFamilyKey = inherited.fontFamilyKey.takeIf { it != base.fontFamilyKey },
            fontSize = inherited.fontSize.takeIf { it != base.fontSize },
            bold = inherited.bold.takeIf { it != base.bold },
            italic = inherited.italic.takeIf { it != base.italic },
        )
        surviving += insertedRun
    }

    val normalized = normalizeTextRuns(newText, surviving, base)
    if (newText.isEmpty()) return normalized

    val alignmentSpan = if (inserted > 0) {
        paragraphRangeForSelection(newText, prefix, newChangedEnd)
    } else {
        paragraphSpanAt(newText, prefix.coerceAtMost(newText.lastIndex))
    }
    return alignmentSpan?.let { span ->
        applyParagraphAlignment(
            text = newText,
            runs = normalized,
            base = base,
            start = span.start,
            end = span.end,
            alignment = inherited.alignment,
        )
    } ?: normalized
}

private fun splitsSurrogatePair(text: String, index: Int): Boolean =
    index in 1 until text.length &&
        text[index - 1].isHighSurrogate() &&
        text[index].isLowSurrogate()
