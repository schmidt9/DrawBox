package io.ak1.drawbox.text

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.zIndex
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.TextRun
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.blockStyle
import io.ak1.drawbox.domain.model.remapTextRunsAfterEdit

/**
 * Built-in inline text editor for [Element.Text]. Renders as the element
 * it edits — same font family, size, weight, style, alignment (per paragraph),
 * color, wrap width, and position — so the caret is the only visible "you are
 * editing" affordance. The element underneath is expected to be hidden by the
 * host via [io.ak1.drawbox.DrawBox]'s `hiddenElementIds` parameter to avoid
 * ghosting.
 *
 * Embedders are free to ignore this composable and ship their own editor
 * (modal, side-panel, IME-driven, etc.) — the SDK's `InsertText` /
 * `UpdateText` intents and `Element.Text` model don't depend on it. This
 * exists as the "drop-in" path for hosts that don't want to write one.
 *
 * ### Pixel fidelity with the renderer
 *
 * The implementation deliberately mirrors the SDK's text rendering path:
 *
 * - `TextStyle.fontSize` converts the screen-pixel size
 *   `element.fontSize × viewport.scale` back to `sp` through the local
 *   `Density`. The renderer lays text out in world pixels (density 1, see
 *   `WorldTextDensity`), so a raw `.sp` here would render the editor
 *   `density × fontScale` larger than the committed text on Android.
 * - `width = element.wrapWidth × viewport.scale` (no `fillMaxWidth`) — the
 *   field wraps at the same point as `TextMeasurer.measure(constraints =
 *   Constraints(maxWidth = wrapWidth))`.
 * - `lineHeight` is left unspecified in both editor and renderer so line
 *   breaks land identically.
 * - Position via `Modifier.offset { IntOffset(...) }` re-reads
 *   [Viewport.worldToScreen] each composition — pan / zoom while editing
 *   reposition smoothly.
 *
 * ### State
 *
 * [draft] / [onDraftChange] are hoisted so the host's outside-tap overlay
 * can read the current draft when committing, without coordinating focus
 * events. Recommended host flow:
 *
 * 1. When the user enters TEXT mode and taps, the SDK dispatches
 *    `Intent.TapTextAt` from the canvas tap handler. A tap on an existing
 *    top-level text selects it and emits [io.ak1.drawbox.domain.model.Event.TextEditRequested]
 *    instead of inserting; a miss inserts a new empty `Element.Text`.
 * 2. Host detects the edit request (event) or a new empty `Element.Text`
 *    (e.g. via a `LaunchedEffect(state.elements)`), records its id as
 *    `editingId`, seeds the `TextFieldValue` and the run list from the text.
 * 3. Host passes `hiddenElementIds = setOf(editingId)` to `DrawBox`.
 * 4. Host renders this composable + a full-screen click-catcher overlay.
 *    Tapping outside the field triggers commit (`updateRichText(id, text,
 *    runs)` when non-empty, else `Intent.DeleteElement(id)`). A host that has
 *    an editor open can dispatch `Intent.RequestTextEditAt` with the outside
 *    tap's world point to switch straight to the tapped text.
 *
 * ### Font family
 *
 * The family is resolved internally from [FontRegistry] via
 * [Element.Text.fontFamilyKey]. Hosts that want a different family
 * register a new key on app launch and set
 * [io.ak1.drawbox.presentation.viewmodel.DrawBoxController.setFontFamily]
 * before the next insert.
 *
 * @param element the text element being edited (its block style is the base).
 * @param viewport the current camera transform — used to project the
 *   element's world position into screen space and to scale font size.
 * @param value the host-owned draft text, selection, and composition.
 * @param runs the host-owned inline style overrides for [value]'s text.
 * @param focusRequester optional host-owned requester so a toolbar can
 *   re-focus the field after applying a style; created per element if null.
 * @param onValueChange fires on every edit with the new (spanless) value; the
 *   host must remap its run model and rebuild [value].
 * @param onTextLayout reports the field's real layout (heights for keyboard
 *   avoidance).
 *
 * [value] carries the plain text plus selection/composition; [runs] carries the
 * inline style overrides. Compose's editing buffer is plain text — every
 * [onValueChange] returns a spanless value — so the **host owns** the run model
 * and must remap it through each edit (see `remapTextRunsAfterEdit`) and rebuild
 * [value] from the returned text + selection. This composable only renders the
 * spans; it does not mutate them.
 *
 * [onTextLayout] reports the field's real layout so hosts can size/pad around
 * it (e.g. keyboard avoidance). [focusRequester] lets a host re-focus the field
 * after a toolbar action; when null a per-element requester is created.
 */
@Composable
fun InlineTextEditor(
    element: Element.Text,
    viewport: Viewport,
    value: TextFieldValue,
    runs: List<TextRun> = element.runs,
    focusRequester: FocusRequester? = null,
    onValueChange: (TextFieldValue) -> Unit,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val screenTopLeft = viewport.worldToScreen(element.topLeft)
    val screenWidth = element.wrapWidth * viewport.scale

    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthDp = with(density) { screenWidth.toDp() }
    // World pixels → screen pixels → sp so the field's glyphs match the
    // renderer's (which lays out in world pixels; see the class doc).
    val sizeToSp: (Float) -> TextUnit = { worldPx ->
        with(density) { (worldPx * viewport.scale).toSp() }
    }
    val base = element.blockStyle()
    val annotated = buildAnnotatedText(
        text = value.text,
        runs = runs,
        base = base,
        fontSizeToTextUnit = sizeToSp,
    )
    val displayValue = TextFieldValue(
        annotatedString = annotated,
        selection = value.selection,
        composition = value.composition,
    )

    val requester = focusRequester ?: remember(element.id) { FocusRequester() }
    LaunchedEffect(element.id) {
        runCatching { requester.requestFocus() }
    }

    BasicTextField(
        value = displayValue,
        onValueChange = onValueChange,
        textStyle = base.toComposeTextStyle(sizeToSp, element.color),
        cursorBrush = SolidColor(element.color),
        onTextLayout = onTextLayout,
        modifier = Modifier
            // Editor sits in the topmost compositional layer of the host
            // tree — `zIndex(10f)` puts it above the recommended click-
            // catcher overlay (z 9) and far above the canvas (z 0).
            .zIndex(10f)
            .offset {
                IntOffset(screenTopLeft.x.toInt(), screenTopLeft.y.toInt())
            }
            .width(widthDp)
            .focusRequester(requester),
    )
}

/**
 * Backwards-compatible plain-text overload: keeps its own [TextFieldValue] and
 * run list internally and reports text changes through [onDraftChange]. Rich
 * hosts should use the [TextFieldValue] overload so they can inspect the
 * selection and style substrings.
 */
@Composable
fun InlineTextEditor(
    element: Element.Text,
    viewport: Viewport,
    draft: String,
    onDraftChange: (String) -> Unit,
) {
    var value by remember(element.id) { mutableStateOf(TextFieldValue(draft)) }
    var runs by remember(element.id) { mutableStateOf(element.runs) }
    // Reflect external draft edits (e.g. a host resetting the content).
    LaunchedEffect(draft) {
        if (draft != value.text) {
            value = TextFieldValue(draft, selection = value.selection)
        }
    }
    InlineTextEditor(
        element = element,
        viewport = viewport,
        value = value,
        runs = runs,
        onValueChange = { newValue ->
            val oldText = value.text
            if (newValue.text != oldText) {
                runs = remapTextRunsAfterEdit(
                    oldText = oldText,
                    newText = newValue.text,
                    oldRuns = runs,
                    base = element.blockStyle(),
                )
            }
            value = TextFieldValue(
                text = newValue.text,
                selection = newValue.selection,
                composition = newValue.composition,
            )
            if (newValue.text != oldText) onDraftChange(newValue.text)
        },
    )
}
