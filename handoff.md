# Task: build micron2compose

Written to be handed directly to an AI assistant starting the
`micron2compose` codebase.

`micron2compose` converts NomadNet's **Micron** markup into Jetpack Compose
UI (`AnnotatedString` + real interactive composables), for use in
`nomadportal-android`. It's the third Micron renderer in this project
family — `Micron2HTML` (HTML, done) and `micron2kivy` (Kivy, secondary/
lower-priority track) are the other two. Not a port of either's output
format, but see below on reuse.

## Where to get the real grammar — don't guess, don't rely on training data

Three sources, cross-check them against each other:

1. **Canonical implementation**: `nomadnet/ui/textui/MicronParser.py` in
   `github.com/markqvist/NomadNet`. **GPL-3.0, not owned by us — read for
   understanding, never copy/transcribe/closely-paraphrase code from it
   into this project.** Same "don't copy a differently-licensed project's
   source" rule `nomadportal_android_handoff.md` already applies to
   Sideband, just a different license/reason here.
2. **Canonical spec with worked examples**: `nomadnet/ui/textui/Guide.py`
   in the same repo — NomadNet's own in-app help page, written in Micron
   itself, documents every construct with before/after examples. Same
   GPL/reference-only caveat as above.
3. **`jamesm92/micron2html`'s `converter.py`** — MIT-licensed, and *owned
   by the project author*, so this one is fair game to port from liberally
   (structure, algorithms, function-by-function translation to Kotlin, test
   fixtures). This is the primary reference implementation, not just prior
   art to skim. It also has a real test suite (`tests/`) and a `.mu`
   fixture (`examples/showcase.mu`) — use both as your starting test corpus
   before inventing new ones.

## Things worth knowing before you start (found by reading the above, not assumed)

- **Formatting state is global/streaming across the whole document, not
  per-line.** Parse top-to-bottom with carried state; don't parse lines
  independently.
- **Anchors bind to block/line index, not a character offset.** This
  answers the "block-list vs. single continuous Text" layout question the
  original `micron2compose` architecture notes left open — use a block-list
  model (`LazyColumn`, one composable per output block), since that's the
  granularity anchors are natively defined at.
- **Tables' input format is Markdown-style pipe rows** (`| a | b |` +
  alignment separator row) between `` `t ``...`` `t ``, not raw ASCII art —
  the renderer does fixed-width column layout itself. NomadNet's own
  implementation delegates this to a Python-only helper you won't have
  access to, so this is the one piece needing a genuinely new algorithm
  rather than a port; `Micron2HTML`'s `_render_table` is a second working
  implementation of it worth translating.
- Everything else (inline color/bold/italic/underline tags, links w/
  `#anchor` and bare `#`-next-heading targets, form fields incl.
  checkbox/radio, page-level `#!bg=`/`#!fg=`/`#!c=` headers, literal
  blocks, auto-anchor slugs from headings) is documented with exact syntax
  in `Guide.py` — read it directly rather than having this doc restate it
  secondhand.

## Target shape

Match the other two targets: a `ConvertResult`-style return (parsed
blocks + an anchor-name → block-index map + page fg/bg), with the
tokenizer/parser kept separate from the Compose-specific emission layer —
same "shared parser, target-specific emission" split the original handoff
doc already establishes. Use `AnnotatedString.stringAnnotations` for link
taps (Compose's native mechanism, no side-table needed the way Kivy
required). Build real `TextField`/`Checkbox`/`RadioButton` composables
inline for form fields — this is Compose's actual advantage over the Kivy
target, don't settle for placeholders.

## Security posture

Same trust model as the rest of this project family (`porting-notes.md`
§3): every `.mu` document is untrusted remote content. No Micron construct
has an execution path — keep it that way, validate anything derived from
untrusted input before it's used as a URL/path/annotation tag, and never
crash on malformed input (render best-effort instead — this doubles as
the robustness the page editor's live preview needs, per
`nomadportal_android_handoff.md`'s "Page editor" section).

## Open questions — your call, not decided here

- Standalone repo (recommended, matches `Micron2HTML`/`micron2kivy`'s
  pattern and the original handoff doc's "its own well-tested module") vs.
  a module inside `nomadportal-android`.
- License — new code, not obligated to match anything. MIT (matching
  `Micron2HTML`) is the obvious default; decide deliberately rather than by
  default either way.
- Whether to build partials (`` `{url`refresh`fields} ``, an auto-refresh
  embed directive) now or defer.

## Sequencing

Parser core (plain Kotlin, no Compose dependency, tested against
`Micron2HTML`'s fixtures) → table algorithm → Compose emission layer →
link/form-field interactivity → hand off to `nomadportal-android` for
integration (that repo's sequencing step 4).
