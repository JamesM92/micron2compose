# Changelog

All notable changes to micron2compose are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `` `FT<6hex> ``/`` `BT<6hex> `` 24-bit color support, verified against live NomadNet source (`MicronParser.py`) rather than assumed absent — closes a gap the initial release deliberately left open pending verification.
- Wide-character-aware table column widths (a built-in East Asian Width approximation covering the common CJK/Fullwidth Unicode ranges — no new runtime dependency).
- `LinkTarget.isFileDownload`, matching the `LinkTarget.isPartial` pattern — see "Fixed" below.
- `fontFamily`/`monospaceFontFamily` parameters on `MicronPage`/`MicronBlock`.

### Changed

- The table width-shrink algorithm now ports NomadNet's real formula (`RNS/Utilities/rngit/util.py`'s `format_table_raw` — sort columns widest-first, drain each to the minimum before moving on) instead of an approximation.
- The `` `t `` table-toggle and its default alignment now match upstream's actual, more permissive behavior: any line starting with `` `t `` toggles table mode regardless of trailing content after the align/width suffix, and a table with no explicit align character inherits whatever alignment was already active instead of resetting to left.

### Fixed

- **`defaultUrlResolver` no longer collapses `/file/` download links to a bare `"#"`.** That was ambiguous with a `` `[label`#] `` "jump to next heading" link with no following heading (also `"#"`), and discarded the fact a file link was even there — blocking a real host-app need (confirming filename/size before download). `LinkTarget.isFileDownload` now carries that signal, with `url` still populated with the real resolved target. Found via real integration into `nomadportal-android`.

## [0.0.1] - 2026-08-06

Initial release. Ports [Micron2HTML](https://github.com/JamesM92/Micron2HTML)'s parser to Kotlin (function-by-function, cross-checked against [micron2kivy](https://github.com/JamesM92/micron2kivy) and current upstream NomadNet source) and adds a Jetpack Compose emission layer on top.

### Added

- **Parser core** (`com.jamesm92.micron2compose.parser`, zero Compose dependency): headings/sections, dividers, inline formatting (bold/italic/underline/colors/alignment), links (incl. `#anchor` and bare `#` jump targets, field-submission specs), explicit and heading auto-anchors (first-wins shared namespace), form fields (text/password/checkbox/radio), partials (parsed, data-only), literal blocks, and box-drawing tables — all producing a Compose-independent `Block`/`InlineRun` intermediate representation rather than a markup string.
- **Compose emission** (`com.jamesm92.micron2compose.compose`): `Block` → `AnnotatedString` with real `SpanStyle`s, links/partials via the current `LinkAnnotation.Clickable`/`withLink` API, and real interactive `OutlinedTextField`/`Checkbox`/`RadioButton` composables embedded inline via `InlineTextContent` for form fields — not placeholders.
- `MicronPage`/`MicronBlock` composables: a batteries-included `LazyColumn`-of-blocks renderer with scroll-to-anchor support, plus the lower-level per-block composable for custom layouts.
- `MicronFormState`: a small interface for form field values, so the library never owns app state; a simple in-memory default implementation is provided.
- Custom `UrlResolver` support, matching the HTML/Kivy siblings' resolver-injection pattern.
- 68 parser unit tests (ported from Micron2HTML's suite) plus a `showcase.mu` integration test, and 5 Compose UI instrumented tests (link taps, form field input, and a malformed-input corpus) — all verified against a real emulator, not just compiled.

### Known limitations

See [README.md](README.md#known-limitations) — partials are data-only (no live auto-refresh), table column widths aren't wide-character-aware, and the table width-shrink algorithm is a faithful-effort approximation of NomadNet's own formula. None of these are permanent; see the project's stated goal of full NomadNet-viewer parity.
