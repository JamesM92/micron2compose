package com.jamesm92.micron2compose.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.jamesm92.micron2compose.parser.AnchorRun
import com.jamesm92.micron2compose.parser.Block
import com.jamesm92.micron2compose.parser.FieldRun
import com.jamesm92.micron2compose.parser.FieldSpec
import com.jamesm92.micron2compose.parser.FieldType
import com.jamesm92.micron2compose.parser.LinkRun
import com.jamesm92.micron2compose.parser.LinkTarget
import com.jamesm92.micron2compose.parser.PartialRun
import com.jamesm92.micron2compose.parser.TextRun

/**
 * A [Block] turned into real Compose UI material: the [AnnotatedString] to
 * hand to a `Text` composable, plus the `inlineContent` map that same
 * `Text` needs for any [FieldRun]s embedded in the flow (Compose's
 * `appendInlineContent`/`InlineTextContent` mechanism — the same technique
 * used for inline icons, used here to embed a real `TextField`/`Checkbox`/
 * `RadioButton` mid-text).
 */
data class MicronInlineContent(
    val annotatedString: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

/**
 * Builds (and memoizes by [block]) the Compose material for one [Block].
 *
 * The link-click callback is threaded through [rememberUpdatedState] rather
 * than being a `remember` key itself — the built [AnnotatedString]/
 * `inlineContent` only need rebuilding when the *content* changes
 * ([block], [readOnly], or swapping [formState] entirely), never merely
 * because a caller passed a fresh lambda instance on this recomposition;
 * every [LinkAnnotation] built here always calls whatever `onLinkClick` is
 * current at click-time regardless.
 */
@Composable
fun rememberMicronBlockContent(
    block: Block,
    readOnly: Boolean,
    formState: MicronFormState,
    onLinkClick: (LinkTarget) -> Unit,
): MicronInlineContent {
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    return remember(block, readOnly, formState) {
        buildMicronBlockContent(block, readOnly, formState) { target -> currentOnLinkClick(target) }
    }
}

private fun buildMicronBlockContent(
    block: Block,
    readOnly: Boolean,
    formState: MicronFormState,
    onLinkClick: (LinkTarget) -> Unit,
): MicronInlineContent {
    val inlineContentMap = mutableMapOf<String, InlineTextContent>()
    var fieldCounter = 0

    val annotated = buildAnnotatedString {
        for (run in block.runs) {
            when (run) {
                is TextRun -> withStyle(run.toSpanStyle()) { append(run.text) }

                is LinkRun -> withLink(
                    LinkAnnotation.Clickable(
                        tag = "micron_link",
                        linkInteractionListener = LinkInteractionListener { onLinkClick(run.target) },
                    )
                ) { append(run.label) }

                // Data-only placeholder — no live re-fetching. See
                // PartialRun/LinkTarget docs for why, and what metadata
                // rides along on the click for a host app to act on.
                is PartialRun -> withLink(
                    LinkAnnotation.Clickable(
                        tag = "micron_partial",
                        linkInteractionListener = LinkInteractionListener { onLinkClick(run.target) },
                    )
                ) { append("[live]") }

                is FieldRun -> {
                    val id = "micron_field_${fieldCounter++}"
                    inlineContentMap[id] = fieldInlineContent(run.spec, readOnly, formState)
                    appendInlineContent(id, "[${run.spec.name}]")
                }

                // Zero-width — no visual output. Scroll-to-anchor works off
                // ConvertResult.anchors' block index, not a marker here.
                is AnchorRun -> Unit
            }
        }
    }

    return MicronInlineContent(annotated, inlineContentMap)
}

private fun TextRun.toSpanStyle(): SpanStyle = SpanStyle(
    color = fgColor?.let(::parseHexColor) ?: Color.Unspecified,
    background = bgColor?.let(::parseHexColor) ?: Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = if (underline) TextDecoration.Underline else null,
)

/** Parses a "#rrggbb" string — the only shape [TextRun]'s colors ever carry. */
private fun parseHexColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val r = clean.substring(0, 2).toInt(16)
    val g = clean.substring(2, 4).toInt(16)
    val b = clean.substring(4, 6).toInt(16)
    return Color(r, g, b)
}

/**
 * Builds the real interactive composable for one form field, embedded
 * inline in the surrounding text via [InlineTextContent].
 *
 * Field values are seeded once from [formState] (falling back to the
 * field's own default/preselected state) into local `remember`ed state,
 * which then drives the widget and mirrors edits back out to [formState]
 * for a host app to read — except RADIO, which has no per-widget local
 * state at all: sibling radio buttons for the same field name are
 * independent [InlineTextContent] instances that only agree on which one
 * is selected by reading [formState] directly on every recomposition, so
 * that has to be the live source of truth, not something seeded once.
 * [rememberDefaultMicronFormState] backs its values with Compose state
 * precisely so those reads participate in recomposition correctly.
 */
private fun fieldInlineContent(
    spec: FieldSpec,
    readOnly: Boolean,
    formState: MicronFormState,
): InlineTextContent {
    val widthEm = (spec.width.coerceIn(4, 40) * 0.55).em

    return InlineTextContent(
        placeholder = Placeholder(
            width = if (spec.type == FieldType.CHECKBOX || spec.type == FieldType.RADIO) 1.8.em else widthEm,
            height = 1.8.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        )
    ) {
        when (spec.type) {
            FieldType.TEXT, FieldType.PASSWORD -> {
                var value by remember(spec.name) {
                    mutableStateOf(formState.getValue(spec.name).ifEmpty { spec.defaultValue })
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { new ->
                        value = new
                        formState.setValue(spec.name, new)
                    },
                    readOnly = readOnly,
                    singleLine = true,
                    visualTransformation = if (spec.type == FieldType.PASSWORD) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = if (spec.type == FieldType.PASSWORD) {
                        KeyboardOptions(keyboardType = KeyboardType.Password)
                    } else {
                        KeyboardOptions.Default
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            FieldType.CHECKBOX -> {
                var checked by remember(spec.name, spec.optionValue) {
                    mutableStateOf(
                        formState.getValue(spec.name).let { it == spec.optionValue || it == "true" }
                            || (formState.getValue(spec.name).isEmpty() && spec.preselected)
                    )
                }
                Checkbox(
                    checked = checked,
                    onCheckedChange = { new ->
                        checked = new
                        formState.setValue(spec.name, if (new) spec.optionValue else "")
                    },
                    enabled = !readOnly,
                )
            }

            FieldType.RADIO -> {
                val current = formState.getValue(spec.name)
                val selected = if (current.isNotEmpty()) {
                    current == spec.optionValue
                } else {
                    spec.preselected
                }
                RadioButton(
                    selected = selected,
                    onClick = { formState.setValue(spec.name, spec.optionValue) },
                    enabled = !readOnly,
                )
            }
        }
    }
}
