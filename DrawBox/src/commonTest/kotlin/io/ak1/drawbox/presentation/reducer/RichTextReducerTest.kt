package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.TextRun
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Whole-block style intents, rich-text commits, and text tool defaults. */
class RichTextReducerTest {

    private val reducer = Reducer(UseCase())

    private fun text(
        id: String = "t",
        body: String = "abcd",
        bold: Boolean = false,
        italic: Boolean = false,
        runs: List<TextRun> = emptyList(),
    ) = Element.Text(
        id = id,
        text = body,
        fontSize = 20f,
        color = Color.Black,
        topLeft = Offset.Zero,
        wrapWidth = 240f,
        measuredHeight = 24f,
        bold = bold,
        italic = italic,
        runs = runs,
    )

    private fun State.textElement() = assertIs<Element.Text>(elements.single())

    @Test
    fun wholeBlockBoldClearsBoldOverrides() {
        val state = State(
            elements = listOf(
                text(runs = listOf(TextRun(0, 2, bold = true), TextRun(2, 4, italic = true))),
            ),
            selectedIds = setOf("t"),
        )

        val out = reducer.reduce(state, Intent.SetSelectedTextBold(true))

        val text = out.textElement()
        assertTrue(text.bold)
        assertEquals(listOf(TextRun(2, 4, italic = true)), text.runs)
        assertEquals(1, out.history.size)
    }

    @Test
    fun wholeBlockItalicClearsItalicOverrides() {
        val state = State(
            elements = listOf(text(runs = listOf(TextRun(0, 2, italic = false)))),
            selectedIds = setOf("t"),
        )

        val out = reducer.reduce(state, Intent.SetSelectedTextItalic(true))

        val text = out.textElement()
        assertTrue(text.italic)
        assertEquals(emptyList(), text.runs)
    }

    @Test
    fun wholeBlockFontSizeClearsSizeOverridesButKeepsOthers() {
        val state = State(
            elements = listOf(
                text(runs = listOf(TextRun(0, 4, fontSize = 40f, fontFamilyKey = "serif"))),
            ),
            selectedIds = setOf("t"),
        )

        val out = reducer.reduce(state, Intent.SetSelectedFontSize(30f))

        val text = out.textElement()
        assertEquals(30f, text.fontSize)
        assertEquals(listOf(TextRun(0, 4, fontFamilyKey = "serif")), text.runs)
    }

    @Test
    fun wholeBlockAlignmentClearsParagraphOverrides() {
        val state = State(
            elements = listOf(
                text(
                    body = "aa\nbb",
                    runs = listOf(TextRun(0, 3, alignment = TextAlignment.CENTER)),
                ),
            ),
            selectedIds = setOf("t"),
        )

        val out = reducer.reduce(state, Intent.SetSelectedTextAlignment(TextAlignment.RIGHT))

        val text = out.textElement()
        assertEquals(TextAlignment.RIGHT, text.alignment)
        assertEquals(emptyList(), text.runs)
    }

    @Test
    fun updateRichTextCommitsTextAndRunsInOneHistoryStep() {
        val state = State(elements = listOf(text()))

        val out = reducer.reduce(
            state,
            Intent.UpdateRichText(
                id = "t",
                text = "xy",
                runs = listOf(TextRun(0, 1, bold = true)),
            ),
        )

        val text = out.textElement()
        assertEquals("xy", text.text)
        assertEquals(listOf(TextRun(0, 1, bold = true)), text.runs)
        assertEquals(1, out.history.size)
    }

    @Test
    fun legacyUpdateTextRemapsExistingRuns() {
        val state = State(elements = listOf(text(runs = listOf(TextRun(1, 2, bold = true)))))

        val out = reducer.reduce(state, Intent.UpdateText("t", "abXcd"))

        val text = out.textElement()
        assertEquals("abXcd", text.text)
        assertEquals(listOf(TextRun(1, 3, bold = true)), text.runs)
    }

    @Test
    fun textToolInsertUsesBoldAndItalicDefaults() {
        val state = State(
            mode = Mode.TEXT,
            currentItemTextBold = true,
            currentItemTextItalic = true,
        )

        val out = reducer.reduce(state, Intent.TapTextAt(Offset(50f, 50f), tolerance = 8f))

        val text = out.textElement()
        assertTrue(text.bold)
        assertTrue(text.italic)
    }

    @Test
    fun boldAndItalicDefaultsSurviveToolMemory() {
        val state = State(
            mode = Mode.TEXT,
            currentItemTextBold = true,
            currentItemTextItalic = true,
        )

        val away = reducer.reduce(state, Intent.SetMode(Mode.SELECT))
        val off = reducer.reduce(away, Intent.SetTextBold(false))
        assertTrue(!off.currentItemTextBold)

        val back = reducer.reduce(off, Intent.SetMode(Mode.TEXT))
        assertTrue(back.currentItemTextBold)
        assertTrue(back.currentItemTextItalic)
    }
}
