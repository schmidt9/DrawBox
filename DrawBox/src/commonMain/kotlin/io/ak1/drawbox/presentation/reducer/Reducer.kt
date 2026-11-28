package io.ak1.drawbox.presentation.reducer

import androidx.compose.ui.geometry.Offset
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.HISTORY_CAP
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Layer
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.StateSnapshot
import io.ak1.drawbox.domain.model.ToolSettings
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.allElementIds
import io.ak1.drawbox.domain.model.childrenAt
import io.ak1.drawbox.domain.model.contentBounds
import io.ak1.drawbox.domain.model.excludedTopLevelIds
import io.ak1.drawbox.domain.model.findElementById
import io.ak1.drawbox.domain.model.flattenElements
import io.ak1.drawbox.domain.model.hittableElements
import io.ak1.drawbox.domain.model.isLayerNameTaken
import io.ak1.drawbox.domain.model.normalizeLayerName
import io.ak1.drawbox.domain.model.persistsSettings
import io.ak1.drawbox.domain.model.resolveTap
import io.ak1.drawbox.domain.model.sanitizeGroupPath
import io.ak1.drawbox.domain.model.sanitizeGroupPathForExcludedIds
import io.ak1.drawbox.domain.model.subtreeIdsOf
import io.ak1.drawbox.domain.model.visibleElements
import io.ak1.drawbox.domain.usecase.UseCase

/**
 * Pure state reducer implementing the MVI pattern.
 *
 * `(State, Intent) → State`. No side effects, no mutation of the input state.
 *
 * Undo model: snapshot-based. Each intent that mutates `elements` in a way the
 * user would expect to undo (insert, delete, recolor, z-order, transform-commit)
 * pushes the previous element list onto `history` and clears `future`. The
 * per-pixel update intents (`UpdateLatestPath`, `UpdateLatestShape`,
 * `MoveSelected`, `SetElementBounds`, `SetElementRotation`) do NOT push to
 * history — the caller is expected to dispatch [Intent.BeginTransform] once at
 * the start of the gesture.
 */
class Reducer(
    private val useCase: UseCase,
) {
    fun reduce(state: State, intent: Intent): State = when (intent) {
        is Intent.AddElement -> state.snapshot().copy(
            elements = useCase.addElement(intent.element, state.elements),
        )
        is Intent.UpdateElement -> state.copy(
            elements = useCase.updateElement(intent.element, state.elements),
        )
        is Intent.DeleteElement -> state.snapshot().copy(
            elements = useCase.deleteElement(intent.elementId, state.elements),
            selectedIds = state.selectedIds - intent.elementId,
            activePolygonId = if (state.activePolygonId == intent.elementId) {
                null
            } else {
                state.activePolygonId
            },
        )
        is Intent.InsertNewPath -> {
            val newPath = useCase.insertNewPath(
                intent.offset,
                state.strokeColor,
                state.strokeWidth,
                state.opacity,
                state.currentItemStrokeStyle,
            )
            state.snapshot().copy(
                elements = useCase.addElement(newPath, state.elements),
            )
        }
        is Intent.UpdateLatestPath -> state.copy(
            elements = useCase.updateLatestPath(
                intent.newPoint, state.elements, intent.pressure,
            ),
        )
        is Intent.InsertText -> insertText(state, intent)
        is Intent.TapTextAt -> {
            // Text-tool tap: edit the text under the pointer, if any. A miss
            // inserts a new empty text, exactly like InsertText.
            val hit = state.hittableElements().resolveTap(
                state.groupPath,
                intent.offset,
                intent.tolerance,
            ).target
            if (hit is Element.Text) {
                // Opening an editor is a selection change, not a document edit.
                state.copy(selectedIds = setOf(hit.id))
            } else {
                // TEXT mode never keeps a selection: a text that was just
                // edited must not stay selected while a new one is typed.
                insertText(
                    state,
                    Intent.InsertText(
                        text = "",
                        position = intent.offset,
                        fontSize = state.currentItemFontSize,
                        fontFamilyKey = state.currentItemFontFamilyKey,
                        alignment = state.currentItemTextAlignment,
                        color = state.strokeColor,
                        bold = state.currentItemTextBold,
                        italic = state.currentItemTextItalic,
                    ),
                ).copy(selectedIds = emptySet())
            }
        }
        is Intent.UpdateText -> state.snapshot().copy(
            elements = useCase.updateText(state.elements, intent.id, intent.text),
        )
        is Intent.UpdateRichText -> state.snapshot().copy(
            elements = useCase.updateRichText(
                state.elements, intent.id, intent.text, intent.runs,
            ),
        )
        is Intent.SetSelectedFontSize -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedFontSize(
                    state.elements, state.selectedIds, intent.size,
                ),
            )
        }
        is Intent.SetSelectedTextAlignment -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedTextAlignment(
                    state.elements, state.selectedIds, intent.alignment,
                ),
            )
        }
        is Intent.SetSelectedFontFamily -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedFontFamily(
                    state.elements, state.selectedIds, intent.fontFamilyKey,
                ),
            )
        }
        is Intent.SetSelectedTextBold -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedTextBold(
                    state.elements, state.selectedIds, intent.bold,
                ),
            )
        }
        is Intent.SetSelectedTextItalic -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedTextItalic(
                    state.elements, state.selectedIds, intent.italic,
                ),
            )
        }
        is Intent.SyncTextMeasuredHeight -> {
            // Short-circuit on no-op so identical layouts in successive
            // frames don't generate State copies (recompositions, listener
            // wakeups, sync-layer chatter). The 0.5px threshold matches the
            // renderer's drift gate.
            val target = state.elements.findElementById(intent.id) as? Element.Text
            if (target == null ||
                kotlin.math.abs(target.measuredHeight - intent.height) < 0.5f
            ) state
            else state.copy(
                elements = useCase.syncTextMeasuredHeight(
                    state.elements, intent.id, intent.height,
                ),
            )
        }
        is Intent.InsertImage -> {
            val newImage = useCase.insertImage(
                intent.bytes,
                intent.position,
                intent.intrinsicSize,
            )
            state.snapshot().copy(
                elements = useCase.addElement(newImage, state.elements),
            )
        }
        is Intent.InsertNewShape -> {
            val newShape = useCase.insertNewShape(
                intent.shapeType,
                intent.offset,
                state.strokeColor,
                state.strokeWidth,
                state.currentItemCornerRadius,
                state.currentItemStrokeStyle,
                state.currentItemFillColor,
                state.currentItemStrokeEnabled,
            )
            state.snapshot().copy(
                elements = useCase.addElement(newShape, state.elements),
            )
        }
        is Intent.UpdateLatestShape -> state.copy(
            elements = useCase.updateLatestShape(intent.newPoint, state.elements),
        )
        is Intent.DiscardNewShape -> {
            if (state.elements.none { it.id == intent.id }) state
            else state.copy(
                elements = useCase.deleteElement(intent.id, state.elements),
                selectedIds = state.selectedIds - intent.id,
                activePolygonId = state.activePolygonId?.takeIf { it != intent.id },
                // The gesture produced nothing, so drop the snapshot
                // InsertNewShape pushed instead of leaving a no-op undo step.
                history = state.history.dropLast(1),
            )
        }
        is Intent.DiscardLatestShape -> discardLatest(state) { element ->
            element is Element.Shape && element.shapeType == intent.shapeType
        }
        is Intent.DiscardLatestPath -> discardLatest(state) { element ->
            element is Element.Path
        }
        is Intent.StartPolygon -> {
            val polygon = useCase.insertNewPolygon(
                intent.offset,
                state.strokeColor,
                state.strokeWidth,
                state.currentItemStrokeStyle,
                state.currentItemFillColor,
                state.currentItemStrokeEnabled,
            )
            state.snapshot().copy(
                elements = useCase.addElement(polygon, state.elements),
                activePolygonId = polygon.id,
            )
        }
        is Intent.AppendPolygonPoint -> {
            val id = state.activePolygonId ?: return@reduce state
            state.copy(
                elements = useCase.appendPolygonPoint(state.elements, id, intent.point),
            )
        }
        is Intent.UpdateLatestPolygonPoint -> {
            val id = state.activePolygonId ?: return@reduce state
            state.copy(
                elements = useCase.updateLatestPolygonPoint(state.elements, id, intent.point),
            )
        }
        is Intent.FinishPolygon -> {
            val id = state.activePolygonId ?: return@reduce state
            val polygon = state.elements.firstOrNull { it.id == id } as? Element.Shape
            if (polygon == null || polygon.points.size < 3) {
                state.copy(
                    elements = useCase.deleteElement(id, state.elements),
                    selectedIds = state.selectedIds - id,
                    history = state.history.dropLast(1),
                    activePolygonId = null,
                )
            } else {
                state.copy(activePolygonId = null)
            }
        }
        is Intent.CancelPolygon -> {
            val id = state.activePolygonId ?: return@reduce state
            state.copy(
                elements = useCase.deleteElement(id, state.elements),
                selectedIds = state.selectedIds - id,
                history = state.history.dropLast(1),
                activePolygonId = null,
            )
        }
        is Intent.SetStrokeColor -> state.copy(strokeColor = intent.color)
        is Intent.SetStrokeWidth -> state.copy(strokeWidth = intent.width)
        is Intent.SetCornerRadius -> state.copy(currentItemCornerRadius = intent.radius)
        is Intent.SetStrokeStyle -> state.copy(currentItemStrokeStyle = intent.style)
        is Intent.SetFillColor -> state.copy(currentItemFillColor = intent.color)
        is Intent.SetStrokeEnabled -> state.copy(currentItemStrokeEnabled = intent.enabled)
        is Intent.SetFontSize -> state.copy(currentItemFontSize = intent.size)
        is Intent.SetFontFamily -> state.copy(currentItemFontFamilyKey = intent.fontFamilyKey)
        is Intent.SetTextAlignment -> state.copy(currentItemTextAlignment = intent.alignment)
        is Intent.SetTextBold -> state.copy(currentItemTextBold = intent.bold)
        is Intent.SetTextItalic -> state.copy(currentItemTextItalic = intent.italic)
        is Intent.SetOpacity -> state.copy(opacity = intent.opacity)
        is Intent.SetBgColor -> state.copy(bgColor = intent.bgColor)
        is Intent.SetBackgroundPattern -> state.copy(bgPattern = intent.pattern)
        is Intent.SetMode -> {
            // Per-tool memory: capture the outgoing mode's style snapshot if
            // it's a persisted mode, then restore the incoming mode's saved
            // snapshot (if any). Utility modes (SELECT, ERASER, PAN) pass
            // through settings unchanged in both directions.
            val outgoingMode = state.mode
            val snapshot = ToolSettings(
                strokeColor = state.strokeColor,
                strokeWidth = state.strokeWidth,
                strokeStyle = state.currentItemStrokeStyle,
                cornerRadius = state.currentItemCornerRadius,
                fillColor = state.currentItemFillColor,
                strokeEnabled = state.currentItemStrokeEnabled,
                fontSize = state.currentItemFontSize,
                fontFamilyKey = state.currentItemFontFamilyKey,
                textAlignment = state.currentItemTextAlignment,
                textBold = state.currentItemTextBold,
                textItalic = state.currentItemTextItalic,
            )
            val nextMemory = if (outgoingMode.persistsSettings())
                state.toolMemory + (outgoingMode to snapshot)
            else state.toolMemory
            val restored = if (intent.mode.persistsSettings())
                nextMemory[intent.mode] else null
            val nextSelected = if (intent.mode == Mode.SELECT) state.selectedIds else emptySet()
            val activeId = state.activePolygonId
            val cancelActive = activeId != null && intent.mode != Mode.POLYGON
            val nextElements = if (cancelActive) {
                useCase.deleteElement(activeId, state.elements)
            } else {
                state.elements
            }
            val nextHistory = if (cancelActive) state.history.dropLast(1) else state.history
            state.copy(
                elements = nextElements,
                history = nextHistory,
                mode = intent.mode,
                selectedIds = nextSelected,
                groupPath = if (intent.mode == Mode.SELECT) state.groupPath else emptyList(),
                marqueeRect = null,
                activePolygonId = if (intent.mode == Mode.POLYGON) activeId else null,
                toolMemory = nextMemory,
                strokeColor = restored?.strokeColor ?: state.strokeColor,
                strokeWidth = restored?.strokeWidth ?: state.strokeWidth,
                currentItemStrokeStyle = restored?.strokeStyle ?: state.currentItemStrokeStyle,
                currentItemCornerRadius = restored?.cornerRadius ?: state.currentItemCornerRadius,
                currentItemFillColor = restored?.fillColor ?: state.currentItemFillColor,
                currentItemStrokeEnabled = restored?.strokeEnabled ?: state.currentItemStrokeEnabled,
                currentItemFontSize = restored?.fontSize ?: state.currentItemFontSize,
                currentItemFontFamilyKey = restored?.fontFamilyKey ?: state.currentItemFontFamilyKey,
                currentItemTextAlignment = restored?.textAlignment ?: state.currentItemTextAlignment,
                currentItemTextBold = restored?.textBold ?: state.currentItemTextBold,
                currentItemTextItalic = restored?.textItalic ?: state.currentItemTextItalic,
            )
        }

        // Selection
        is Intent.SelectAt -> {
            val resolution = state.hittableElements().resolveTap(
                state.groupPath,
                intent.offset,
                intent.tolerance,
            )
            val target = resolution.target
            when {
                target == null -> state.copy(
                    selectedIds = emptySet(),
                    groupPath = emptyList(),
                )
                resolution.insideEnteredScope -> state.copy(selectedIds = setOf(target.id))
                else -> state.copy(
                    selectedIds = setOf(target.id),
                    groupPath = emptyList(),
                )
            }
        }
        is Intent.DoubleTapAt -> {
            val path = state.elements.sanitizeGroupPathForExcludedIds(
                state.elements.sanitizeGroupPath(state.groupPath),
                state.excludedTopLevelIds(),
            )
            val resolution = state.hittableElements().resolveTap(path, intent.offset, intent.tolerance)
            val target = resolution.target ?: return@reduce state
            if (target is Element.Group) {
                val basePath = if (resolution.insideEnteredScope) path else emptyList()
                val nextPath = basePath + target.id
                val nextTarget = resolution.hitPath.getOrNull(nextPath.size) ?: target
                state.copy(groupPath = nextPath, selectedIds = setOf(nextTarget.id))
            } else {
                state.copy(
                    selectedIds = setOf(target.id),
                    groupPath = if (resolution.insideEnteredScope) path else emptyList(),
                )
            }
        }
        is Intent.RequestTextEditAt -> {
            // Select the tapped text so the edit target is the sole selection;
            // the controller emits Event.TextEditRequested off this. Hidden and
            // locked layers are excluded so their text cannot be edited.
            val resolution = state.hittableElements().resolveTap(
                state.groupPath,
                intent.offset,
                intent.tolerance,
            )
            val hit = resolution.target
            if (hit is Element.Text) {
                state.copy(selectedIds = setOf(hit.id))
            } else {
                state
            }
        }
        is Intent.GroupSelected -> {
            val scopeIds = state.elements
                .childrenAt(state.groupPath)
                .mapTo(hashSetOf()) { it.id }
            if (state.selectedIds.count { it in scopeIds } < 2) {
                state
            } else {
                val before = state.elements.allElementIds()
                val nextElements = useCase.groupSelected(
                    state.elements,
                    state.groupPath,
                    state.selectedIds,
                )
                val newGroupId = nextElements.flattenElements()
                    .firstOrNull { it.id !in before }?.id
                if (newGroupId == null) {
                    state
                } else {
                    state.snapshot().copy(
                        elements = nextElements,
                        selectedIds = setOf(newGroupId),
                    )
                }
            }
        }
        is Intent.UngroupSelected -> {
            val group = state.selectedIds.singleOrNull()
                ?.let { state.elements.findElementById(it) as? Element.Group }
            if (group == null) {
                state
            } else {
                state.snapshot().copy(
                    elements = useCase.ungroupSelected(
                        state.elements,
                        state.groupPath,
                        state.selectedIds,
                    ),
                    selectedIds = group.children.mapTo(hashSetOf()) { it.id },
                )
            }
        }
        is Intent.DuplicateSelected -> {
            val result = useCase.duplicateSelected(
                state.elements,
                state.groupPath,
                state.selectedIds,
            )
            if (result == null) {
                state
            } else {
                state.snapshot().copy(
                    elements = result.elements,
                    selectedIds = result.selectedIds,
                )
            }
        }
        is Intent.ExitGroup -> {
            if (state.groupPath.isEmpty()) {
                state.copy(selectedIds = emptySet())
            } else {
                val exitedId = state.groupPath.last()
                state.copy(
                    groupPath = state.groupPath.dropLast(1),
                    selectedIds = setOf(exitedId),
                )
            }
        }
        is Intent.SetMarqueeRect -> state.copy(marqueeRect = intent.rect)
        is Intent.CommitMarquee -> state.copy(
            selectedIds = useCase.selectInRect(
                state.hittableElements(),
                intent.rect,
                state.groupPath,
            ),
            marqueeRect = null,
        )
        is Intent.ClearSelection -> state.copy(selectedIds = emptySet())
        is Intent.DeleteSelected -> {
            if (state.selectedIds.isEmpty()) state
            else {
                val nextElements = useCase.deleteSelected(state.elements, state.selectedIds)
                state.snapshot().copy(
                    elements = nextElements,
                    selectedIds = emptySet(),
                    groupPath = nextElements.sanitizeGroupPath(state.groupPath),
                    activePolygonId = state.activePolygonId?.takeIf { it !in state.selectedIds },
                )
            }
        }
        is Intent.BeginTransform -> state.snapshot()
        is Intent.EndTransform -> state
        is Intent.MoveSelected -> state.copy(
            elements = useCase.propagateBindings(
                useCase.translateSelected(state.elements, state.selectedIds, intent.delta),
            ),
        )
        is Intent.SetElementBounds -> state.copy(
            elements = useCase.propagateBindings(
                useCase.setElementBounds(state.elements, intent.id, intent.bounds),
            ),
        )
        is Intent.SetElementRotation -> state.copy(
            elements = useCase.propagateBindings(
                useCase.setElementRotation(state.elements, intent.id, intent.rotationDegrees),
            ),
        )
        is Intent.SetElementPoints -> state.copy(
            elements = useCase.setElementPoints(state.elements, intent.id, intent.points),
        )
        is Intent.SetLineBend -> state.copy(
            elements = useCase.setLineBend(state.elements, intent.id, intent.bend),
        )
        is Intent.FinalizeArrowBindings -> state.copy(
            // Don't snapshot here — this intent is always dispatched at the END
            // of a gesture whose START already snapshotted (InsertNewShape for
            // a freshly drawn arrow, BeginTransform for an endpoint drag). A
            // second snapshot here would split a single user action across two
            // undo entries.
            elements = useCase.propagateBindings(
                useCase.finalizeArrowBindings(state.elements, intent.id),
            ),
        )
        is Intent.SetSelectedStrokeColor -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedStrokeColor(
                    state.elements, state.selectedIds, intent.color,
                ),
            )
        }
        is Intent.SetSelectedFillColor -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedFillColor(
                    state.elements, state.selectedIds, intent.color,
                ),
            )
        }
        is Intent.SetSelectedStrokeEnabled -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedStrokeEnabled(
                    state.elements, state.selectedIds, intent.enabled,
                ),
            )
        }
        is Intent.SetSelectedStrokeWidth -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedStrokeWidth(
                    state.elements, state.selectedIds, intent.width,
                ),
            )
        }
        is Intent.SetSelectedCornerRadius -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedCornerRadius(
                    state.elements, state.selectedIds, intent.radius,
                ),
            )
        }
        is Intent.SetSelectedStrokeStyle -> {
            if (state.selectedIds.isEmpty()) state
            else state.snapshot().copy(
                elements = useCase.setSelectedStrokeStyle(
                    state.elements, state.selectedIds, intent.style,
                ),
            )
        }
        // Eraser
        is Intent.BeginErase -> {
            // Defensive reset: a previous session that was cancelled mid-sweep
            // without an EndErase would leave the dirty flag stuck.
            if (state.erasingSessionDirty) state.copy(erasingSessionDirty = false)
            else state
        }
        is Intent.EraseAt -> {
            // Snapshot lazily on the FIRST removal of the current session — a
            // tap or drag through empty space pushes nothing to history. The
            // eraseAt helper returns the same list instance when no element is
            // hit, so we can detect a miss by reference and short-circuit.
            val next = useCase.eraseAt(
                state.elements,
                intent.point,
                intent.radius,
                excludedTopLevelIds = state.excludedTopLevelIds(),
            )
            if (next === state.elements) state
            else {
                val base = if (!state.erasingSessionDirty) state.snapshot() else state
                base.copy(
                    elements = next,
                    erasingSessionDirty = true,
                    selectedIds = state.selectedIds.intersect(next.map { it.id }.toSet()),
                    activePolygonId = state.activePolygonId?.takeIf { id ->
                        next.any { it.id == id }
                    },
                )
            }
        }
        is Intent.EndErase -> {
            if (state.erasingSessionDirty) state.copy(erasingSessionDirty = false)
            else state
        }
        is Intent.SetEraserSize -> state.copy(eraserSize = intent.size)

        is Intent.BringSelectionToFront -> {
            if (state.selectedIds.isEmpty()) state
            else {
                val next = useCase.bringToFront(
                    state.elements,
                    state.groupPath,
                    state.selectedIds,
                )
                if (next === state.elements) state
                else state.snapshot().copy(elements = next)
            }
        }
        is Intent.SendSelectionToBack -> {
            if (state.selectedIds.isEmpty()) state
            else {
                val next = useCase.sendToBack(
                    state.elements,
                    state.groupPath,
                    state.selectedIds,
                )
                if (next === state.elements) state
                else state.snapshot().copy(elements = next)
            }
        }
        is Intent.MoveSelectionUp -> {
            if (state.selectedIds.isEmpty()) state
            else {
                val next = useCase.moveSelectionUp(
                    state.elements,
                    state.groupPath,
                    state.selectedIds,
                )
                if (next === state.elements) state
                else state.snapshot().copy(elements = next)
            }
        }
        is Intent.MoveSelectionDown -> {
            if (state.selectedIds.isEmpty()) state
            else {
                val next = useCase.moveSelectionDown(
                    state.elements,
                    state.groupPath,
                    state.selectedIds,
                )
                if (next === state.elements) state
                else state.snapshot().copy(elements = next)
            }
        }

        // Camera / Viewport
        is Intent.PanBy -> state.copy(viewport = state.viewport.panBy(intent.delta))
        is Intent.ZoomBy -> state.copy(
            viewport = state.viewport.zoomBy(intent.factor, intent.focalScreen),
        )
        is Intent.ZoomTo -> state.copy(
            viewport = state.viewport.zoomTo(intent.targetScale, intent.focalScreen),
        )
        is Intent.FitToScreen -> {
            val size = intent.viewportSize
            val bounds = if (size.width <= 0f || size.height <= 0f) {
                null
            } else {
                contentBounds(state.visibleElements())
            }
            if (bounds == null) {
                state
            } else {
                val next = Viewport.fit(bounds, size, intent.padding, intent.maxScale)
                if (next == state.viewport) state else state.copy(viewport = next)
            }
        }
        is Intent.ResetCamera -> state.copy(viewport = Viewport())
        is Intent.SetTempPan -> state.copy(tempPanActive = intent.active)

        // Layers
        is Intent.AddLayer -> {
            val name = normalizeLayerName(intent.name)
            if (name.isEmpty() || isLayerNameTaken(name, state.layers)) {
                state
            } else {
                state.snapshot().copy(
                    layers = state.layers + Layer(id = intent.id, name = name),
                )
            }
        }
        is Intent.RenameLayer -> {
            val layer = state.layers.firstOrNull { it.id == intent.id }
            val name = normalizeLayerName(intent.name)
            when {
                layer == null || name.isEmpty() || name == layer.name -> state
                isLayerNameTaken(name, state.layers, excludingId = intent.id) -> state
                else -> state.snapshot().copy(
                    layers = state.layers.map {
                        if (it.id == intent.id) it.copy(name = name) else it
                    },
                )
            }
        }
        is Intent.DeleteLayer -> {
            if (state.layers.none { it.id == intent.id }) {
                state
            } else {
                val removedRoots = state.elements
                    .filter { it.layerId == intent.id }
                    .mapTo(hashSetOf()) { it.id }
                val removedIds = state.elements.subtreeIdsOf(removedRoots)
                val nextElements = useCase.deleteLayerElements(state.elements, intent.id)
                state.snapshot().copy(
                    layers = state.layers.filterNot { it.id == intent.id },
                    elements = nextElements,
                    selectedIds = state.selectedIds - removedIds,
                    groupPath = nextElements.sanitizeGroupPath(
                        nextElements.sanitizeGroupPathForExcludedIds(
                            state.groupPath,
                            removedRoots,
                        ),
                    ),
                    activePolygonId = state.activePolygonId?.takeIf { it !in removedIds },
                )
            }
        }
        is Intent.SetLayerVisible -> {
            val layer = state.layers.firstOrNull { it.id == intent.id }
            if (layer == null || layer.isVisible == intent.visible) {
                state
            } else {
                val next = state.snapshot().copy(
                    layers = state.layers.map {
                        if (it.id == intent.id) it.copy(isVisible = intent.visible) else it
                    },
                )
                if (intent.visible) next else next.clearExcludedSelection()
            }
        }
        is Intent.SetLayerLocked -> {
            val layer = state.layers.firstOrNull { it.id == intent.id }
            if (layer == null || layer.isLocked == intent.locked) {
                state
            } else {
                val next = state.snapshot().copy(
                    layers = state.layers.map {
                        if (it.id == intent.id) it.copy(isLocked = intent.locked) else it
                    },
                )
                if (intent.locked) next.clearExcludedSelection() else next
            }
        }
        is Intent.SetElementsLayer -> {
            val targetMissing = intent.layerId != null &&
                state.layers.none { it.id == intent.layerId }
            if (targetMissing) {
                state
            } else {
                val next = useCase.assignLayer(
                    state.elements,
                    intent.elementIds,
                    intent.layerId,
                )
                if (next == state.elements) state
                else state.snapshot().copy(elements = next)
            }
        }

        // History
        is Intent.Undo -> {
            val prev = state.history.lastOrNull() ?: return@reduce state
            state.copy(
                elements = prev.elements,
                layers = prev.layers,
                history = state.history.dropLast(1),
                future = (state.future + StateSnapshot(state.elements, state.layers))
                    .takeLast(HISTORY_CAP),
                selectedIds = state.selectedIds.intersect(prev.elements.map { it.id }.toSet()),
                groupPath = emptyList(),
                activePolygonId = null,
            )
        }
        is Intent.Redo -> {
            val next = state.future.lastOrNull() ?: return@reduce state
            state.copy(
                elements = next.elements,
                layers = next.layers,
                future = state.future.dropLast(1),
                history = (state.history + StateSnapshot(state.elements, state.layers))
                    .takeLast(HISTORY_CAP),
                selectedIds = state.selectedIds.intersect(next.elements.map { it.id }.toSet()),
                groupPath = emptyList(),
                activePolygonId = null,
            )
        }
        is Intent.Reset -> State()

        else -> state
    }

    /**
     * Topmost element hit at [point] within [tolerance], or null. Exposes the
     * pick used by [reduce] so the controller can resolve edit targets for
     * [Event.TextEditRequested] without duplicating hit-test logic.
     */
    fun hitTopmost(elements: List<Element>, point: Offset, tolerance: Float): Element? =
        useCase.hitTopmost(elements, point, tolerance)

    /** Push current elements and layers onto [State.history] and clear [State.future]. */
    private fun State.snapshot(): State = copy(
        history = (history + StateSnapshot(elements, layers)).takeLast(HISTORY_CAP),
        future = emptyList(),
    )

    /**
     * Remove the element most recently inserted by the active drawing gesture
     * when it matches [predicate], and drop the snapshot its insertion pushed.
     * Intent processing is synchronous, so the reducer's last element is the
     * one the gesture just inserted — no round-trip through lagging UI state.
     */
    private fun discardLatest(state: State, predicate: (Element) -> Boolean): State {
        val last = state.elements.lastOrNull() ?: return state
        if (!predicate(last)) return state
        return state.copy(
            elements = useCase.deleteElement(last.id, state.elements),
            selectedIds = state.selectedIds - last.id,
            activePolygonId = state.activePolygonId?.takeIf { it != last.id },
            history = state.history.dropLast(1),
        )
    }

    /**
     * Insert [intent]'s text and snapshot history once. Shared by
     * [Intent.InsertText] and the insert fallback of [Intent.TapTextAt].
     */
    private fun insertText(state: State, intent: Intent.InsertText): State {
        val newText = useCase.insertText(
            intent.text,
            intent.position,
            intent.fontSize,
            intent.fontFamilyKey,
            intent.alignment,
            intent.color,
            bold = intent.bold,
            italic = intent.italic,
        )
        return state.snapshot().copy(
            elements = useCase.addElement(newText, state.elements),
        )
    }

    /**
     * Drops selection, group scope, and any in-progress polygon that belong to
     * a hidden or locked layer. Called after a layer becomes hidden/locked.
     */
    private fun State.clearExcludedSelection(): State {
        val excluded = excludedTopLevelIds(includeLocked = true)
        if (excluded.isEmpty()) return this
        val excludedIds = elements.subtreeIdsOf(excluded)
        return copy(
            selectedIds = selectedIds - excludedIds,
            groupPath = elements.sanitizeGroupPathForExcludedIds(groupPath, excluded),
            activePolygonId = activePolygonId?.takeIf { it !in excludedIds },
        )
    }
}
