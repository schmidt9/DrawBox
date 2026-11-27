package io.ak1.drawbox.presentation.viewmodel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.StateSnapshot
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DrawBoxControllerTest {

    private fun newController() = DrawBoxController(Reducer(UseCase()))

    // ===== End-to-end "user draws a stroke" =====
    //
    // These drive the same intent sequence the DrawBox gesture layer dispatches
    // when a user presses, drags, and lifts in PEN mode. They live in commonTest
    // so every published target — JVM, JS, WASM, iOS — proves a user can actually
    // draw, not merely that the code compiles.

    @Test
    fun userCanDrawAFreehandStroke() {
        val controller = newController()
        controller.setMode(Mode.PEN)

        // Press down, then drag through a few points and lift.
        controller.onIntent(Intent.InsertNewPath(Offset(10f, 10f)))
        controller.onIntent(Intent.UpdateLatestPath(Offset(20f, 15f)))
        controller.onIntent(Intent.UpdateLatestPath(Offset(30f, 25f)))
        controller.onIntent(Intent.UpdateLatestPath(Offset(40f, 40f)))

        val elements = controller.state.value.elements
        assertEquals(1, elements.size, "the drag should have produced exactly one stroke")
        val path = elements[0] as Element.Path
        assertEquals(4, path.samples.size, "seed point plus three drag samples")
        assertEquals(Offset(10f, 10f), path.samples.first().position)
        assertEquals(Offset(40f, 40f), path.samples.last().position)
        assertTrue(controller.canUndo.value, "a finished stroke must be undoable")
    }

    @Test
    fun undoRemovesADrawnStroke() {
        val controller = newController()
        controller.setMode(Mode.PEN)
        controller.onIntent(Intent.InsertNewPath(Offset(0f, 0f)))
        controller.onIntent(Intent.UpdateLatestPath(Offset(50f, 50f)))
        assertEquals(1, controller.state.value.elements.size)

        controller.undo()

        assertTrue(controller.state.value.elements.isEmpty(), "undo should clear the stroke")
        assertFalse(controller.canUndo.value)
        assertTrue(controller.canRedo.value, "the undone stroke should be redoable")
    }

    @Test
    fun intentsFlowEmitsEveryProcessedIntent() = runTest(StandardTestDispatcher()) {
        val controller = newController()
        val collected = mutableListOf<Intent>()
        val job = launch { controller.intents.take(2).toList(collected) }

        // Yield so the collector actually subscribes before we emit.
        testScheduler.runCurrent()

        controller.onIntent(Intent.SetStrokeColor(Color.Red))
        controller.onIntent(Intent.SetStrokeWidth(7f))

        job.join()

        assertEquals(2, collected.size)
        assertTrue(collected[0] is Intent.SetStrokeColor)
        assertTrue(collected[1] is Intent.SetStrokeWidth)
    }

    @Test
    fun intentsFlowEmitsAfterStateUpdate() = runTest(StandardTestDispatcher()) {
        val controller = newController()
        var stateAtEmission: Color? = null
        val job = launch {
            controller.intents.take(1).collect {
                // Guarantee: at emission time, state.value already reflects the
                // reduced intent.
                stateAtEmission = controller.state.value.strokeColor
            }
        }
        testScheduler.runCurrent()

        controller.onIntent(Intent.SetStrokeColor(Color.Blue))
        job.join()

        assertEquals(Color.Blue, stateAtEmission)
    }

    // ===== Text tool tap =====

    private fun text(id: String): Element.Text = Element.Text(
        id = id,
        text = "hello",
        fontSize = 16f,
        color = Color.Black,
        topLeft = Offset(0f, 0f),
        wrapWidth = 100f,
        measuredHeight = 40f,
    )

    @Test
    fun textTapOnExistingTextEmitsTextEditRequested() = runTest(StandardTestDispatcher()) {
        val controller = DrawBoxController(
            Reducer(UseCase()),
            State(elements = listOf(text("t")), mode = Mode.TEXT),
        )
        val events = mutableListOf<Event>()
        val job = launch { controller.events.take(1).toList(events) }
        testScheduler.runCurrent()

        controller.onIntent(Intent.TapTextAt(Offset(50f, 20f), tolerance = 8f))
        job.join()

        assertEquals("t", assertIs<Event.TextEditRequested>(events.single()).id)
        assertEquals(setOf("t"), controller.state.value.selectedIds)
        assertEquals(1, controller.state.value.elements.size)
    }

    @Test
    fun textTapOnEmptyCanvasInsertsWithoutEvent() = runTest(StandardTestDispatcher()) {
        val controller = DrawBoxController(
            Reducer(UseCase()),
            State(mode = Mode.TEXT),
        )
        val events = mutableListOf<Event>()
        val job = launch { controller.events.collect { events += it } }
        testScheduler.runCurrent()

        controller.onIntent(Intent.TapTextAt(Offset(500f, 500f), tolerance = 8f))
        testScheduler.runCurrent()
        job.cancel()

        assertTrue(events.isEmpty(), "an insert opens the editor via state, not an event")
        assertIs<Element.Text>(controller.state.value.elements.single())
        assertEquals(1, controller.state.value.history.size)
    }

    // ===== Persistence =====

    @Test
    fun importPathPreservesCurrentStrokeSettings() {
        val controller = DrawBoxController(
            Reducer(UseCase()),
            State(
                strokeColor = Color.Black,
                strokeWidth = 6f,
                bgColor = Color.White,
            ),
        )
        val json = DrawingSerializer.serialize(
            PayLoad(bgColor = Color.White, elements = emptyList()),
        )

        controller.importPath(json)

        val state = controller.state.value
        assertEquals(Color.Black, state.strokeColor, "import must not reset the selected color")
        assertEquals(6f, state.strokeWidth, "import must not reset the stroke width")
        assertEquals(Color.White, state.bgColor)
    }

    @Test
    fun restoreStateReplacesWholeStateAndRefreshesHistoryFlags() {
        val controller = newController()
        assertFalse(controller.canUndo.value)
        assertFalse(controller.canRedo.value)

        val restored = State(
            elements = emptyList(),
            history = listOf(StateSnapshot()),
            future = listOf(StateSnapshot()),
            mode = Mode.SELECT,
            bgColor = Color.White,
        )

        controller.restoreState(restored)

        assertEquals(restored, controller.state.value)
        assertTrue(controller.canUndo.value)
        assertTrue(controller.canRedo.value)
    }
}
