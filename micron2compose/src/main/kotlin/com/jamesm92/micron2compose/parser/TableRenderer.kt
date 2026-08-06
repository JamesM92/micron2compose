package com.jamesm92.micron2compose.parser

/**
 * Renders a buffered `` `t ``...`` `t `` block as box-drawing ASCII art —
 * ported from NomadNet's own `MarkdownToMicron.format_table_raw()` (RNS's
 * `RNS/Utilities/rngit/util.py`, fetched and read directly from live
 * upstream for this pass, not paraphrased or approximated) so cell
 * content, column widths, alignment, and the width-shrink behavior match
 * what real NomadNet would draw for the same markdown-table input.
 *
 * Unlike the HTML/Kivy targets — which feed each rendered row back through
 * their own line-processing function to become N separate block-level
 * elements wrapped in a container — this produces a *single* [Block]
 * containing every row's runs joined by newline [TextRun]s. A Compose
 * `Text` renders embedded newlines natively, and one block is the more
 * natural fit for [ConvertResult.anchors]' block-granularity model anyway.
 * Table alignment is likewise set directly on that one [Block] rather than
 * threaded through [DocState.align] the way the line-based ports need to
 * (upstream achieves the same "this table renders aligned, and alignment
 * reverts after" effect by literally emitting `` `{align} ``/`` `a ``
 * lines around the table's rows for the line-based renderer to consume —
 * not needed here since one [Block] just gets one [Align] directly).
 */

internal const val TABLE_MIN_COL_WIDTH = 3

private const val TABLE_H = "─"
private const val TABLE_V = "│"
private const val TABLE_TL = "┌"
private const val TABLE_TR = "┐"
private const val TABLE_BL = "└"
private const val TABLE_BR = "┘"
private const val TABLE_ML = "├"
private const val TABLE_MR = "┤"
private const val TABLE_TM = "┬"
private const val TABLE_BM = "┴"
private const val TABLE_MM = "┼"

// Strips color/bold/italic/underline/reset tokens for width-measurement
// purposes only — a cell's *visible* width shouldn't count its formatting.
// Matches upstream's `_visible_width` regex set exactly (its five
// sequential `re.sub` passes collapse into one alternation here since none
// of the patterns can appear nested inside another — same end result).
private val MICRON_TOKEN_RE = Regex(
    "`[FB]T[0-9a-fA-F]{6}" +
        "|`[FB][0-9a-fA-F]{3}" +
        "|`[!*_=fb]"
)

private enum class BorderKind { TOP, MID, BOTTOM }

/**
 * Split a markdown-table row into cells on unescaped `|`. Matches
 * upstream's `_parse_table_row` exactly: a `\|` drops the backslash
 * entirely and keeps just the literal `|` in the cell (unlike a naive
 * "keep the backslash for later" approach — there's no second escape pass
 * needed since the assembled row text's `|` has no special meaning to
 * [parseInline] anyway).
 */
private fun parseTableRow(line: String): List<String> {
    var l = line.trim()
    if (l.startsWith("|")) l = l.substring(1)
    if (l.endsWith("|")) l = l.substring(0, l.length - 1)

    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (ch in l) {
        when {
            escaped -> { current.append(ch); escaped = false }
            ch == '\\' -> escaped = true
            ch == '|' -> { cells.add(current.toString().trim()); current.setLength(0) }
            else -> current.append(ch)
        }
    }
    cells.add(current.toString().trim())
    return cells
}

/** Parse a markdown separator row (`:---:`/`--:`/`---`) per column. */
private fun parseTableAlignments(cells: List<String>, ncols: Int): List<Align> {
    val aligns = cells.map { cell ->
        val c = cell.trim()
        when {
            c.startsWith(":") && c.endsWith(":") -> Align.CENTER
            c.endsWith(":") -> Align.RIGHT
            else -> Align.LEFT
        }
    }.toMutableList()
    while (aligns.size < ncols) aligns.add(Align.LEFT)
    return aligns.take(ncols)
}

// ---------------------------------------------------------------------------
// Display width
//
// Upstream measures column width with Python's `wcwidth` package when it's
// importable, falling back to `len()` otherwise (its own `display_width`,
// read directly from source). This library has no runtime dependencies and
// isn't adding one just for this, but unlike the HTML/Kivy siblings (which
// skip wide-character awareness entirely), a full-width-aware measurement
// needs no external package here — it's a fixed, stable set of Unicode
// ranges (the "Wide"/"Fullwidth" categories from EastAsianWidth.txt),
// implementable as plain range checks. "Ambiguous"-width characters are
// deliberately left at width 1, matching wcwidth's own default behavior
// (its East Asian context flag defaults to off).
// ---------------------------------------------------------------------------

private fun isWideCodePoint(cp: Int): Boolean = when {
    cp in 0x1100..0x115F -> true // Hangul Jamo
    cp in 0x2E80..0x303E -> true // CJK Radicals Supplement .. CJK Symbols and Punctuation
    cp in 0x3041..0x33FF -> true // Hiragana .. CJK Compatibility
    cp in 0x3400..0x4DBF -> true // CJK Unified Ideographs Extension A
    cp in 0x4E00..0x9FFF -> true // CJK Unified Ideographs
    cp in 0xA000..0xA4CF -> true // Yi Syllables / Yi Radicals
    cp in 0xAC00..0xD7A3 -> true // Hangul Syllables
    cp in 0xF900..0xFAFF -> true // CJK Compatibility Ideographs
    cp in 0xFE30..0xFE4F -> true // CJK Compatibility Forms
    cp in 0xFF00..0xFF60 -> true // Fullwidth Forms
    cp in 0xFFE0..0xFFE6 -> true // Fullwidth Signs
    cp in 0x20000..0x2FFFD -> true // CJK Unified Ideographs Extension B and beyond (supplementary)
    cp in 0x30000..0x3FFFD -> true // CJK Unified Ideographs Extension G and beyond (supplementary)
    else -> false
}

/** Codepoint-aware display width — a surrogate pair counts as one character. */
private fun displayWidth(text: String): Int {
    var width = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        width += if (isWideCodePoint(cp)) 2 else 1
        i += Character.charCount(cp)
    }
    return width
}

/**
 * Character width of a cell, ignoring Micron formatting tokens and
 * accounting for double-width glyphs (see "Display width" above).
 */
private fun visibleWidth(text: String): Int = displayWidth(MICRON_TOKEN_RE.replace(text, ""))

/**
 * Truncates to at most [maxWidth] display columns, stopping before any
 * codepoint that would overshoot it (so a wide character is never split,
 * and the result never renders wider than requested even when the last
 * character that fits is narrow but the next is wide).
 */
private fun truncateToWidth(text: String, maxWidth: Int): String {
    val sb = StringBuilder()
    var width = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val cw = if (isWideCodePoint(cp)) 2 else 1
        if (width + cw > maxWidth) break
        sb.appendCodePoint(cp)
        width += cw
        i += Character.charCount(cp)
    }
    return sb.toString()
}

/** Pad (or, if needed, truncate) a cell to [width] visible columns. */
private fun padCell(text: String, width: Int, align: Align): String {
    var t = text
    var visible = visibleWidth(t)
    if (visible > width) {
        // Truncating mid-token could leave a dangling format token —
        // stripping formatting before truncating is strictly safer than
        // that. (Upstream's own truncation operates on raw, un-stripped
        // text and can leave a dangling token in this same situation — its
        // own comment acknowledges this is unresolved, so this is a
        // deliberate, safety-motivated deviation, not an oversight.)
        t = truncateToWidth(MICRON_TOKEN_RE.replace(t, ""), width)
        visible = displayWidth(t)
    }
    val pad = width - visible
    return when (align) {
        Align.RIGHT -> " ".repeat(pad) + t
        Align.CENTER -> {
            val left = pad / 2
            " ".repeat(left) + t + " ".repeat(pad - left)
        }
        Align.LEFT -> t + " ".repeat(pad)
    }
}

/**
 * Shrinks column widths to fit [maxWidth]: sort columns widest-first, then
 * drain each one down to [TABLE_MIN_COL_WIDTH] (or just enough to cover
 * the remaining excess, whichever is less) before moving to the
 * next-widest. Ported verbatim from upstream's real algorithm — not the
 * "shrink the single widest column by one repeatedly" approximation this
 * library shipped with initially (that guess predated finding upstream's
 * actual source and used a materially different distribution).
 */
private fun shrinkTableWidths(colWidths: List<Int>, maxWidth: Int): List<Int> {
    val widths = colWidths.toMutableList()
    val ncols = widths.size
    val total = widths.sum() + ncols * 3 + 1
    if (total <= maxWidth) return widths

    var excess = total - maxWidth
    val widestFirst = widths.indices.sortedByDescending { widths[it] }
    for (idx in widestFirst) {
        if (excess <= 0) break
        val reduction = minOf(excess, widths[idx] - TABLE_MIN_COL_WIDTH)
        widths[idx] -= reduction
        excess -= reduction
    }
    return widths
}

private fun tableBorder(colWidths: List<Int>, kind: BorderKind): String {
    val (left, mid, right) = when (kind) {
        BorderKind.TOP -> Triple(TABLE_TL, TABLE_TM, TABLE_TR)
        BorderKind.MID -> Triple(TABLE_ML, TABLE_MM, TABLE_MR)
        BorderKind.BOTTOM -> Triple(TABLE_BL, TABLE_BM, TABLE_BR)
    }
    return left + colWidths.joinToString(mid) { TABLE_H.repeat(it + 2) } + right
}

private fun tableRowText(cells: List<String>, colWidths: List<Int>, aligns: List<Align>): String {
    val padded = cells.indices.map { padCell(cells[it], colWidths[it], aligns[it]) }
    return "$TABLE_V " + padded.joinToString(" $TABLE_V ") + " $TABLE_V"
}

private fun resolveAlignChar(c: String): Align? = when (c) {
    "l" -> Align.LEFT
    "c" -> Align.CENTER
    "r" -> Align.RIGHT
    else -> null
}

/**
 * Renders a complete `` `t ``...`` `t `` block. `rawLines` is everything
 * buffered between the opening and closing toggle lines (markdown-style
 * pipe rows: header, alignment separator, then data rows). Returns null if
 * there are fewer than 2 raw lines (no header + separator to parse) —
 * matches upstream's own `if len(rows) < 2: return rows` guard, part of
 * the "never crash, render best-effort" posture for malformed input.
 */
internal fun renderTable(
    rawLines: List<String>,
    alignChar: String,
    maxWidth: Int,
    nodeHash: String,
    basePath: String,
    doc: DocState,
    urlResolver: UrlResolver,
): Block? {
    if (rawLines.size < 2) return null

    val headerCells = parseTableRow(rawLines[0])
    val ncols = headerCells.size
    val aligns = parseTableAlignments(parseTableRow(rawLines[1]), ncols)

    val dataRows = rawLines.drop(2).map { raw ->
        val cells = parseTableRow(raw)
        (cells + List(ncols) { "" }).take(ncols)
    }

    val colWidths = MutableList(ncols) { TABLE_MIN_COL_WIDTH }
    for (row in listOf(headerCells) + dataRows) {
        for (j in row.indices) {
            colWidths[j] = maxOf(colWidths[j], visibleWidth(row[j]))
        }
    }
    val shrunkWidths = shrinkTableWidths(colWidths, maxWidth)

    val rowTexts = mutableListOf<String>()
    rowTexts.add(tableBorder(shrunkWidths, BorderKind.TOP))
    rowTexts.add(tableRowText(headerCells, shrunkWidths, List(ncols) { Align.LEFT }))
    rowTexts.add(tableBorder(shrunkWidths, BorderKind.MID))
    for (row in dataRows) rowTexts.add(tableRowText(row, shrunkWidths, aligns))
    rowTexts.add(tableBorder(shrunkWidths, BorderKind.BOTTOM))

    val combinedRuns = mutableListOf<InlineRun>()
    for ((idx, rowText) in rowTexts.withIndex()) {
        if (idx > 0) combinedRuns.add(TextRun("\n"))
        combinedRuns.addAll(parseInline(rowText, nodeHash, basePath, doc, urlResolver))
    }

    return Block(
        runs = combinedRuns,
        kind = BlockKind.TABLE,
        // No explicit `` `t[lcr] `` char -> inherit whatever alignment was
        // already active, matching upstream (which only emits a `` `{align} ``
        // wrapper line when one was actually specified — otherwise the
        // table's rows render under whatever the surrounding text's current
        // alignment already was, not a hard reset to left).
        align = resolveAlignChar(alignChar) ?: doc.align,
        indent = indentLevel(doc),
    )
}
