package com.jamesm92.micron2compose.parser

/**
 * Document-level state that persists across lines — the "formatting state
 * is global/streaming across the whole document, not per-line" model this
 * library's design notes call out. Ported field-for-field from
 * Micron2HTML/micron2kivy's `_DocState`.
 */
internal class DocState {
    var align: Align = Align.LEFT
    var section: Int = 0
    var literal: Boolean = false
    val literalLines = mutableListOf<String>()
    var docFg: String? = null
    var docBg: String? = null
    /** Claimed anchor names, first-wins — shared namespace for heading auto-anchors and explicit `:name. */
    val anchors = mutableSetOf<String>()
    /** name -> block index, built up as blocks are emitted. Becomes `ConvertResult.anchors`. */
    val anchorBlockIndex = mutableMapOf<String, Int>()
    /** The index the *next* emitted block will land at — set by the converter's loop before each line. */
    var pendingBlockIndex: Int = 0
    /** Per source-line-index -> nearest following heading's slug, for bare `[label`#] links. */
    var nextHeadingMap: List<String?> = emptyList()
    var lineIndex: Int = 0
    var tableMode: Boolean = false
    val tableLines = mutableListOf<String>()
    var tableAlign: String = "" // "", "l", "c", "r" — captured at `t open
    var tableMaxWidth: Int = 100
    var hasFormFields: Boolean = false
}

/** Section-depth indent *level* for the current line — a consumer multiplies by its own unit. */
internal fun indentLevel(doc: DocState): Int = maxOf(0, doc.section - 1)

/**
 * Claim an anchor name in the document's shared namespace, and record which
 * block it resolves to (see [DocState.pendingBlockIndex]).
 *
 * Returns the name if it was successfully claimed (non-empty, not already
 * taken), else null. First declared wins — a later duplicate (whether
 * another heading's auto-slug or an explicit `:name) is silently ignored,
 * matching NomadNet's own anchor-collision rule.
 */
internal fun claimAnchor(doc: DocState, name: String): String? {
    if (name.isEmpty() || name in doc.anchors) return null
    doc.anchors.add(name)
    doc.anchorBlockIndex[name] = doc.pendingBlockIndex
    return name
}

/**
 * Resolve a bare `#` link to the next heading after this point. Falls back
 * to a harmless "#" when there's no following heading, or no document
 * context at all (e.g. `convertInline`, which never runs the multi-line
 * pre-pass so `nextHeadingMap` is empty).
 */
internal fun resolveBareHashLink(doc: DocState): String {
    val map = doc.nextHeadingMap
    if (map.isNotEmpty() && doc.lineIndex < map.size) {
        val slug = map[doc.lineIndex]
        if (!slug.isNullOrEmpty()) return "#$slug"
    }
    return "#"
}

/**
 * For each line index, find the nearest heading strictly after it. Powers
 * the bare `[label`#] link ("jump to the next heading after this point").
 * Two passes: forward records which line has which heading's slug
 * (re-simulating first-wins collision handling locally, matching
 * [claimAnchor]); backward fills each index from what's `upcoming` *before*
 * folding in that same line's own slug, so a heading never targets itself.
 */
internal fun computeNextHeadingMap(lines: List<String>): List<String?> {
    val n = lines.size
    val slugAt = arrayOfNulls<String>(n)
    val seen = mutableSetOf<String>()
    for (k in 0 until n) {
        val raw = lines[k]
        if (raw.startsWith(">")) {
            val (_, headingText) = splitHeading(raw)
            if (headingText.isNotEmpty()) {
                val slug = slugifyMicron(headingText)
                if (slug.isNotEmpty() && slug !in seen) {
                    seen.add(slug)
                    slugAt[k] = slug
                }
            }
        }
    }
    val nextMap = arrayOfNulls<String>(n)
    var upcoming: String? = null
    for (k in n - 1 downTo 0) {
        nextMap[k] = upcoming
        if (slugAt[k] != null) upcoming = slugAt[k]
    }
    return nextMap.toList()
}

/** Split a `>`-prefixed line into (level, stripped heading text). */
internal fun splitHeading(line: String): Pair<Int, String> {
    var level = 0
    while (level < line.length && line[level] == '>') level++
    return level to line.substring(level).trim()
}

private val HEX_DIGITS = (('0'..'9') + ('a'..'f') + ('A'..'F')).toHashSet()

/**
 * Parse a `#!fg=X` / `#!bg=X` page-header color value. 3-hex only, for the
 * same reason as the inline `Fxxx`/`Bxxx` tags (see [parseColor]): no
 * marker distinguishes a 3-hex value from a 6-hex one, so allowing both
 * would make a value's meaning depend silently on its length.
 */
private fun parseHeaderColor(value: String): String? {
    val v = value.trim()
    if (v.length == 3 && v.all { it in HEX_DIGITS }) {
        return "#" + v.toCharArray().joinToString("") { "$it$it" }.lowercase()
    }
    return null
}

private val TABLE_TOGGLE_RE = Regex("`t([lcr]?)(\\d*)")

/**
 * The document-level line/state machine: `#!bg=`/`#!fg=` headers, `>`
 * heading levels + auto-anchor claiming, dividers, literal-block and table
 * toggles, blank lines, and plain text lines. Ported from
 * Micron2HTML/micron2kivy's `_process_line`.
 *
 * Returns the [Block] produced by this source line, or null when the line
 * only updates state (a header, a toggle, an empty heading, a buffered
 * table/literal-block line) and contributes no block of its own.
 */
internal fun processLine(
    line: String,
    nodeHash: String,
    basePath: String,
    doc: DocState,
    urlResolver: UrlResolver,
): Block? {

    // ---- Inside a `t ... `t table block ----
    // Mutually exclusive with doc.literal by construction (both are only
    // entered from the plain fallthrough path below), so this can safely
    // come first.
    if (doc.tableMode) {
        if (TABLE_TOGGLE_RE.matchEntire(line.trimEnd('\r').trim()) != null) {
            doc.tableMode = false
            val rawLines = doc.tableLines.toList()
            val align = doc.tableAlign
            val maxWidth = doc.tableMaxWidth
            doc.tableLines.clear()
            doc.tableAlign = ""
            doc.tableMaxWidth = 100
            return renderTable(rawLines, align, maxWidth, nodeHash, basePath, doc, urlResolver)
        }
        doc.tableLines.add(line)
        return null
    }

    // ---- Inside a multi-line literal block ----
    if (doc.literal) {
        if (line.trimEnd() == "`=") {
            doc.literal = false
            val content = doc.literalLines.joinToString("\n")
            doc.literalLines.clear()
            return Block(
                runs = listOf(TextRun(content)),
                kind = BlockKind.LITERAL,
                indent = indentLevel(doc),
            )
        }
        doc.literalLines.add(line)
        return null
    }

    // ---- Comment / page-header lines (start with #) ----
    if (line.startsWith("#")) {
        val raw = line.trim()
        if (raw.startsWith("#!bg=")) {
            parseHeaderColor(raw.substring(5).trim())?.let { doc.docBg = it }
        } else if (raw.startsWith("#!fg=")) {
            parseHeaderColor(raw.substring(5).trim())?.let { doc.docFg = it }
        }
        return null
    }

    val stripped = line.trimEnd('\r')

    // ---- Table start: standalone `t[align][width] line ----
    val tableMatch = TABLE_TOGGLE_RE.matchEntire(stripped.trim())
    if (tableMatch != null) {
        doc.tableMode = true
        doc.tableLines.clear()
        doc.tableAlign = tableMatch.groupValues[1]
        doc.tableMaxWidth = tableMatch.groupValues[2].toIntOrNull() ?: 100
        return null
    }

    // ---- Literal block start/end: standalone `= line ----
    if (stripped.trim() == "`=") {
        doc.literal = true
        doc.literalLines.clear()
        return null
    }

    // ---- Full reset: standalone `` resets doc-level state ----
    if (stripped.trim() == "``") {
        doc.align = Align.LEFT
        return null
    }

    // ---- Section headings: line starts with one or more > ----
    if (line.startsWith(">")) {
        val (level, headingText) = splitHeading(line)
        doc.section = level
        if (headingText.isEmpty()) {
            // No row at all, not even blank space — section depth is still
            // updated above.
            return null
        }
        // Auto-anchor: claimed before parsing the text itself, so a
        // same-slug explicit `:name inside this same heading loses the tie.
        val slug = slugifyMicron(headingText)
        claimAnchor(doc, slug)
        val runs = parseInline(headingText, nodeHash, basePath, doc, urlResolver)
        return Block(
            runs = runs,
            kind = BlockKind.HEADING,
            align = doc.align,
            headingLevel = level,
            indent = maxOf(0, level - 1),
        )
    }

    // ---- Dividers ----
    // Only lines starting with `-` produce dividers. `=-`, `==`, `===` etc.
    // fall through and render as regular text.
    val s = line.trim()
    if (s.isNotEmpty() && s[0] == '-') {
        val indent = indentLevel(doc)
        // A custom divider character only takes effect when the line is
        // *exactly* "-" + one more character — any other length falls back
        // to the default. A control character in that position also falls
        // back (matches current upstream NomadNet exactly).
        val dividerChar = if (s.length == 2 && s[1].code >= 32) s[1] else '─'
        return Block(kind = BlockKind.DIVIDER, indent = indent, dividerChar = dividerChar)
    }

    // ---- Empty line ----
    if (line.trim().isEmpty()) {
        return Block(kind = BlockKind.BLANK)
    }

    // ---- Regular text line ----
    val runs = parseInline(line, nodeHash, basePath, doc, urlResolver)
    return Block(
        runs = runs,
        kind = BlockKind.TEXT,
        align = doc.align,
        indent = indentLevel(doc),
    )
}
