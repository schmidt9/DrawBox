package io.ak1.drawbox.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.TextBlockStyle
import io.ak1.drawbox.domain.model.TextRun
import io.ak1.drawbox.domain.model.blockStyle
import io.ak1.drawbox.domain.model.normalizeTextRuns
import io.ak1.drawbox.domain.model.textParagraphSpans

/** Compose text alignment for a DrawBox [TextAlignment]. */
fun TextAlignment.toComposeTextAlign(): TextAlign = when (this) {
    TextAlignment.LEFT -> TextAlign.Left
    TextAlignment.CENTER -> TextAlign.Center
    TextAlignment.RIGHT -> TextAlign.Right
}

/**
 * Compose base style for a block-level [TextBlockStyle]. [fontSizeToTextUnit]
 * maps a world-pixel size to the layout unit: the renderer measures at
 * `WorldTextDensity` so `sp` == world px, while the inline editor converts
 * through the device density and viewport scale.
 */
fun TextBlockStyle.toComposeTextStyle(
    fontSizeToTextUnit: (Float) -> TextUnit = { it.sp },
    color: Color = Color.Unspecified,
): TextStyle = TextStyle(
    fontSize = fontSizeToTextUnit(fontSize),
    fontFamily = FontRegistry.resolve(fontFamilyKey),
    textAlign = alignment.toComposeTextAlign(),
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    color = color,
)

/**
 * Build the styled text for [text] with [runs] over [base].
 *
 * Span runs become [SpanStyle] overrides; paragraph alignment overrides become
 * one [ParagraphStyle] per affected paragraph (its range includes the trailing
 * `\n`, so the newline stays in the same paragraph). Uniform text returns a
 * plain [AnnotatedString], keeping the pre-rich-text layout byte-identical.
 */
fun buildAnnotatedText(
    text: String,
    runs: List<TextRun>,
    base: TextBlockStyle,
    fontSizeToTextUnit: (Float) -> TextUnit = { it.sp },
): AnnotatedString {
    if (runs.isEmpty()) return AnnotatedString(text)
    val normalized = normalizeTextRuns(text, runs, base)
    if (normalized.isEmpty()) return AnnotatedString(text)

    val builder = AnnotatedString.Builder(text)
    for (run in normalized) {
        val span = SpanStyle(
            fontFamily = run.fontFamilyKey?.let { FontRegistry.resolve(it) },
            fontSize = run.fontSize?.let { fontSizeToTextUnit(it) } ?: TextUnit.Unspecified,
            fontWeight = run.bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
            fontStyle = run.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
        )
        builder.addStyle(span, run.start, run.end)
    }
    for (paragraph in textParagraphSpans(text)) {
        if (paragraph.isEmpty) continue
        val alignment = normalized.firstOrNull {
            it.alignment != null && it.start >= paragraph.start && it.start < paragraph.end
        }?.alignment ?: continue
        if (alignment == base.alignment) continue
        builder.addStyle(
            ParagraphStyle(textAlign = alignment.toComposeTextAlign()),
            paragraph.start,
            paragraph.end,
        )
    }
    return builder.toAnnotatedString()
}

/** Styled text for this element in world-pixel units (renderer measurement). */
fun Element.Text.toAnnotatedString(
    fontSizeToTextUnit: (Float) -> TextUnit = { it.sp },
): AnnotatedString = buildAnnotatedText(
    text = text,
    runs = runs,
    base = blockStyle(),
    fontSizeToTextUnit = fontSizeToTextUnit,
)
