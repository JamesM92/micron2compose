package com.jamesm92.micron2compose.parser

/**
 * Converts Micron markup text — NomadNet's terminal markup language — into
 * the Compose-independent [ConvertResult] intermediate representation.
 *
 * Goal: NomadNet parity — parse the same Micron source the way the real
 * NomadNet client would. The reference sources are NomadNet's own
 * `Guide.py` (the in-app spec written for page authors) and
 * `MicronParser.py` (the reference implementation); where those two
 * disagree, this follows the Guide, since that's what real page authors
 * actually read and write to. This converter's algorithm is ported from
 * [Micron2HTML](https://github.com/JamesM92/Micron2HTML)'s `converter.py`
 * (MIT), cross-checked against
 * [micron2kivy](https://github.com/JamesM92/micron2kivy)'s independent
 * port and against current upstream NomadNet source directly.
 *
 * Unlike its Python siblings, `authenticated` (whether form fields render
 * as editable) is *not* a parameter here — it's a rendering decision, not
 * a parsing one, so it belongs to the Compose emission layer
 * (`com.jamesm92.micron2compose.compose`) instead. One parsed
 * [ConvertResult] can be rendered read-only or interactively without
 * re-parsing.
 *
 * @param urlResolver Callable `(rawUrl, nodeHash, basePath) -> href`
 *   invoked for every link/partial in the input. Defaults to
 *   [defaultUrlResolver], which emits canonical `hash://...` URLs.
 */
class MicronConverter(private val urlResolver: UrlResolver = ::defaultUrlResolver) {

    /**
     * Convert a full Micron document.
     *
     * @param nodeHash Destination hash of the source NomadNet node — used
     *   to resolve relative and node-relative links.
     * @param basePath Path of the current page (e.g. `/page/index.mu`) —
     *   used to resolve relative links against the page's directory.
     *
     * Never throws: any unexpected failure falls back to a single plain
     * [TextRun] block holding the raw input, matching this library's
     * "never crash on malformed input, render best-effort" requirement —
     * a live-preview page editor depends on this exact behavior.
     */
    fun convert(text: String, nodeHash: String = "", basePath: String = ""): ConvertResult {
        return try {
            convertInternal(text, nodeHash, basePath)
        } catch (e: Exception) {
            fallbackResult(text)
        }
    }

    private fun convertInternal(text: String, nodeHash: String, basePath: String): ConvertResult {
        val lines = text.split("\n")
        val doc = DocState()
        doc.nextHeadingMap = computeNextHeadingMap(lines)
        val blocks = mutableListOf<Block>()

        for (idx in lines.indices) {
            doc.lineIndex = idx
            doc.pendingBlockIndex = blocks.size
            val block = processLine(lines[idx], nodeHash, basePath, doc, urlResolver)
            if (block != null) blocks.add(block)
        }

        // Flush any unclosed literal block.
        if (doc.literal && doc.literalLines.isNotEmpty()) {
            val content = doc.literalLines.joinToString("\n")
            blocks.add(
                Block(runs = listOf(TextRun(content)), kind = BlockKind.LITERAL, indent = indentLevel(doc))
            )
        }

        // Flush any unclosed table.
        if (doc.tableMode && doc.tableLines.isNotEmpty()) {
            doc.pendingBlockIndex = blocks.size
            val rendered = renderTable(
                doc.tableLines, doc.tableAlign, doc.tableMaxWidth, nodeHash, basePath, doc, urlResolver
            )
            if (rendered != null) blocks.add(rendered)
        }

        return ConvertResult(
            blocks = blocks,
            anchors = doc.anchorBlockIndex.toMap(),
            hasFormFields = doc.hasFormFields,
            pageFg = doc.docFg,
            pageBg = doc.docBg,
        )
    }

    /**
     * Convert a single line of Micron markup to a single inline-only
     * block — no heading/divider/table handling, no block wrapper — useful
     * for titles, message previews, and brand elements. Multi-line input
     * has all newlines replaced with spaces.
     */
    fun convertInline(text: String, nodeHash: String = "", basePath: String = ""): ConvertResult {
        return try {
            val single = text.replace("\n", " ").trim()
            val doc = DocState()
            val runs = parseInline(single, nodeHash, basePath, doc, urlResolver)
            ConvertResult(blocks = listOf(Block(runs = runs)), hasFormFields = doc.hasFormFields)
        } catch (e: Exception) {
            fallbackResult(text)
        }
    }

    /**
     * Render Micron markup to plain text, stripping all formatting/colors.
     * Useful for message previews, search indexing, and anywhere structure
     * isn't wanted. Links retain only their label text; field/anchor/
     * partial runs contribute nothing. Literal blocks appear as their raw
     * content.
     */
    fun toText(text: String): String {
        val result = convert(text)
        return result.blocks.joinToString("\n") { block ->
            block.runs.joinToString("") { run ->
                when (run) {
                    is TextRun -> run.text
                    is LinkRun -> run.label
                    is PartialRun, is FieldRun, is AnchorRun -> ""
                }
            }
        }.trim()
    }

    private fun fallbackResult(text: String): ConvertResult =
        ConvertResult(blocks = listOf(Block(runs = listOf(TextRun(text)))))
}
