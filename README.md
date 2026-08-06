# micron2compose

[![CI](https://github.com/JamesM92/micron2compose/actions/workflows/ci.yml/badge.svg)](https://github.com/JamesM92/micron2compose/actions/workflows/ci.yml)

A Kotlin/Jetpack Compose library that converts [Micron](https://github.com/markqvist/NomadNet) markup into real Compose UI — `AnnotatedString` with proper styling for inline formatting/links, and live `TextField`/`Checkbox`/`RadioButton` composables for form fields, not placeholders.

Micron is the terminal markup language used by [NomadNet](https://github.com/markqvist/NomadNet) nodes. This library is a generic, standalone dependency — it makes no assumptions about any particular app, networking stack, or NomadNet client. Any Android/Compose project that needs to render `.mu` content can depend on it directly.

This is the third Micron renderer in a small project family, all sharing the same **NomadNet-parity** design goal and the same parsing logic — only the output side differs:

- [Micron2HTML](https://github.com/JamesM92/Micron2HTML) — HTML target, and this library's primary porting reference
- [micron2kivy](https://github.com/JamesM92/micron2kivy) — Kivy target
- **micron2compose** (this repo) — Jetpack Compose target

**Design goal: NomadNet parity.** The parser aims to read the same Micron source the way the real NomadNet client would — following [NomadNet's own Guide](https://github.com/markqvist/NomadNet/blob/master/nomadnet/ui/textui/Guide.py) (the in-app spec written for page authors) and [MicronParser.py](https://github.com/markqvist/NomadNet/blob/master/nomadnet/ui/textui/MicronParser.py) (the reference implementation). Where those two disagree, this follows the Guide, since that's what real page authors read and write to.

## Installation

Published via [JitPack](https://jitpack.io/) — builds directly from a tagged GitHub release, no other infrastructure required.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// module build.gradle.kts
dependencies {
    implementation("com.github.JamesM92:micron2compose:<tag>")
}
```

Requires Compose (a recent Compose BOM — the library is built against `2026.06.01`), Kotlin, and `minSdk 24`+.

## Library usage

The library is split into two independent layers: a plain-Kotlin parser core with no Compose dependency, and a Compose emission layer built on top of it.

```kotlin
import com.jamesm92.micron2compose.parser.MicronConverter
import com.jamesm92.micron2compose.compose.MicronPage

val converter = MicronConverter()
val result = converter.convert(
    micronText,
    nodeHash = "a1b2c3d4...",     // destination hash of the source node
    basePath = "/page/index.mu", // current page path
)

// Batteries-included: a LazyColumn of blocks, scroll-to-anchor, the works.
@Composable
fun PageScreen(result: ConvertResult) {
    MicronPage(
        result = result,
        readOnly = false, // false = interactive form fields; true = disabled
        onLinkClick = { target ->
            if (target.isFileDownload) {
                // show a filename/size confirmation before following target.url
            } else {
                // navigate, using target.url
            }
        },
    )
}
```

`MicronPage`/`MicronBlock` also take `fontFamily` (regular text) and `monospaceFontFamily` (tables, literal blocks, and dividers — anything built from box-drawing/Braille characters, where a generic system monospace font often doesn't have full glyph coverage and misaligns) if your app bundles its own fonts:

```kotlin
MicronPage(
    result = result,
    fontFamily = MyAppFonts.body,
    monospaceFontFamily = MyAppFonts.robotoMonoNerd,
)
```

`convert()` returns a `ConvertResult` — a Compose-independent intermediate representation, not a bare string:

```kotlin
data class ConvertResult(
    val blocks: List<Block>,           // one entry per rendered line/segment
    val anchors: Map<String, Int>,     // anchor name -> block index, for scroll-to-anchor
    val hasFormFields: Boolean,
    val pageFg: String?,               // from #!fg= — a "#rrggbb" string
    val pageBg: String?,               // from #!bg= — apply to your own container yourself
)
```

Keeping parsing and rendering separate means one parsed `ConvertResult` can be rendered read-only or interactively, or scrolled to different anchors, without re-parsing — and `authenticated`/`readOnly` (whether form fields are editable) is a parameter on the Compose side (`MicronPage`), not on `convert()` itself, unlike the HTML/Kivy siblings.

For custom layouts instead of the provided `LazyColumn`, render blocks individually:

```kotlin
Column {
    for (block in result.blocks) {
        MicronBlock(block = block, onLinkClick = { /* ... */ })
    }
}
```

### Form field state

The library never owns form field values — implement `MicronFormState` over whatever state your app already uses (a `ViewModel`, saved-instance state, a plain map), or use the provided in-memory default for quick use:

```kotlin
MicronPage(
    result = result,
    formState = rememberDefaultMicronFormState(), // or your own MicronFormState
)
```

### Inline-only conversion

```kotlin
// For titles, message previews, brand elements — no block wrapper.
val title = converter.convertInline("`F4af`!My Node`!`f")

// Plain text — strips all formatting and colors, no Compose types at all.
val text = converter.toText(micronText)
```

### Custom URL resolution

By default, links resolve to canonical `hash://<hash>/<path>` URLs (and `http(s)://` URLs pass through). Pass a resolver to produce your own scheme:

```kotlin
import com.jamesm92.micron2compose.parser.MicronConverter
import com.jamesm92.micron2compose.parser.defaultUrlResolver

val converter = MicronConverter { url, nodeHash, basePath ->
    val canonical = defaultUrlResolver(url, nodeHash, basePath)
    if (canonical.startsWith("hash://")) "myapp://page?url=$canonical" else canonical
}
```

## Micron syntax

### Comments and headers

```
# This is a comment — the whole line is stripped from output

#!bg=2a2   Set page background color (3-hex shorthand: each digit doubled)
#!fg=aaa   Set page foreground color
```

Deliberately 3-hex-only, matching the inline `` `Fxxx ``/`` `Bxxx `` tags below — NomadNet's own reference parser would technically permit a 6-hex value here too, but with no marker distinguishing the two, a value's meaning would silently depend on its length. One fixed width, applied consistently, is safer.

### Headings and sections

```
>Section heading      level 1
>>Subsection          level 2
>>>Sub-subsection     level 3 (and deeper — headingLevel always carries the real depth)
```

Every heading becomes a `Block` with `headingLevel` set, and its slugified text becomes an anchor (see [Anchors](#anchors)).

### Dividers

```
-       Default divider — a repeated U+2500 (─) row
-=      Row of `=` characters
-x      Custom divider — repeats character `x` (e.g. `-*` renders a row of `*`)
```

A custom divider character only takes effect when the line is *exactly* two characters — `-` followed by one more. Any other length, including `---` or `-==`, falls back to the default row of `─`.

### Inline formatting

```
`!text`!      Bold
`*text`*      Italic
`_text`_      Underline

`Fxxx         Set foreground color (3-hex shorthand — `FF40 → #ff4400)
`FTrrggbb     Set foreground color (24-bit — `FT8b4513 → #8b4513)
`f            Reset foreground color

`Bxxx         Set background color (3-hex shorthand)
`BTrrggbb     Set background color (24-bit)
`b            Reset background color

``            Reset ALL inline formatting (bold, italic, underline, colors, alignment)
```

Both forms are real (verified against live NomadNet source, not just its Guide) — the 24-bit `T`-prefixed form only takes effect when there's room for the full 6 hex digits after the `T`; otherwise it falls back to treating `T` plus the next 2 characters as a 3-hex attempt, matching NomadNet exactly. Note this differs from the `#!fg=`/`#!bg=` page headers above, which stay 3-hex-only — see that section for why.

### Alignment

```
`a            Left align (default)
`c            Center align
`r            Right align
```

Alignment persists across lines until changed — it's document-level state, not a per-line reset.

### Links

```
`[Label`href]                        Labeled link
`[`http://example.com]               URL-only link (label falls back to the URL)
`[Label`/relative/path.mu]           Relative path (resolved against basePath)
`[Label`hash://a1b2c3/page.mu]       Node link (resolved against nodeHash)
```

Links can also submit form-field data: `` `[Label`url`fields] ``, where `fields` is pipe-separated (`*` for every field, specific field names, or `key=value` pairs). This rides on `LinkTarget.fieldSpec` for your app to read. A link with more than 3 backtick-separated components renders nothing at all, matching NomadNet exactly.

A link pointing at NomadNet's `/file/` download-file convention sets `LinkTarget.isFileDownload = true`, with `url` still populated with the real resolved target (not blocked or altered) — the library doesn't decide what to do with a file link, it just tells you one is here. A host app wanting a download confirmation (filename/MIME/size) before following it checks this flag itself.

### Anchors

```
`:name                                Declare an anchor at this point (zero-width)
`[Label`#name]                        Jump to a named anchor
`[Label`#]                            Jump to the next `>` heading after this link
```

Every heading also becomes an anchor automatically, slugified from its text (`` >Hello World `` → `hello-world`). Anchors resolve to a **block index** (`ConvertResult.anchors`), not a character offset — this library renders as a list of blocks (a `LazyColumn`), which is the natural granularity to scroll to. Explicit `` `:name `` anchors and heading auto-anchors share one namespace; first declared wins on a collision.

### Partials

```
`{URL}                                Partial with no auto-refresh
`{URL`refresh}                        Refresh interval in seconds (0/omitted disables it)
`{URL`refresh`field1|field2|pid=x}    With request fields; `pid=` targets a specific partial
```

Real NomadNet partials asynchronously load and periodically re-fetch a page fragment in place. This library renders a plain clickable "[live]" placeholder instead — matching the data-only approach `Micron2HTML`/`micron2kivy` already take — but the `refresh`/`fields`/`pid` data isn't discarded: it's on `LinkTarget`, delivered through the same `onLinkClick` callback as an ordinary link, for a host app to build its own live-refresh behavior on top of if it wants one.

### Literal blocks

```
`=
This text is rendered verbatim in a monospace block.
No Micron formatting is applied inside.
`=
```

Each `` `= `` must be alone on its own line — that's the only form recognized as a toggle. Mid-line, it's just an unrecognized token like any other.

### Tables

```
`t
| Name | Price | Qty |
| ---- | :---: | --: |
| Apple | Free | 5 |
| Orange | Ask, nicely | 3 |
`t
```

Renders as literal box-drawing ASCII art in a monospace font — not a semantic table — matching what real NomadNet actually shows. The first row is the header; the second is a markdown-style alignment separator (`:---:` center, `---:` right, anything else left); the rest is data. `` `t `` takes an optional alignment letter and/or max-width number (e.g. `` `tc30 ``) to align/cap the whole table. Use `\|` inside a cell for a literal pipe.

### Form fields

Fields render as real, interactive composables by default; pass `readOnly = true` to `MicronPage`/`MicronBlock` to render them disabled instead.

```
`<name`default>                  Text input
`<size|name`default>             Text input with character width
`<!|name`default>                Password input
`<?|name|value`label>            Checkbox (add |* to pre-check: `<?|name|value|*`label>)
`<^|name|value`label>            Radio button (add |* to pre-select)
```

The backtick before the closing `>` is mandatory for every field type — `` `<?|name|value> `` without it is not valid Micron and renders as plain text, matching NomadNet's own parser exactly.

## Known limitations

This library's stated goal is full parity with the real NomadNet viewer — the items below are open gaps toward that, not a permanent ceiling.

- **Partials render as a static, clickable placeholder only** — no automatic live re-fetching (the metadata is exposed on `LinkTarget` for a host app to build that on top). See [Partials](#partials).
- **Table wide-character width uses a built-in East Asian Width approximation, not a full `wcwidth` port** — the common CJK/Fullwidth Unicode ranges are measured as double-width, matching real NomadNet's own `wcwidth`-based measurement for the vast majority of real content, but "Ambiguous"-width characters and some rarer combining-mark cases aren't specially handled.
- **The table width-shrink algorithm is ported from NomadNet's real implementation** (`RNS/Utilities/rngit/util.py`'s `format_table_raw`, fetched and verified against live upstream source — not approximated): sort columns widest-first, drain each down to the 3-character minimum before moving to the next, until the table fits.
- **Dividers render at a fixed repeated-character width** clipped to the available layout width, rather than anything terminal-width-aware — there's no meaningful "terminal width" concept in a Compose layout.

## Security

Every `.mu` document is treated as untrusted remote content. No Micron construct has an execution path, and none ever will — the parser only ever produces plain Kotlin data (`Block`/`InlineRun` values), never anything reflective, evaluated, or otherwise executable. Malformed input never throws: `convert()`/`convertInline()` fall back to a single plain-text block on any unexpected failure rather than crashing — this doubles as what a live-preview page editor needs from its renderer.

The default URL resolver only ever emits `http(s)://`, `hash://`, or `#` — but a consuming app still shouldn't blindly hand a resolved URL to something like `Intent.ACTION_VIEW` without its own scheme allow-list. It does **not** block NomadNet's `/file/` download-file convention (an earlier version did, by collapsing those links to a bare `#` — which is exactly as ambiguous as it sounds, since `#` is also the real href for a "jump to the next heading" link with no following heading, and it threw away the fact a file link was even there). `LinkTarget.isFileDownload` carries that signal instead, with `url` still populated with the real target — see [Links](#links).

The default URL resolver blocks NomadNet's `/file/` download-file convention (returns `#`) and only ever emits `http(s)://`, `hash://`, or `#` — but a consuming app still shouldn't blindly hand a resolved URL to something like `Intent.ACTION_VIEW` without its own scheme allow-list.

## Running tests

```bash
./gradlew :micron2compose:testDebugUnitTest       # parser core — plain JUnit, no device needed
./gradlew :micron2compose:connectedAndroidTest    # Compose UI tests — needs a device/emulator
```

## License

MIT — see [LICENSE](LICENSE).

## Related

- [NomadNet](https://github.com/markqvist/NomadNet) — the NomadNet node software (defines the Micron spec)
- [Micron2HTML](https://github.com/JamesM92/Micron2HTML) — the HTML target, and this library's primary porting reference
- [micron2kivy](https://github.com/JamesM92/micron2kivy) — the Kivy target
