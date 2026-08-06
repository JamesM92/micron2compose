package com.jamesm92.micron2compose.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import com.jamesm92.micron2compose.parser.ConvertResult
import com.jamesm92.micron2compose.parser.LinkTarget
import com.jamesm92.micron2compose.parser.MicronConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Fetches the raw Micron text for a partial's target. The library has no
 * networking of its own — a host app supplies this, backed by whatever it
 * already uses to talk to a NomadNet node (RNS/LXMF, or anything else).
 * Thrown exceptions are caught by the caller (see [MicronBlock]'s
 * `onFetchPartial` docs) and don't crash the composition; the last
 * successfully-fetched content (or the loading placeholder, if none yet)
 * stays on screen and the next scheduled refresh tries again.
 */
typealias PartialFetcher = suspend (LinkTarget) -> String

/**
 * Renders a live-refreshing `` `{...} `` partial: fetches once immediately,
 * then — if [LinkTarget.partialRefresh] is set — re-fetches on that
 * interval for as long as this composable stays in composition, replacing
 * its content in place each time. Recurses through [MicronBlock] for the
 * fetched content, so a partial whose content itself contains a partial
 * (or a form field, a table, anything) just works.
 *
 * Never shown directly — [MicronBlock] switches to this internally for
 * `BlockKind.PARTIAL` blocks when a fetcher is supplied, and falls back to
 * the plain static "[live]" placeholder otherwise.
 */
@Composable
internal fun MicronLivePartial(
    target: LinkTarget,
    fetcher: PartialFetcher,
    formState: MicronFormState,
    readOnly: Boolean,
    blankLineHeight: Dp,
    fontFamily: FontFamily,
    monospaceFontFamily: FontFamily,
    onLinkClick: (LinkTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var result by remember(target) { mutableStateOf<ConvertResult?>(null) }

    LaunchedEffect(target) {
        // A partial's fetched content is resolved against *its own*
        // node/path, not whatever page embedded the `{...} — extracted
        // from its already-resolved url (real NomadNet-parity default
        // resolver form, "hash://<hash>/<path>"; falls back to empty for
        // a custom resolver's own scheme, same as any other unresolvable
        // relative-link context).
        val (nodeHash, basePath) = parseHashUrl(target.url)
        val converter = MicronConverter()
        while (isActive) {
            try {
                val raw = fetcher(target)
                result = converter.convert(raw, nodeHash, basePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing whatever's already on screen (the loading
                // placeholder, or the last successful fetch) and try
                // again on the next scheduled cycle — never crash the
                // composition over a single failed fetch.
            }
            val refreshSeconds = target.partialRefresh
            if (refreshSeconds == null || refreshSeconds < 1) break
            delay((refreshSeconds * 1000).toLong())
        }
    }

    val current = result
    if (current == null) {
        // Matches real NomadNet's own initial-loading placeholder glyph
        // (MicronParser.py's parse_partial: urwid.Text(f"⧖")).
        Text(text = "⧖", fontFamily = fontFamily, modifier = modifier)
    } else {
        Column(modifier = modifier) {
            for (nestedBlock in current.blocks) {
                MicronBlock(
                    block = nestedBlock,
                    formState = formState,
                    readOnly = readOnly,
                    blankLineHeight = blankLineHeight,
                    fontFamily = fontFamily,
                    monospaceFontFamily = monospaceFontFamily,
                    onFetchPartial = fetcher,
                    onLinkClick = onLinkClick,
                )
            }
        }
    }
}

/** Splits a "hash://<hash>/<path>" url into (nodeHash, basePath); ("", "") if it doesn't match that shape. */
private fun parseHashUrl(url: String): Pair<String, String> {
    if (!url.startsWith("hash://")) return "" to ""
    val rest = url.removePrefix("hash://")
    val slash = rest.indexOf('/')
    return if (slash == -1) rest to "/" else rest.substring(0, slash) to rest.substring(slash)
}
