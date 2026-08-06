package com.jamesm92.micron2compose.parser

/**
 * Character-level state machine for one line's worth of Micron: backslash
 * escapes, `` ` `` token dispatch (bold/italic/underline/reset, fg/bg
 * color, alignment, links, explicit anchors, form fields, partials).
 * Ported from Micron2HTML/micron2kivy's `_parse_inline` — same algorithm,
 * same edge cases, but instead of building a markup string, this appends
 * runs to a flat [InlineRun] list.
 *
 * That target difference is also what lets this be *simpler* than the
 * Python originals' tag-stack close-innermost-and-reopen dance: that
 * machinery exists there only to keep HTML/Kivy markup strings validly
 * nested when e.g. a color span closes while bold is still open. Here,
 * each [TextRun] just carries its own flat set of style flags/colors, so
 * toggling one off can never disturb another — no stack, no unwinding,
 * same visible result (which formatting applies over which character
 * range) either way.
 */
private val HEX_DIGITS = (('0'..'9') + ('a'..'f') + ('A'..'F')).toHashSet()

private val ANCHOR_NAME_CHARS =
    (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('_', '-')).toHashSet()

internal fun parseInline(
    text: String,
    nodeHash: String,
    basePath: String,
    doc: DocState,
    urlResolver: UrlResolver,
): List<InlineRun> {
    val runs = mutableListOf<InlineRun>()
    val buffer = StringBuilder()
    var bold = false
    var italic = false
    var underline = false
    var fgColor: String? = null
    var bgColor: String? = null

    fun flush() {
        if (buffer.isNotEmpty()) {
            runs.add(TextRun(buffer.toString(), bold, italic, underline, fgColor, bgColor))
            buffer.setLength(0)
        }
    }

    var i = 0
    val n = text.length

    while (i < n) {
        val ch = text[i]

        // ---- Backslash escape ----
        if (ch == '\\' && i + 1 < n) {
            buffer.append(text[i + 1])
            i += 2
            continue
        }

        // ---- Backtick token ----
        if (ch == '`') {
            i += 1
            if (i >= n) break // dangling backtick at EOF — dropped silently
            val nc = text[i]

            when (nc) {
                // Reset ALL formatting (``)
                '`' -> {
                    flush()
                    bold = false; italic = false; underline = false
                    fgColor = null; bgColor = null
                    doc.align = Align.LEFT
                    i += 1
                }

                // Bold (`!)
                '!' -> {
                    flush(); bold = !bold; i += 1
                }

                // Underline (`_)
                '_' -> {
                    flush(); underline = !underline; i += 1
                }

                // Italic (`*)
                '*' -> {
                    flush(); italic = !italic; i += 1
                }

                // Foreground color (`Fxxx)
                'F' -> {
                    i += 1
                    val (color, newI) = parseColor(text, i, n)
                    i = newI
                    if (color != null) {
                        flush(); fgColor = color
                    }
                }

                // Reset foreground (`f)
                'f' -> {
                    flush(); fgColor = null; i += 1
                }

                // Background color (`Bxxx)
                'B' -> {
                    i += 1
                    val (color, newI) = parseColor(text, i, n)
                    i = newI
                    if (color != null) {
                        flush(); bgColor = color
                    }
                }

                // Reset background (`b)
                'b' -> {
                    flush(); bgColor = null; i += 1
                }

                // Alignment — updates persistent doc state
                'c' -> { doc.align = Align.CENTER; i += 1 }
                'l' -> { doc.align = Align.LEFT; i += 1 }
                'r' -> { doc.align = Align.RIGHT; i += 1 }
                'a' -> { doc.align = Align.LEFT; i += 1 }

                // Link (`[label`url`fields])
                // More than 3 backtick-separated components renders
                // nothing at all, matching NomadNet exactly.
                '[' -> {
                    i += 1 // past [
                    val end = text.indexOf(']', i)
                    if (end != -1) {
                        val linkInner = text.substring(i, end)
                        val parts = linkInner.split("`")
                        val (lbl, url, fieldSpecRaw) = when (parts.size) {
                            1 -> Triple("", parts[0], "")
                            2 -> Triple(parts[0], parts[1], "")
                            3 -> Triple(parts[0], parts[1], parts[2])
                            else -> Triple("", "", "")
                        }
                        if (url.isNotEmpty()) {
                            // `#`-prefixed URLs are page-local anchor jumps,
                            // not resolved through the normal URL resolver.
                            val href = when {
                                url == "#" -> resolveBareHashLink(doc)
                                url.startsWith("#") -> url
                                else -> urlResolver(url, nodeHash, basePath)
                            }
                            val kind = classifyLink(url, href)
                            val display = lbl.ifEmpty { url }
                            flush()
                            runs.add(
                                LinkRun(
                                    label = display,
                                    target = LinkTarget(
                                        url = href,
                                        fieldSpec = fieldSpecRaw.ifEmpty { null },
                                        isFileDownload = kind == LinkKind.FILE_DOWNLOAD,
                                        kind = kind,
                                    ),
                                )
                            )
                        }
                        i = end + 1
                    } else {
                        buffer.append('[')
                    }
                }

                // Explicit anchor (`:name) — zero-width jump target.
                ':' -> {
                    i += 1
                    val start = i
                    while (i < n && text[i] in ANCHOR_NAME_CHARS) i++
                    val name = text.substring(start, i)
                    val claimed = claimAnchor(doc, name)
                    if (claimed != null) {
                        flush()
                        runs.add(AnchorRun(claimed))
                    }
                }

                // Field (`<flags|name`default>)
                // A field requires a backtick between `<flags|name` and
                // `default>` — mirrors NomadNet exactly, including for
                // checkbox/radio shorthand that omits it: rendered as
                // broken text (the `<` silently dropped), not an input.
                '<' -> {
                    val fieldStart = i + 1
                    val backtickPos = text.indexOf('`', fieldStart)
                    val end = if (backtickPos != -1) text.indexOf('>', backtickPos + 1) else -1
                    if (backtickPos != -1 && end != -1) {
                        val fieldContent = text.substring(fieldStart, backtickPos)
                        val fieldData = text.substring(backtickPos + 1, end)
                        val spec = parseFieldSpec(fieldContent, fieldData)
                        doc.hasFormFields = true
                        flush()
                        runs.add(FieldRun(spec))
                        i = end + 1
                    } else {
                        // Malformed — eat the `<` silently.
                        i += 1
                    }
                }

                // Unknown token — silently consume both the backtick and
                // the unknown char, matching NomadNet. For a literal
                // backtick, escape it: `\``.
                //
                // `{` (partials) falls here too, deliberately — verified
                // against live upstream (MicronParser.py): a partial is
                // recognized *only* as a whole-line-starting construct in
                // parse_line (`line.startswith("`{")`), never inline via
                // make_output's character dispatch (this function's
                // equivalent), which has no case for it at all. See
                // LineParser.kt's line-start partial detection.
                else -> {
                    i += 1
                }
            }
            continue
        }

        buffer.append(ch)
        i += 1
    }

    flush()
    return runs
}

/**
 * Parse a Micron color token after the F/B prefix.
 *
 * Two forms, both ported from NomadNet's own `MicronParser.py` (fetched
 * and read directly, not paraphrased — the reference `elif c == "F":`/
 * `elif c == "B":` branches):
 *  - `FT<6hex>` / `BT<6hex>` — 24-bit extended form, only takes effect
 *    when there's room for the full 6 hex digits after the `T`.
 *  - `Fxxx` / `Bxxx` — 3-hex shorthand, each nibble doubled (f -> ff,
 *    8 -> 88, 0 -> 00). Also the fallback when a `T` is present but there
 *    isn't room for 6 more hex digits after it — matches upstream's own
 *    fallback exactly: `T` plus the next 2 characters get consumed as a
 *    (near-certainly invalid) 3-hex attempt rather than backtracking.
 *
 * Always consumes the appropriate number of characters when there's room
 * for the attempt at all (3 for the short form, 7 for `T` + 6 hex),
 * regardless of whether they're valid hex, so they never leak as visible
 * text either way.
 *
 * One deliberate deviation from upstream: real NomadNet doesn't validate
 * hex digits at all here — it stores whatever substring it finds directly
 * as color state, leaving any garbage to whatever downstream rendering
 * does with it. Building a Compose `Color` from an invalid hex string
 * would throw, which this library's "never crash on malformed input"
 * requirement doesn't allow — so both forms here validate all digits are
 * hex before applying a color, matching Micron2HTML/micron2kivy's own
 * same safety-motivated choice for the 3-hex form.
 *
 * Returns (color, newIndex).
 */
private fun parseColor(text: String, i: Int, n: Int): Pair<String?, Int> {
    if (i + 3 > n) return null to i

    if (text[i] == 'T' && i + 7 <= n) {
        val h6 = text.substring(i + 1, i + 7)
        if (h6.all { it in HEX_DIGITS }) {
            return "#" + h6.lowercase() to i + 7
        }
        return null to i + 7
    }

    val h3 = text.substring(i, i + 3)
    if (h3.all { it in HEX_DIGITS }) {
        return "#" + h3.toCharArray().joinToString("") { "$it$it" }.lowercase() to i + 3
    }
    return null to i + 3
}

/**
 * Parse a Micron input field. `fieldContent` is everything between `<` and
 * the backtick; `fieldData` is everything between the backtick and `>`.
 *
 * Formats (the backtick is the required separator between flags|name and
 * default/label):
 *   text/password : `<[size][!]|name`default>`
 *   checkbox      : `<?[size]|field_name|value[|*]`label>`
 *   radio         : `<^[size]|field_name|value[|*]`label>`
 *
 * Ported from Micron2HTML/micron2kivy's `_render_field` parsing logic.
 */
private fun parseFieldSpec(fieldContent: String, fieldData: String): FieldSpec {
    var masked = false
    var width = 24
    var type = FieldType.TEXT
    var name = fieldContent
    var value = ""
    var preselected = false

    if ("|" in fieldContent) {
        val components = fieldContent.split("|")
        var flags = components[0]
        name = components.getOrElse(1) { "" }

        when {
            "^" in flags -> { type = FieldType.RADIO; flags = flags.replace("^", "") }
            "?" in flags -> { type = FieldType.CHECKBOX; flags = flags.replace("?", "") }
            "!" in flags -> { masked = true; flags = flags.replace("!", "") }
        }

        if (flags.isNotEmpty() && flags.all { it.isDigit() }) {
            flags.toIntOrNull()?.let { width = minOf(it, 256) }
        }

        if (components.size > 2) value = components[2]
        if (components.size > 3 && components[3] == "*") preselected = true
    }

    return if (type == FieldType.CHECKBOX || type == FieldType.RADIO) {
        FieldSpec(
            name = name,
            type = type,
            optionValue = value.ifEmpty { fieldData },
            label = fieldData,
            preselected = preselected,
        )
    } else {
        FieldSpec(
            name = name,
            type = if (masked) FieldType.PASSWORD else FieldType.TEXT,
            width = width,
            defaultValue = fieldData,
        )
    }
}

/**
 * Parses a partial's `URL`refresh`fields` inner content (the text between
 * `` `{ `` and `}`, already extracted by the caller) into a [LinkTarget].
 * Called only from [LineParser.kt]'s line-start `` `{ `` detection — see
 * that file's docs and this file's "unknown token" comment for why a
 * partial is never recognized mid-line.
 */
internal fun parsePartialTarget(
    inner: String,
    nodeHash: String,
    basePath: String,
    urlResolver: UrlResolver,
): LinkTarget {
    val parts = inner.split("`")
    val rawUrl = parts.getOrElse(0) { "" }.trim()
    val href = urlResolver(rawUrl, nodeHash, basePath)

    var refresh: Double? = null
    if (parts.size > 1) {
        val r = parts[1].toDoubleOrNull()
        if (r != null && r >= 1) refresh = r
    }

    var fieldsSpec: String? = null
    var pid: String? = null
    if (parts.size > 2 && parts[2].isNotEmpty()) {
        fieldsSpec = parts[2]
        for (f in fieldsSpec.split("|")) {
            if (f.startsWith("pid=")) {
                pid = f.removePrefix("pid=")
                break
            }
        }
    }

    return LinkTarget(
        url = href,
        fieldSpec = fieldsSpec,
        isPartial = true,
        partialRefresh = refresh,
        partialPid = pid,
        kind = classifyLink(rawUrl, href),
    )
}
