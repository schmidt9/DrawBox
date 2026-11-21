package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolygonReducerTest {

    private val reducer = Reducer(UseCase())

    private fun start(): State =
        reducer.reduce(
            State(mode = Mode.POLYGON, strokeColor = Color.Black),
            Intent.StartPolygon(Offset.Zero),
        )

    @Test
    fun startAppendUpdateFinishKeepsOneHistoryEntry() {
        val started = start()
        val id = assertNotNull(started.activePolygonId)
        assertEquals(1, started.elements.size)
        assertEquals(1, started.history.size)
        val polygon = started.elements.single() as Element.Shape
        assertEquals(ShapeType.POLYGON, polygon.shapeType)
        assertEquals(1, polygon.points.size)

        val appended = reducer.reduce(started, Intent.AppendPolygonPoint(Offset(10f, 0f)))
        assertEquals(1, appended.history.size)
        assertEquals(2, (appended.elements.single() as Element.Shape).points.size)

        val moved = reducer.reduce(appended, Intent.UpdateLatestPolygonPoint(Offset(10f, 10f)))
        assertEquals(
            Offset(10f, 10f),
            (moved.elements.single() as Element.Shape).points.last(),
        )

        val third = reducer.reduce(moved, Intent.AppendPolygonPoint(Offset(0f, 10f)))
        val finished = reducer.reduce(third, Intent.FinishPolygon)
        assertNull(finished.activePolygonId)
        assertEquals(id, finished.elements.single().id)
        assertEquals(3, (finished.elements.single() as Element.Shape).points.size)
        assertEquals(1, finished.history.size)
    }

    @Test
    fun firstDragAppendsAndThenMovesTheSecondVertex() {
        val started = start()
        val firstMove = reducer.reduce(
            started,
            Intent.UpdateLatestPolygonPoint(Offset(10f, 10f)),
        )
        val firstPoints = (firstMove.elements.single() as Element.Shape).points
        assertEquals(2, firstPoints.size)
        assertEquals(Offset.Zero, firstPoints[0])
        assertEquals(Offset(10f, 10f), firstPoints[1])

        val secondMove = reducer.reduce(
            firstMove,
            Intent.UpdateLatestPolygonPoint(Offset(20f, 20f)),
        )
        val secondPoints = (secondMove.elements.single() as Element.Shape).points
        assertEquals(2, secondPoints.size)
        assertEquals(Offset.Zero, secondPoints[0])
        assertEquals(Offset(20f, 20f), secondPoints[1])
    }

    @Test
    fun finishWithFewerThanThreeVerticesCancels() {
        val started = start()
        val appended = reducer.reduce(started, Intent.AppendPolygonPoint(Offset(10f, 0f)))
        val finished = reducer.reduce(appended, Intent.FinishPolygon)
        assertTrue(finished.elements.isEmpty())
        assertNull(finished.activePolygonId)
        assertTrue(finished.history.isEmpty())
    }

    @Test
    fun cancelRestoresPrePolygonState() {
        val rect = Element.Shape(
            id = "rect",
            shapeType = ShapeType.RECTANGLE,
            points = listOf(Offset.Zero, Offset(20f, 20f)),
            strokeColor = Color.Red,
            strokeWidth = 2f,
        )
        val base = State(elements = listOf(rect), mode = Mode.POLYGON)
        val started = reducer.reduce(base, Intent.StartPolygon(Offset(30f, 30f)))
        val cancelled = reducer.reduce(started, Intent.CancelPolygon)
        assertNull(cancelled.activePolygonId)
        assertEquals(listOf("rect"), cancelled.elements.map { it.id })
        assertTrue(cancelled.history.isEmpty())
    }

    @Test
    fun undoAfterFinishRemovesPolygonAndRedoRestoresIt() {
        val started = start()
        val second = reducer.reduce(started, Intent.AppendPolygonPoint(Offset(10f, 0f)))
        val third = reducer.reduce(second, Intent.AppendPolygonPoint(Offset(0f, 10f)))
        val finished = reducer.reduce(third, Intent.FinishPolygon)

        val undone = reducer.reduce(finished, Intent.Undo)
        assertTrue(undone.elements.isEmpty())
        assertNull(undone.activePolygonId)

        val redone = reducer.reduce(undone, Intent.Redo)
        assertEquals(1, redone.elements.size)
        assertEquals(ShapeType.POLYGON, (redone.elements.single() as Element.Shape).shapeType)
    }

    @Test
    fun switchingModeCancelsActivePolygon() {
        val started = start()
        val switched = reducer.reduce(started, Intent.SetMode(Mode.SELECT))
        assertNull(switched.activePolygonId)
        assertTrue(switched.elements.isEmpty())
        assertTrue(switched.history.isEmpty())
    }
}
