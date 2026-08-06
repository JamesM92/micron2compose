# micron2compose

[![CI](https://github.com/JamesM92/micron2compose/actions/workflows/ci.yml/badge.svg)](https://github.com/JamesM92/micron2compose/actions/workflows/ci.yml)

A Kotlin/Jetpack Compose library that converts [Micron](https://github.com/markqvist/NomadNet) markup into real Compose UI — `AnnotatedString` for inline formatting/links, and live `TextField`/`Checkbox`/`RadioButton` composables for form fields.

Micron is the terminal markup language used by [NomadNet](https://github.com/markqvist/NomadNet) nodes. This library is a generic, standalone dependency — it doesn't assume any particular app, networking stack, or NomadNet client; any Android/Compose project that needs to render `.mu` content can depend on it directly.

This is a sibling project to [Micron2HTML](https://github.com/JamesM92/Micron2HTML) (HTML target) and [micron2kivy](https://github.com/JamesM92/micron2kivy) (Kivy target). All three share the same **NomadNet-parity** design goal and the same parsing logic — only the output side differs.

> **Status: under active development.** This README will be filled out fully (installation, usage samples, full syntax reference, known limitations, security notes) as the library reaches a usable state — see `handoff.md` in this repo for the design/build notes driving that work.

## License

MIT — see [LICENSE](LICENSE).

## Related

- [NomadNet](https://github.com/markqvist/NomadNet) — the NomadNet node software (defines the Micron spec)
- [Micron2HTML](https://github.com/JamesM92/Micron2HTML) — the HTML target, and this library's primary porting reference
- [micron2kivy](https://github.com/JamesM92/micron2kivy) — the Kivy target
