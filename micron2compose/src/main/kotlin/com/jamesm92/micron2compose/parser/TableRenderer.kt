package com.jamesm92.micron2compose.parser

/**
 * Renders a buffered `` `t ``...`` `t `` block as box-drawing ASCII art —
 * ported from Micron2HTML/micron2kivy's `_render_table` + helpers (in turn
 * porting NomadNet's own `MarkdownToMicron.format_table_raw()` algorithm),
 * so cell content, column widths, and alignment match what real NomadNet
 * would draw for the same markdown-table input.
 *
 * Unlike the HTML/Kivy targets — which feed each rendered row back through
 * their own line-processing function to become N separate block-level
 * elements wrapped in a container — this produces a *single* [Block]
 * containing every row's runs joined by newline [TextRun]s. A Compose
 * `Text` renders embedded newlines natively, and one block is the more
 * natural fit for [ConvertResult.anchors]' block-granularity model anyway.
 * Table alignment is likewise set directly on that one [Block] rather than
 * threaded through [DocState.align] the way the line-based ports need to.
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
private val MICRON_TOKEN_RE = Regex(
    "`[FB]T[0-9a-fA-F]{6}" +
        "|`[FB][0-9a-fA-F]{3}" +
        "|`[!*_=fb]"
)

private enum class BorderKind { TOP, MID, BOTTOM }

/** Split a markdown-table row into cells on unescaped `|`. */
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
            ch == '\\' -> { current.append(ch); escaped = true }
            ch == '|' -> { cells.add(current.toString().trim()); current.setLength(0) }
            else -> current.append(ch)
        }
    }
    cells.add(current.toString().trim())
    return cells
}

/**
 * Parse a markdown separator row (`:---:`/`--:`/`---`) per column. The
 * escaped-backslash sequence kept in each cell by [parseTableRow] (e.g.
 * `\|` staying as literal backslash-plus-pipe) is intentional — it's
 * resolved later when the assembled row text is fed through [parseInline],
 * whose own backslash-escape handling collapses it to a literal `|`.
 */
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

/**
 * Character width of a cell, ignoring Micron formatting tokens. NomadNet's
 * own implementation also consults `wcwidth` for double-width glyphs;
 * deliberately not ported here (would add a runtime dependency this
 * library doesn't otherwise need) — documented simplification, same as the
 * HTML/Kivy siblings.
 */
private fun visibleWidth(text: String): Int = MICRON_TOKEN_RE.replace(text, "").length

/** Pad (or, if needed, truncate) a cell to [width] visible columns. */
private fun padCell(text: String, width: Int, align: Align): String {
    var t = text
    var visible = visibleWidth(t)
    if (visible > width) {
        // Truncating mid-token could leave a dangling format token —
        // dropping formatting on truncation is strictly safer than that.
        t = MICRON_TOKEN_RE.replace(t, "").take(width)
        visible = t.length
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
 * Greedily shrink the widest column until the table fits [maxWidth].
 * Faithful-effort port of NomadNet's "proportionally shrink the widest
 * columns" — not a byte-for-byte match of its exact formula, which isn't
 * fully specified in the reference source (same documented simplification
 * as the HTML/Kivy siblings).
 */
private fun shrinkTableWidths(colWidths: List<Int>, maxWidth: Int): List<Int> {
    val widths = colWidths.toMutableList()
    val ncols = widths.size
    var total = widths.sum() + ncols * 3 + 1
    while (total > maxWidth && (widths.maxOrNull() ?: 0) > TABLE_MIN_COL_WIDTH) {
        val j = widths.indexOf(widths.max())
        widths[j] -= 1
        total -= 1
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
 * matches the HTML/Kivy siblings' same guard, part of the "never crash,
 * render best-effort" posture for malformed input.
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
        align = resolveAlignChar(alignChar) ?: Align.LEFT,
        indent = indentLevel(doc),
    )
}
