package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Layer
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [Intent.TapTextAt]: a Text-tool tap edits the top-level text under the
 * pointer instead of stacking a new empty text on it, and falls back to the
 * previous insert behavior on a miss.
 */
class TextTapReducerTest {

    private val reducer = Reducer(UseCase())

    /** Bounds are (x, y) .. (x + 100, y + 40). */
    private fun text(
        id: String,
        x: Float = 0f,
        y: Float = 0f,
        z: Int = 0,
        layerId: String? = null,
    ): Element.Text = Element.Text(
        id = id,
        text = "hello",
        fontSize = 16f,
        color = Color.Black,
        topLeft = Offset(x, y),
        wrapWidth = 100f,
        measuredHeight = 40f,
        zIndex = z,
        layerId = layerId,
    )

    @Test
    fun tapOnExistingTextSelectsItWithoutHistoryOrInsert() {
        val existing = text("t")
        val state = State(elements = listOf(existing), mode = Mode.TEXT)

        val out = reducer.reduce(state, Intent.TapTextAt(Offset(50f, 20f), tolerance = 8f))

        assertEquals(setOf("t"), out.selectedIds)
        assertEquals(1, out.elements.size)
        assertSame(existing, out.elements.single())
        assertTrue(out.history.isEmpty(), "opening the editor is not a document edit")
    }

    @Test
    fun tapOnEmptyCanvasInsertsEmptyTextWithStateDefaults() {
        val state = State(
            elements = listOf(text("old", x = 500f)),
            mode = Mode.TEXT,
            selectedIds = setOf("old"),
            strokeColor = Color.Green,
            currentItemFontSize = 33f,
            currentItemFontFamilyKey = "serif",
            currentItemTextAlignment = TextAlignment.CENTER,
        )

        val out = reducer.reduce(state, Intent.TapTextAt(Offset(200f, 300f), tolerance = 8f))

        val inserted = assertIs<Element.Text>(out.elements.last())
        assertEquals("", inserted.text)
        assertEquals(Offset(200f, 300f), inserted.topLeft)
        assertEquals(33f, inserted.fontSize)
        assertEquals("serif", inserted.fontFamilyKey)
        assertEquals(TextAlignment.CENTER, inserted.alignment)
        assertEquals(Color.Green, inserted.color)
        assertEquals(2, out.elements.size)
        assertEquals(1, out.history.size)
        assertTrue(out.selectedIds.isEmpty(), "a new text starts unselected")
    }

    @Test
    fun tapOnTextInsideGroupInsertsTopLevelText() {
        val grouped = Element.Group(
            id = "g",
            children = listOf(text("child")),
        )
        val state = State(elements = listOf(grouped), mode = Mode.TEXT)

        val out = reducer.reduce(state, Intent.TapTextAt(Offset(50f, 20f), tolerance = 8f))

        assertEquals(2, out.elements.size)
        assertSame(grouped, out.elements.first(), "the group is untouched")
        assertIs<Element.Text>(out.elements.last())
        assertEquals(1, out.history.size, "grouped text is not edited in TEXT mode")
    }

    @Test
    fun tapOnTopmostTextSelectsIt() {
        val lower = text("lower", z = 0)
        val upper = text("upper", x = 50f, z = 5)
        val state = State(elements = listOf(lower, upper), mode = Mode.TEXT)

        val out = reducer.reduce(state, Intent.TapTextAt(Offset(60f, 20f), tolerance = 8f))

        assertEquals(setOf("upper"), out.selectedIds)
        assertEquals(2, out.elements.size)
        assertTrue(out.history.isEmpty())
    }

    @Test
    fun tapWithinToleranceEditsButOutOfRangeInserts() {
        val existing = text("t") // bounds 0..100 x 0..40
        val state = State(elements = listOf(existing), mode = Mode.TEXT)

        val near = reducer.reduce(state, Intent.TapTextAt(Offset(105f, 20f), tolerance = 8f))
        assertEquals(setOf("t"), near.selectedIds)

        val far = reducer.reduce(state, Intent.TapTextAt(Offset(120f, 20f), tolerance = 8f))
        assertEquals(2, far.elements.size)
        assertIs<Element.Text>(far.elements.last())
    }

    @Test
    fun tapOnTextOfHiddenLayerInserts() {
        val layer = Layer(id = "l1", name = "Hidden", isVisible = false)
        val state = State(
            elements = listOf(text("t", layerId = "l1")),
            layers = listOf(layer),
            mode = Mode.TEXT,
        )

        val out = reducer.reduce(state, Intent.TapTextAt(Offset(50f, 20f), tolerance = 8f))

        assertEquals(2, out.elements.size, "hidden text is not editable")
        assertTrue(out.selectedIds.isEmpty())
    }

    @Test
    fun tapOnTextOfLockedLayerInserts() {
        val layer = Layer(id = "l1", name = "Locked", isLocked = true)
        val state = State(
            elements = listOf(text("t", layerId = "l1")),
            layers = listOf(layer),
            mode = Mode.TEXT,
        )

        val out = reducer.reduce(state, Intent.TapTextAt(Offset(50f, 20f), tolerance = 8f))

        assertEquals(2, out.elements.size, "locked text is not editable")
        assertTrue(out.selectedIds.isEmpty())
    }
}
