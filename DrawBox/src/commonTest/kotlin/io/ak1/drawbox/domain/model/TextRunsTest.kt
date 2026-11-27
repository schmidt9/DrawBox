package io.ak1.drawbox.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextRunsTest {

    private val base = TextBlockStyle(fontSize = 20f)

    // ==================== Paragraph geometry ====================

    @Test
    fun paragraphSpansIncludeTheTerminatingNewline() {
        assertEquals(
            listOf(TextParagraphSpan(0, 3), TextParagraphSpan(3, 5)),
            textParagraphSpans("ab\ncd"),
        )
        assertEquals(
            listOf(TextParagraphSpan(0, 3), TextParagraphSpan(3, 3)),
            textParagraphSpans("ab\n"),
        )
        assertEquals(listOf(TextParagraphSpan(0, 0)), textParagraphSpans(""))
    }

    @Test
    fun paragraphRangeCoversWholeTouchedParagraphs() {
        val text = "aa\nbb\ncc"

        assertEquals(TextParagraphSpan(0, 3), paragraphRangeForSelection(text, 0, 1))
        assertEquals(TextParagraphSpan(0, 6), paragraphRangeForSelection(text, 1, 4))
        // Ending exactly at the next paragraph's start does not include it.
        assertEquals(TextParagraphSpan(0, 3), paragraphRangeForSelection(text, 0, 3))
        assertEquals(TextParagraphSpan(6, 8), paragraphRangeForSelection(text, 7, 7))
    }

    // ==================== Normalization ====================

    @Test
    fun normalizationSortsTrimsClampsAndMerges() {
        val normalized = normalizeTextRuns(
            text = "abcdef",
            runs = listOf(
                TextRun(2, 4, bold = true),
                TextRun(0, 2, bold = true),
                TextRun(4, 99, bold = true),
                TextRun(-5, 1, italic = true),
                TextRun(3, 3, bold = true),
            ),
            base = base,
        )
        // Overlaps are trimmed first-wins; equal adjacent runs merge.
        assertEquals(
            listOf(
                TextRun(0, 1, italic = true),
                TextRun(1, 6, bold = true),
            ),
            normalized,
        )
    }

    @Test
    fun normalizationDropsValuesEqualToTheBase() {
        val boldBase = base.copy(bold = true)
        val normalized = normalizeTextRuns(
            text = "abc",
            runs = listOf(
                TextRun(0, 2, bold = true, fontSize = 20f, fontFamilyKey = "sans"),
                TextRun(2, 3, fontSize = 32f),
            ),
            base = boldBase,
        )
        assertEquals(listOf(TextRun(2, 3, fontSize = 32f)), normalized)
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = normalizeTextRuns(
            "abc\ndef",
            listOf(TextRun(1, 6, bold = true), TextRun(2, 4, fontSize = 30f)),
            base,
        )
        assertEquals(once, normalizeTextRuns("abc\ndef", once, base))
    }

    @Test
    fun normalizationSplitsAtParagraphBoundaries() {
        val normalized = normalizeTextRuns("ab\ncd", listOf(TextRun(0, 5, bold = true)), base)
        assertEquals(
            listOf(TextRun(0, 3, bold = true), TextRun(3, 5, bold = true)),
            normalized,
        )
    }

    // ==================== Alignment ====================

    @Test
    fun paragraphAlignmentIsKeptOnOneRepresentativeRun() {
        val aligned = applyParagraphAlignment(
            text = "aa\nbb\ncc",
            runs = emptyList(),
            base = base,
            start = 0,
            end = 1,
            alignment = TextAlignment.CENTER,
        )
        assertEquals(listOf(TextRun(0, 3, alignment = TextAlignment.CENTER)), aligned)

        val two = applyParagraphAlignment(
            text = "aa\nbb\ncc",
            runs = aligned,
            base = base,
            start = 4,
            end = 5,
            alignment = TextAlignment.RIGHT,
        )
        assertEquals(
            listOf(
                TextRun(0, 3, alignment = TextAlignment.CENTER),
                TextRun(3, 6, alignment = TextAlignment.RIGHT),
            ),
            two,
        )
    }

    @Test
    fun alignmentSpanningParagraphsAppliesToBoth() {
        val runs = applyParagraphAlignment(
            text = "aa\nbb\ncc",
            runs = emptyList(),
            base = base,
            start = 1,
            end = 4,
            alignment = TextAlignment.CENTER,
        )
        assertEquals(
            listOf(
                TextRun(0, 3, alignment = TextAlignment.CENTER),
                TextRun(3, 6, alignment = TextAlignment.CENTER),
            ),
            runs,
        )
    }

    @Test
    fun baseAlignmentClearsParagraphOverrides() {
        val centered = applyParagraphAlignment(
            "a\nb", emptyList(), base, 0, 1, TextAlignment.CENTER,
        )
        val cleared = applyParagraphAlignment(
            "a\nb", centered, base, 0, 1, TextAlignment.LEFT,
        )
        assertEquals(emptyList(), cleared)
    }

    // ==================== Span overrides ====================

    @Test
    fun spanOverrideSplitsExistingRuns() {
        val runs = applyTextSpanOverrides(
            text = "abcd",
            runs = listOf(TextRun(0, 4, bold = true)),
            base = base,
            start = 2,
            end = 3,
            fontSize = 30f,
        )
        assertEquals(
            listOf(
                TextRun(0, 2, bold = true),
                TextRun(2, 3, bold = true, fontSize = 30f),
                TextRun(3, 4, bold = true),
            ),
            runs,
        )
    }

    @Test
    fun explicitFalseSurvivesABoldBase() {
        val boldBase = base.copy(bold = true)
        val runs = applyTextSpanOverrides(
            text = "abcd",
            runs = emptyList(),
            base = boldBase,
            start = 1,
            end = 3,
            bold = false,
        )
        assertEquals(listOf(TextRun(1, 3, bold = false)), runs)
        assertFalse(resolveStyleAt("abcd", runs, boldBase, 1).bold)
        assertTrue(resolveStyleAt("abcd", runs, boldBase, 0).bold)
    }

    @Test
    fun spanOverridePreservesParagraphAlignment() {
        val centered = applyParagraphAlignment(
            "abcd", emptyList(), base, 0, 1, TextAlignment.CENTER,
        )
        val styled = applyTextSpanOverrides(
            text = "abcd",
            runs = centered,
            base = base,
            start = 1,
            end = 2,
            italic = true,
        )
        assertEquals(TextAlignment.CENTER, resolveStyleAt("abcd", styled, base, 3).alignment)
        assertTrue(resolveStyleAt("abcd", styled, base, 1).italic)
    }

    @Test
    fun clearOverrideRemovesOneProperty() {
        val runs = clearTextRunOverride(
            text = "ab",
            runs = listOf(TextRun(0, 2, bold = true, fontSize = 30f)),
            base = base,
            property = TextRunProperty.BOLD,
        )
        assertEquals(listOf(TextRun(0, 2, fontSize = 30f)), runs)
    }

    // ==================== Queries ====================

    @Test
    fun resolveStyleAtCoversRunsAndEndOfText() {
        val runs = listOf(TextRun(1, 3, bold = true, fontSize = 30f))
        assertEquals(base, resolveStyleAt("abcd", runs, base, 0))
        assertEquals(base.copy(bold = true, fontSize = 30f), resolveStyleAt("abcd", runs, base, 1))
        assertEquals(base, resolveStyleAt("abcd", runs, base, 3))
        // Past the end clamps to the last character's run.
        assertEquals(base, resolveStyleAt("abcd", runs, base, 4))
    }

    @Test
    fun selectionUniformityChecksEveryCharacter() {
        val runs = listOf(TextRun(0, 2, bold = true))
        assertFalse(selectionHasBold("abcd", runs, base, 0, 3))
        assertTrue(selectionHasBold("abcd", runs, base, 0, 2))
        assertFalse(selectionHasBold("abcd", runs, base, 2, 2))
    }

    // ==================== Edit remapping ====================

    @Test
    fun insertionInheritsCaretStyle() {
        val old = listOf(TextRun(1, 2, bold = true))
        val runs = remapTextRunsAfterEdit("abcd", "abXcd", old, base)
        assertEquals(listOf(TextRun(1, 3, bold = true)), runs)
    }

    @Test
    fun insertionAtEndInheritsLastCharacterStyle() {
        val runs = remapTextRunsAfterEdit(
            oldText = "ab",
            newText = "abX",
            oldRuns = listOf(TextRun(0, 2, bold = true)),
            base = base,
        )
        assertEquals(listOf(TextRun(0, 3, bold = true)), runs)
    }

    @Test
    fun insertionAtStartUsesTheFirstCharacterStyle() {
        val runs = remapTextRunsAfterEdit(
            oldText = "ab",
            newText = "Xab",
            oldRuns = listOf(TextRun(0, 2, bold = true)),
            base = base,
        )
        assertEquals(listOf(TextRun(0, 3, bold = true)), runs)
    }

    @Test
    fun deletionTrimsRuns() {
        val runs = remapTextRunsAfterEdit(
            oldText = "abcd",
            newText = "abd",
            oldRuns = listOf(TextRun(1, 3, bold = true)),
            base = base,
        )
        assertEquals(listOf(TextRun(1, 2, bold = true)), runs)
    }

    @Test
    fun deletionRemovesEmojiRun() {
        val emoji = "a😀b" // a😀b, the emoji occupies indices 1..3
        val runs = remapTextRunsAfterEdit(
            oldText = emoji,
            newText = "ab",
            oldRuns = listOf(TextRun(1, 3, bold = true)),
            base = base,
        )
        assertEquals(emptyList(), runs)
    }

    @Test
    fun replacementInheritsFirstRemovedCharacterStyle() {
        val runs = remapTextRunsAfterEdit(
            oldText = "abcd",
            newText = "aX",
            oldRuns = listOf(TextRun(1, 3, bold = true)),
            base = base,
        )
        assertEquals(listOf(TextRun(1, 2, bold = true)), runs)
    }

    @Test
    fun deletingANewlineKeepsTheLeftParagraphAlignment() {
        val text = "aa\nbb"
        val aligned = applyParagraphAlignment(
            text, emptyList(), base, 3, 5, TextAlignment.RIGHT,
        )
        assertEquals(listOf(TextRun(3, 5, alignment = TextAlignment.RIGHT)), aligned)

        val joined = remapTextRunsAfterEdit(
            oldText = text,
            newText = "aabb",
            oldRuns = aligned,
            base = base,
        )
        // The joined paragraph inherits the left paragraph's LEFT alignment.
        assertTrue(joined.none { it.alignment != null })
    }

    @Test
    fun addingANewlineKeepsAlignmentOnBothParagraphs() {
        val text = "aabb"
        val centered = applyParagraphAlignment(
            text, emptyList(), base, 0, 4, TextAlignment.CENTER,
        )
        val split = remapTextRunsAfterEdit(
            oldText = text,
            newText = "aa\nbb",
            oldRuns = centered,
            base = base,
        )
        // Splitting a paragraph with Enter keeps its alignment on both halves,
        // matching word processors.
        assertEquals(TextAlignment.CENTER, resolveStyleAt("aa\nbb", split, base, 0).alignment)
        assertEquals(TextAlignment.CENTER, resolveStyleAt("aa\nbb", split, base, 4).alignment)
    }

    @Test
    fun pastedTextTakesCaretStyle() {
        val runs = remapTextRunsAfterEdit(
            oldText = "ab",
            newText = "aXY\nZb",
            oldRuns = listOf(TextRun(0, 1, fontFamilyKey = "mono")),
            base = base,
        )
        // The whole pasted range ("XY\nZ") inherits the style at the caret
        // ("mono"), including the part in the second paragraph.
        assertEquals("mono", resolveStyleAt("aXY\nZb", runs, base, 2).fontFamilyKey)
        assertEquals("mono", resolveStyleAt("aXY\nZb", runs, base, 4).fontFamilyKey)
        assertEquals(base.fontFamilyKey, resolveStyleAt("aXY\nZb", runs, base, 5).fontFamilyKey)
    }

    @Test
    fun selectionOnlyChangeLeavesRunsAlone() {
        val runs = listOf(TextRun(1, 2, bold = true))
        assertEquals(runs, remapTextRunsAfterEdit("abcd", "abcd", runs, base))
    }
}
