package com.jamesm92.micron2compose.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jamesm92.micron2compose.parser.Align
import com.jamesm92.micron2compose.parser.Block
import com.jamesm92.micron2compose.parser.BlockKind
import com.jamesm92.micron2compose.parser.ConvertResult
import com.jamesm92.micron2compose.parser.LinkTarget

/**
 * The batteries-included Micron renderer: a `LazyColumn` of [Block]s, one
 * composable per block, with scroll-to-anchor support driven by
 * [ConvertResult.anchors]' block-index map. For custom layouts, use
 * [MicronBlock] directly instead of this.
 *
 * @param formState Backing store for form field values — see
 *   [MicronFormState]. Defaults to a simple in-memory one.
 * @param readOnly Whether form fields render as interactive or disabled —
 *   a rendering decision, kept separate from parsing (see
 *   [com.jamesm92.micron2compose.parser.MicronConverter]'s docs on why).
 * @param scrollToAnchor When set to a name present in [ConvertResult.anchors],
 *   scrolls to that block. Changing this value re-triggers the scroll.
 * @param fontFamily Font for regular text/heading blocks. Defaults to
 *   whatever the surrounding theme already provides.
 * @param monospaceFontFamily Font for TABLE/LITERAL blocks and dividers —
 *   anything built from repeated box-drawing/Braille characters, where a
 *   real monospace face (ideally one with full Braille/box-drawing glyph
 *   coverage — a generic system monospace font often doesn't have one)
 *   matters for correct alignment. Defaults to [FontFamily.Monospace].
 * @param onLinkClick Invoked with the resolved [LinkTarget] whenever a link
 *   or partial placeholder is tapped.
 */
@Composable
fun MicronPage(
    result: ConvertResult,
    modifier: Modifier = Modifier,
    formState: MicronFormState = rememberDefaultMicronFormState(),
    readOnly: Boolean = false,
    scrollToAnchor: String? = null,
    listState: LazyListState = rememberLazyListState(),
    blankLineHeight: Dp = 16.dp,
    fontFamily: FontFamily = FontFamily.Default,
    monospaceFontFamily: FontFamily = FontFamily.Monospace,
    onLinkClick: (LinkTarget) -> Unit = {},
) {
    LaunchedEffect(scrollToAnchor, result) {
        val index = scrollToAnchor?.let { result.anchors[it] }
        if (index != null && index in result.blocks.indices) {
            listState.animateScrollToItem(index)
        }
    }

    LazyColumn(modifier = modifier, state = listState) {
        itemsIndexed(result.blocks) { _, block ->
            MicronBlock(
                block = block,
                formState = formState,
                readOnly = readOnly,
                blankLineHeight = blankLineHeight,
                fontFamily = fontFamily,
                monospaceFontFamily = monospaceFontFamily,
                onLinkClick = onLinkClick,
            )
        }
    }
}

/**
 * Renders a single [Block] — the piece [MicronPage] uses per `LazyColumn`
 * item, exposed directly for callers who want their own layout container
 * instead of a `LazyColumn` (e.g. mixing Micron blocks into a larger
 * `Column` alongside other app UI).
 *
 * See [MicronPage]'s docs for [fontFamily]/[monospaceFontFamily].
 */
@Composable
fun MicronBlock(
    block: Block,
    modifier: Modifier = Modifier,
    formState: MicronFormState = rememberDefaultMicronFormState(),
    readOnly: Boolean = false,
    blankLineHeight: Dp = 16.dp,
    fontFamily: FontFamily = FontFamily.Default,
    monospaceFontFamily: FontFamily = FontFamily.Monospace,
    onLinkClick: (LinkTarget) -> Unit = {},
) {
    if (block.kind == BlockKind.BLANK) {
        Spacer(modifier = modifier.height(blankLineHeight))
        return
    }

    val indentPadding = (block.indent * 16).dp

    if (block.kind == BlockKind.DIVIDER) {
        // Real NomadNet always repeats a literal character for a divider —
        // even the "default" case is just U+2500 repeated, not a rule the
        // platform/theme decides (see Block.dividerChar's docs). A long
        // repeated string clipped to the available width is the simplest
        // Compose-native way to get that same "fills the row" behavior
        // without measuring anything.
        Text(
            text = block.dividerChar.toString().repeat(200),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            fontFamily = monospaceFontFamily,
            modifier = modifier.fillMaxWidth().padding(start = indentPadding),
        )
        return
    }

    val content = rememberMicronBlockContent(block, readOnly, formState, onLinkClick)
    val monospace = block.kind == BlockKind.TABLE || block.kind == BlockKind.LITERAL

    Text(
        text = content.annotatedString,
        inlineContent = content.inlineContent,
        modifier = modifier.fillMaxWidth().padding(start = indentPadding),
        fontFamily = if (monospace) monospaceFontFamily else fontFamily,
        fontWeight = if (block.kind == BlockKind.HEADING) FontWeight.Bold else null,
        fontSize = headingFontSize(block.headingLevel),
        textAlign = block.align.toTextAlign(),
    )
}

private fun headingFontSize(level: Int) = when (level) {
    1 -> 24.sp
    2 -> 20.sp
    3 -> 18.sp
    else -> androidx.compose.ui.unit.TextUnit.Unspecified
}

private fun Align.toTextAlign(): TextAlign = when (this) {
    Align.LEFT -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.RIGHT -> TextAlign.End
}
