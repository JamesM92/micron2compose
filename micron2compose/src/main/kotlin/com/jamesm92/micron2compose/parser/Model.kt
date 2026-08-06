package com.jamesm92.micron2compose.parser

/**
 * Compose-independent intermediate representation for parsed Micron
 * content.
 *
 * Deliberately holds no Compose types (no `AnnotatedString`, no `Color`) —
 * this is the "no Compose dependency" parser core the project's handoff doc
 * calls for, so it stays unit-testable with plain JUnit and reusable if a
 * fourth rendering target ever shows up. The `com.jamesm92.micron2compose.compose`
 * package is what turns this into real Compose UI.
 */

/** One inline unit of parsed content within a line. */
sealed interface InlineRun

/** Plain or styled text — the vast majority of runs in any real document. */
data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** "#rrggbb", already expanded from Micron's 3-hex shorthand. Null = inherit. */
    val fgColor: String? = null,
    val bgColor: String? = null,
) : InlineRun

/** A `` `[label`url] `` link, or a bare `` `[`url] `` (label falls back to the URL). */
data class LinkRun(
    val label: String,
    val target: LinkTarget,
) : InlineRun

/**
 * A `` `{url`refresh`fields} `` partial. Per the data-only-placeholder
 * decision for this library (matching Micron2HTML/micron2kivy): this
 * renders as a plain clickable "[live]"-style link, never an automatic
 * re-fetch. `target` carries the refresh/fields/pid metadata for a
 * consuming app to build its own live-refresh behavior on top of, if it
 * wants one.
 */
data class PartialRun(
    val target: LinkTarget,
) : InlineRun

/** A `` `<...> `` form field. */
data class FieldRun(
    val spec: FieldSpec,
) : InlineRun

/**
 * A zero-width `` `:name `` anchor declaration at this exact point in the
 * line. Carries no visual output; the containing [Block]'s index is what
 * [ConvertResult.anchors] actually points to (block granularity, not a
 * character offset — see [ConvertResult]).
 */
data class AnchorRun(
    val name: String,
) : InlineRun

/** Resolved destination for a [LinkRun] or [PartialRun]. */
data class LinkTarget(
    val url: String,
    /**
     * Form-submission spec from a link's third backtick-component
     * (`*`, pipe-separated field names, or `key=value` pairs — see
     * README "Links"), or a partial's request-fields component. Same
     * syntax serves both roles, so one field covers both.
     */
    val fieldSpec: String? = null,
    val isPartial: Boolean = false,
    /** Seconds between re-fetches; null/absent means refresh is disabled. */
    val partialRefresh: Double? = null,
    val partialPid: String? = null,
)

enum class FieldType { TEXT, PASSWORD, CHECKBOX, RADIO }

/**
 * A parsed `` `<...> `` form field. Field semantics split by [type], same
 * as NomadNet's own field syntax:
 *  - TEXT/PASSWORD: [defaultValue] is the prefill value, [width] is the
 *    character width of the input.
 *  - CHECKBOX/RADIO: [optionValue] is the value submitted when this option
 *    is selected, [label] is the visible label text, [preselected] is
 *    whether it starts checked/selected.
 */
data class FieldSpec(
    val name: String,
    val type: FieldType = FieldType.TEXT,
    val width: Int = 24,
    val defaultValue: String = "",
    val optionValue: String = "",
    val label: String = "",
    val preselected: Boolean = false,
)

enum class BlockKind { TEXT, HEADING, DIVIDER, LITERAL, TABLE, BLANK }

enum class Align { LEFT, CENTER, RIGHT }

/**
 * One rendered line/segment — the granularity a Compose renderer emits one
 * composable per (a `LazyColumn` item), and the granularity
 * [ConvertResult.anchors] resolves against.
 *
 * TABLE and LITERAL blocks still carry their content as [runs] (styled
 * text runs — a table's runs are its assembled box-drawing text, fed back
 * through the inline parser per cell so a color/bold token inside a cell
 * still renders); [kind] only tells the emission layer "use a monospace
 * font here", not a different payload shape.
 */
data class Block(
    val runs: List<InlineRun> = emptyList(),
    val kind: BlockKind = BlockKind.TEXT,
    val align: Align = Align.LEFT,
    /** 0 = not a heading; N = `>`-depth (1 = h1, 2 = h2, ...). */
    val headingLevel: Int = 0,
    /** Section-depth indent *level*, not px/dp — a consumer multiplies by its own unit. */
    val indent: Int = 0,
    /**
     * DIVIDER only: the character to repeat across the row. Real NomadNet
     * always repeats a literal character for a divider — even the
     * "default" case is just U+2500 (─) repeated, not a theme-decided
     * rule the way Micron2HTML's HTML `<hr>` lets CSS decide. Defaults to
     * U+2500 to match that default case; a custom `-x` divider line
     * carries `x` here instead.
     */
    val dividerChar: Char = '─',
)

/**
 * Result of [MicronConverter.convert] / [MicronConverter.convertInline].
 */
data class ConvertResult(
    val blocks: List<Block> = emptyList(),
    /**
     * Anchor name -> block index. Every anchor (a heading's auto-slug, or
     * an explicit `` `:name `` found anywhere in a line) resolves to the
     * index of the [Block] it was found in — block granularity, not a
     * character offset, matching the layout model decided for this
     * library (a `LazyColumn` of blocks, scrolled to an item index rather
     * than a sub-block text position).
     */
    val anchors: Map<String, Int> = emptyMap(),
    val hasFormFields: Boolean = false,
    /** From `#!fg=` — a "#rrggbb" string, or null if the page didn't set one. */
    val pageFg: String? = null,
    /** From `#!bg=` — apply to your own container's background yourself. */
    val pageBg: String? = null,
)
