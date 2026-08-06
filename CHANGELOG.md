# Changelog

All notable changes to micron2compose are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

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
