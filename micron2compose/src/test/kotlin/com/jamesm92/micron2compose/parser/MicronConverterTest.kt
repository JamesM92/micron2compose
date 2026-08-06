package com.jamesm92.micron2compose.parser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Micron2HTML's `tests/test_converter.py` — same cases,
 * translated from "assert a substring appears in the rendered HTML" to
 * "assert the parsed [Block]/[InlineRun] structure looks right", since
 * there's no markup string here to search. Categories and case names are
 * kept close to the original so the two suites stay easy to compare.
 */
class MicronConverterTest {

    private val conv = MicronConverter()

    private fun Block.plainText(): String = runs.joinToString("") { run ->
        when (run) {
            is TextRun -> run.text
            is LinkRun -> run.label
            else -> ""
        }
    }

    private fun ConvertResult.plainText(): String = blocks.joinToString("\n") { it.plainText() }

    // -----------------------------------------------------------------
    // Comments
    // -----------------------------------------------------------------

    @Test
    fun `comment line omitted`() {
        val out = conv.convert("# this is a comment")
        assertTrue(out.blocks.isEmpty())
    }

    @Test
    fun `comment does not consume next line`() {
        val out = conv.convert("# comment\nhello")
        assertTrue(out.plainText().contains("hello"))
    }

    // -----------------------------------------------------------------
    // Headings
    // -----------------------------------------------------------------

    @Test
    fun `h1 h2 h3 levels`() {
        assertEquals(1, conv.convert("> Title").blocks.single().headingLevel)
        assertEquals(2, conv.convert(">> Subtitle").blocks.single().headingLevel)
        assertEquals(3, conv.convert(">>> Deep").blocks.single().headingLevel)
    }

    @Test
    fun `heading level 4 still carries its real depth`() {
        // Unlike Micron2HTML (which only styles levels 1-3 and falls back to
        // plain rendering beyond that), headingLevel here always carries the
        // real depth — a Compose consumer decides for itself how to style
        // level 4+.
        val block = conv.convert(">>>> Level 4").blocks.single()
        assertEquals(BlockKind.HEADING, block.kind)
        assertEquals(4, block.headingLevel)
    }

    @Test
    fun `heading content is parsed`() {
        val block = conv.convert("> `!Bold Title`!").blocks.single()
        val run = block.runs.filterIsInstance<TextRun>().first { it.text == "Bold Title" }
        assertTrue(run.bold)
    }

    @Test
    fun `empty heading emits nothing`() {
        assertTrue(conv.convert(">").blocks.isEmpty())
    }

    // -----------------------------------------------------------------
    // Dividers
    // -----------------------------------------------------------------

    @Test
    fun `single dash is default divider`() {
        val block = conv.convert("-").blocks.single()
        assertEquals(BlockKind.DIVIDER, block.kind)
        assertEquals('─', block.dividerChar)
    }

    @Test
    fun `double equals is a custom divider carrying its own char`() {
        val block = conv.convert("-=").blocks.single()
        assertEquals(BlockKind.DIVIDER, block.kind)
        assertEquals('=', block.dividerChar)
    }

    @Test
    fun `custom divider char preserved for arbitrary chars`() {
        val block = conv.convert("-*").blocks.single()
        assertEquals('*', block.dividerChar)
    }

    @Test
    fun `triple dash falls back to default rule`() {
        // Only a line of exactly "-" + one more char gets a custom divider
        // char — any other length (including "---") falls back to default.
        val block = conv.convert("---").blocks.single()
        assertEquals('─', block.dividerChar)
    }

    @Test
    fun `equals dash is text not a divider`() {
        // Only lines starting with `-` produce dividers.
        val block = conv.convert("=-").blocks.single()
        assertEquals(BlockKind.TEXT, block.kind)
        assertEquals("=-", block.plainText())
    }

    // -----------------------------------------------------------------
    // Inline formatting
    // -----------------------------------------------------------------

    @Test
    fun `bold italic underline toggle independently`() {
        val runs = conv.convert("`!bold`! `*italic`* `_under`_").blocks.single().runs
            .filterIsInstance<TextRun>()
        assertTrue(runs.any { it.text == "bold" && it.bold })
        assertTrue(runs.any { it.text == "italic" && it.italic })
        assertTrue(runs.any { it.text == "under" && it.underline })
    }

    @Test
    fun `reset all clears every flag`() {
        val runs = conv.convert("`!`*`_bold-italic-under``plain").blocks.single().runs
            .filterIsInstance<TextRun>()
        val styled = runs.first { it.text == "bold-italic-under" }
        assertTrue(styled.bold && styled.italic && styled.underline)
        val plain = runs.first { it.text == "plain" }
        assertFalse(plain.bold || plain.italic || plain.underline)
    }

    @Test
    fun `backslash escape prevents token interpretation`() {
        val block = conv.convert("\\`literal backtick").blocks.single()
        assertEquals("`literal backtick", block.plainText())
        assertFalse(block.runs.filterIsInstance<TextRun>().any { it.bold })
    }

    @Test
    fun `unknown token eaten silently`() {
        // An unrecognized char after a backtick is dropped along with the
        // backtick itself — no leftover backtick or the unknown char.
        val block = conv.convert("foo `> bar").blocks.single()
        assertEquals("foo  bar", block.plainText())
    }

    @Test
    fun `toggling one flag does not disturb others already open`() {
        // The whole point of the flat-run design over a tag-stack: closing
        // bold while color is still open must leave the color state intact
        // on whatever comes after, with no unwind/reopen machinery needed.
        val runs = conv.convert("`Fff0`!bold-colored`!still-colored`f").blocks.single().runs
            .filterIsInstance<TextRun>()
        val boldColored = runs.first { it.text == "bold-colored" }
        val stillColored = runs.first { it.text == "still-colored" }
        assertTrue(boldColored.bold)
        assertEquals("#ffff00", boldColored.fgColor)
        assertFalse(stillColored.bold)
        assertEquals("#ffff00", stillColored.fgColor)
    }

    // -----------------------------------------------------------------
    // Colors
    // -----------------------------------------------------------------

    @Test
    fun `fg 3digit expands each nibble`() {
        val run = conv.convert("`Fff0 yellow`f").blocks.single().runs
            .filterIsInstance<TextRun>().first { it.text.contains("yellow") }
        assertEquals("#ffff00", run.fgColor)
    }

    @Test
    fun `bg 3digit expands each nibble`() {
        val run = conv.convert("`B333 dark`b").blocks.single().runs
            .filterIsInstance<TextRun>().first { it.text.contains("dark") }
        assertEquals("#333333", run.bgColor)
    }

    @Test
    fun `invalid hex consumes three chars but applies no color`() {
        val block = conv.convert("`Fxxx hello`f").blocks.single()
        assertFalse(block.plainText().contains("xxx"))
        assertTrue(block.plainText().contains("hello"))
        assertNull(block.runs.filterIsInstance<TextRun>().first { it.text.contains("hello") }.fgColor)
    }

    @Test
    fun `T format 24-bit color is not supported`() {
        // NomadNet's reference parser accepts `FT<6hex>`, but its own Guide
        // never teaches it — deliberately staying 3-hex-only. The `T` plus
        // next 2 chars are consumed as a failed 3-char hex attempt.
        val block = conv.convert("`FT8b4513 brown`f").blocks.single()
        assertTrue(block.plainText().contains("brown"))
        assertNull(block.runs.filterIsInstance<TextRun>().first { it.text.contains("brown") }.fgColor)
    }

    @Test
    fun `header fg bg 3digit applied to result`() {
        val out = conv.convert("#!bg=333\n#!fg=aaa\nhello")
        assertEquals("#333333", out.pageBg)
        assertEquals("#aaaaaa", out.pageFg)
    }

    @Test
    fun `header fg bg 6digit not supported`() {
        val out = conv.convert("#!bg=112233\n#!fg=aabbcc\nhello")
        assertNull(out.pageBg)
        assertNull(out.pageFg)
    }

    // -----------------------------------------------------------------
    // Alignment
    // -----------------------------------------------------------------

    @Test
    fun `alignment tokens set block align`() {
        assertEquals(Align.CENTER, conv.convert("`c centered text").blocks.single().align)
        assertEquals(Align.RIGHT, conv.convert("`r right text").blocks.single().align)
        assertEquals(Align.LEFT, conv.convert("`l left text").blocks.single().align)
    }

    @Test
    fun `alignment persists across lines until changed`() {
        val out = conv.convert("`c one\ntwo\n`l three")
        assertEquals(Align.CENTER, out.blocks[0].align)
        assertEquals(Align.CENTER, out.blocks[1].align)
        assertEquals(Align.LEFT, out.blocks[2].align)
    }

    // -----------------------------------------------------------------
    // Links
    // -----------------------------------------------------------------

    @Test
    fun `basic link resolves and carries label`() {
        val link = conv.convert("`[Click here`hash:/abcdef/page.mu]").blocks.single().runs
            .filterIsInstance<LinkRun>().single()
        assertEquals("Click here", link.label)
        assertEquals("hash://abcdef/page.mu", link.target.url)
    }

    @Test
    fun `custom url resolver is honored`() {
        val c = MicronConverter { url, _, _ -> "/wrap?u=$url" }
        val link = c.convert("`[click`hash:/abcd/page.mu]").blocks.single().runs
            .filterIsInstance<LinkRun>().single()
        assertEquals("/wrap?u=hash:/abcd/page.mu", link.target.url)
    }

    @Test
    fun `convert inline produces a single wrapper-free block`() {
        val out = conv.convertInline("hello `!world`!")
        val block = out.blocks.single()
        assertEquals(BlockKind.TEXT, block.kind)
        val bold = block.runs.filterIsInstance<TextRun>().first { it.text == "world" }
        assertTrue(bold.bold)
    }

    @Test
    fun `to text strips formatting and drops link urls`() {
        assertTrue(conv.toText("`!Bold`! and `Fff0 colored`f text").contains("Bold and  colored text"))
        val stripped = conv.toText("`[label`https://example.com]")
        assertTrue(stripped.contains("label"))
        assertFalse(stripped.contains("example.com"))
    }

    @Test
    fun `link url only falls back label to the raw pre-resolution url`() {
        // Matches Micron2HTML/micron2kivy: the label fallback uses the raw
        // url as written in the source, not the resolver's output — the
        // href on LinkTarget is what carries the resolved form.
        val link = conv.convert("`[`hash:/abc/page.mu]").blocks.single().runs
            .filterIsInstance<LinkRun>().single()
        assertEquals("hash:/abc/page.mu", link.label)
        assertEquals("hash://abc/page.mu", link.target.url)
    }

    @Test
    fun `relative link resolves against node hash`() {
        val link = conv.convert("`[About`/about.mu]", nodeHash = "deadbeef").blocks.single().runs
            .filterIsInstance<LinkRun>().single()
        assertTrue(link.target.url.contains("deadbeef"))
    }

    @Test
    fun `http link passes through unchanged`() {
        val link = conv.convert("`[Web`https://example.com]").blocks.single().runs
            .filterIsInstance<LinkRun>().single()
        assertEquals("https://example.com", link.target.url)
    }

    @Test
    fun `link field spec with pipes is captured verbatim`() {
        val link = conv.convert(
            "`[Go`:/page/x.mu`a=1|b=2|c=3]", nodeHash = "deadbeef"
        ).blocks.single().runs.filterIsInstance<LinkRun>().single()
        assertEquals("a=1|b=2|c=3", link.target.fieldSpec)
    }

    @Test
    fun `link with more than three backtick segments renders nothing`() {
        val block = conv.convert(
            "`[Go`:/page/x.mu`a=1`b=2`c=3]", nodeHash = "deadbeef"
        ).blocks.single()
        assertTrue(block.runs.filterIsInstance<LinkRun>().isEmpty())
    }

    // -----------------------------------------------------------------
    // Anchors
    // -----------------------------------------------------------------

    @Test
    fun `heading auto anchor slug maps to block index`() {
        val out = conv.convert("> Hello World")
        assertEquals(0, out.anchors["hello-world"])
    }

    @Test
    fun `heading auto anchor strips formatting tokens`() {
        val out = conv.convert("> `!Bold`! Heading")
        assertTrue(out.anchors.containsKey("bold-heading"))
    }

    @Test
    fun `heading auto anchor collision first wins`() {
        val out = conv.convert("> Same\n> Same")
        assertEquals(0, out.anchors["same"])
        assertEquals(1, out.anchors.size)
    }

    @Test
    fun `explicit anchor is zero width and claims name`() {
        val block = conv.convert("`:mark hello").blocks.single()
        assertTrue(block.runs.any { it is AnchorRun && it.name == "mark" })
        assertTrue(block.plainText().contains("hello"))
    }

    @Test
    fun `explicit anchor name terminates at delimiter`() {
        val out = conv.convert("`:foo-bar baz")
        assertTrue(out.anchors.containsKey("foo-bar"))
    }

    @Test
    fun `explicit and heading anchors share one namespace`() {
        val out = conv.convert("`:shared marker\n> Shared")
        assertEquals(1, out.anchors.size)
        assertEquals(0, out.anchors["shared"])
    }

    @Test
    fun `explicit anchor name stops before special chars`() {
        val block = conv.convert("`:foo<script>alert(1)</script>").blocks.single()
        assertTrue(block.runs.any { it is AnchorRun && it.name == "foo" })
        // The rest is inert text data, never interpreted as markup/code.
        assertTrue(block.plainText().contains("<script>alert(1)</script>"))
    }

    @Test
    fun `named anchor link href is a plain fragment`() {
        val link = conv.convert("`[Jump`#install-notes]").blocks.single().runs
            .filterIsInstance<LinkRun>().single()
        assertEquals("#install-notes", link.target.url)
    }

    @Test
    fun `bare hash link jumps to next heading`() {
        val out = conv.convert("`[Continue`#]\ntext\n> Next Section")
        val link = out.blocks[0].runs.filterIsInstance<LinkRun>().single()
        assertEquals("#next-section", link.target.url)
    }

    @Test
    fun `bare hash link with no following heading falls back to hash`() {
        val out = conv.convert("`[Continue`#]\ntext")
        val link = out.blocks[0].runs.filterIsInstance<LinkRun>().single()
        assertEquals("#", link.target.url)
    }

    @Test
    fun `convert inline bare hash link does not crash`() {
        val link = conv.convertInline("`[x`#]").blocks.single().runs
            .filterIsInstance<LinkRun>().single()
        assertEquals("#", link.target.url)
    }

    // -----------------------------------------------------------------
    // Tables
    // -----------------------------------------------------------------

    private val tableMu = """
        `t
        | Name | Price | Qty |
        | ---- | :---: | --: |
        | `F3a3Apple`f | Free | `!5`! |
        | Orange | Ask, nicely | 3 |
        `t
    """.trimIndent()

    @Test
    fun `basic table renders box drawing and cell text`() {
        val block = conv.convert(tableMu).blocks.single()
        assertEquals(BlockKind.TABLE, block.kind)
        val text = block.plainText()
        for (ch in "┌┐└┘│┬┴┼") assertTrue("missing $ch", text.contains(ch))
        assertTrue(text.contains("Apple"))
        assertTrue(text.contains("Free"))
        assertTrue(text.contains("Orange"))
    }

    @Test
    fun `table cell formatting is preserved`() {
        val runs = conv.convert(tableMu).blocks.single().runs.filterIsInstance<TextRun>()
        assertTrue(runs.any { it.text.contains("Apple") && it.fgColor == "#33aa33" })
        assertTrue(runs.any { it.text.contains("5") && it.bold })
    }

    @Test
    fun `table min column width is three`() {
        val block = conv.convert("`t\n| A | B |\n| --- | --- |\n| 1 | 2 |\n`t").blocks.single()
        assertTrue(block.plainText().contains("───"))
    }

    @Test
    fun `table width shrinks to fit max width suffix`() {
        val block = conv.convert(
            "`t15\n| VeryLongHeader | AnotherVeryLongOne |\n| --- | --- |\n" +
                "| xxxxxxxxxxxxxxxxxxxx | yyyyyyyyyyyyyyyyyyyy |\n`t"
        ).blocks.single()
        val longestLine = block.plainText().split("\n").maxOf { it.length }
        assertTrue("longest line was $longestLine", longestLine <= 20)
    }

    @Test
    fun `table align sets block align without affecting later blocks`() {
        val out = conv.convert("`tc\n| A | B |\n| --- | --- |\n| 1 | 2 |\n`t\nafter")
        assertEquals(Align.CENTER, out.blocks[0].align)
        assertEquals(Align.LEFT, out.blocks[1].align)
    }

    @Test
    fun `table escaped pipe in cell is not treated as a separator`() {
        val block = conv.convert("`t\n| A | B |\n| --- | --- |\n| x\\|y | 2 |\n`t").blocks.single()
        assertTrue(block.plainText().contains("x|y"))
    }

    @Test
    fun `unclosed table flushes at end of file`() {
        val block = conv.convert("`t\n| A | B |\n| --- | --- |\n| 1 | 2 |").blocks.single()
        assertEquals(BlockKind.TABLE, block.kind)
        assertTrue(block.plainText().contains("┌") && block.plainText().contains("┘"))
    }

    @Test
    fun `table inside section inherits indent`() {
        val block = conv.convert(">> Section\n`t\n| A | B |\n| --- | --- |\n| 1 | 2 |\n`t")
            .blocks.first { it.kind == BlockKind.TABLE }
        assertEquals(1, block.indent)
    }

    @Test
    fun `empty table renders nothing`() {
        assertTrue(conv.convert("`t\n`t").blocks.isEmpty())
    }

    @Test
    fun `table body lines are not interpreted as micron`() {
        val block = conv.convert(
            "`t\n| A | B |\n| --- | --- |\n| > Not a heading | 2 |\n`t"
        ).blocks.single()
        assertEquals(BlockKind.TABLE, block.kind)
        assertTrue(block.plainText().contains("> Not a heading"))
    }

    // -----------------------------------------------------------------
    // Partials
    // -----------------------------------------------------------------

    @Test
    fun `partial exposes resolved url and refresh metadata`() {
        val partial = conv.convert("`{hash:/abcdef/status.mu`5}").blocks.single().runs
            .filterIsInstance<PartialRun>().single()
        assertTrue(partial.target.isPartial)
        assertEquals("hash://abcdef/status.mu", partial.target.url)
        assertEquals(5.0, partial.target.partialRefresh)
    }

    @Test
    fun `partial fields and pid are captured`() {
        val partial = conv.convert("`{hash:/abcdef/status.mu`10`action=view|pid=main}").blocks.single()
            .runs.filterIsInstance<PartialRun>().single()
        assertEquals(10.0, partial.target.partialRefresh)
        assertEquals("action=view|pid=main", partial.target.fieldSpec)
        assertEquals("main", partial.target.partialPid)
    }

    @Test
    fun `partial refresh below one is disabled`() {
        val partial = conv.convert("`{hash:/abcdef/status.mu`0.5}").blocks.single().runs
            .filterIsInstance<PartialRun>().single()
        assertNull(partial.target.partialRefresh)
    }

    @Test
    fun `partial unclosed renders literal brace as text`() {
        val block = conv.convert("`{hash:/abcdef/status.mu").blocks.single()
        assertTrue(block.runs.filterIsInstance<PartialRun>().isEmpty())
        assertTrue(block.plainText().contains("{"))
    }

    // -----------------------------------------------------------------
    // Literal mode
    // -----------------------------------------------------------------

    @Test
    fun `inline backtick equals is not a literal toggle`() {
        // `= only has meaning as a WHOLE line by itself. Mid-line it's just
        // an unrecognized token, silently consumed — bold still toggles
        // normally around it.
        val runs = conv.convert("`=`!not bold`=`!").blocks.single().runs.filterIsInstance<TextRun>()
        assertTrue(runs.any { it.text == "not bold" && it.bold })
    }

    @Test
    fun `standalone literal block preserves content verbatim`() {
        val block = conv.convert("`=\nraw `!not-bold`!\n`=").blocks.single()
        assertEquals(BlockKind.LITERAL, block.kind)
        assertEquals("raw `!not-bold`!", block.plainText())
    }

    // -----------------------------------------------------------------
    // Form fields
    // -----------------------------------------------------------------

    @Test
    fun `text field parses name width and default`() {
        val spec = conv.convert("`<20|username`alice>").blocks.single().runs
            .filterIsInstance<FieldRun>().single().spec
        assertEquals("username", spec.name)
        assertEquals(FieldType.TEXT, spec.type)
        assertEquals(20, spec.width)
        assertEquals("alice", spec.defaultValue)
    }

    @Test
    fun `checkbox field parses value label and prechecked`() {
        val out = conv.convert("`<?|agree|yes|*`I agree>")
        val spec = out.blocks.single().runs.filterIsInstance<FieldRun>().single().spec
        assertEquals(FieldType.CHECKBOX, spec.type)
        assertEquals("agree", spec.name)
        assertEquals("yes", spec.optionValue)
        assertEquals("I agree", spec.label)
        assertTrue(spec.preselected)
        assertTrue(out.hasFormFields)
    }

    @Test
    fun `radio field parses value and label`() {
        val spec = conv.convert("`<^|color|red`Red>").blocks.single().runs
            .filterIsInstance<FieldRun>().single().spec
        assertEquals(FieldType.RADIO, spec.type)
        assertEquals("red", spec.optionValue)
        assertEquals("Red", spec.label)
    }

    @Test
    fun `password field sets masked type`() {
        val spec = conv.convert("`<!|password`secret>").blocks.single().runs
            .filterIsInstance<FieldRun>().single().spec
        assertEquals(FieldType.PASSWORD, spec.type)
        assertEquals("secret", spec.defaultValue)
    }

    @Test
    fun `field without backtick separator is eaten silently`() {
        val block = conv.convert("`<?|agree|yes|*>").blocks.single()
        assertTrue(block.runs.filterIsInstance<FieldRun>().isEmpty())
        assertTrue(block.plainText().contains("agree"))
    }

    // -----------------------------------------------------------------
    // Never-crash / robustness (no HTML escaping needed here, but no
    // construct should ever be interpreted as executable, and malformed
    // input must never throw).
    // -----------------------------------------------------------------

    @Test
    fun `script-like text round-trips as inert data`() {
        val block = conv.convert("<script>alert(1)</script>").blocks.single()
        assertEquals("<script>alert(1)</script>", block.plainText())
    }

    @Test
    fun `malformed input never throws`() {
        val adversarial = listOf(
            "`", "``", "`[", "`[a`b`c`d`e]", "`<", "`<|", "`{", "`:", "`F", "`Fa",
            "\\", "`t\n`t\n`t", "-".repeat(5000), "`!".repeat(5000),
        )
        for (input in adversarial) {
            conv.convert(input)
            conv.convertInline(input)
            conv.toText(input)
        }
    }

    // -----------------------------------------------------------------
    // Integration: the shared showcase.mu fixture (MIT, ported from
    // Micron2HTML/examples/showcase.mu) — a smoke test that the whole
    // pipeline runs end-to-end over a real, representative document.
    // -----------------------------------------------------------------

    @Test
    fun `showcase fixture parses without error and produces expected structure`() {
        val fixture = File("src/test/resources/fixtures/showcase.mu").readText()
        val out = conv.convert(fixture)

        assertEquals("#111111", out.pageBg)
        assertEquals("#cccccc", out.pageFg)
        assertTrue(out.hasFormFields)
        assertTrue(out.anchors.containsKey("custom-anchor"))
        assertTrue(out.blocks.any { it.kind == BlockKind.TABLE })
        assertTrue(out.blocks.any { it.kind == BlockKind.LITERAL })
        assertTrue(out.blocks.any { it.kind == BlockKind.HEADING && it.headingLevel == 1 })
        assertTrue(out.blocks.any { it.runs.filterIsInstance<LinkRun>().isNotEmpty() })
        assertTrue(out.blocks.any { it.runs.filterIsInstance<FieldRun>().isNotEmpty() })
    }
}
