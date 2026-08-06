package com.jamesm92.micron2compose.parser

/**
 * Callable `(rawUrl, nodeHash, basePath) -> resolvedUrl`, invoked for every
 * link/partial found in the input. Defaults to [defaultUrlResolver]. A host
 * app wraps this to produce its own URL scheme (e.g. an in-app route
 * identifier instead of a canonical `hash://` URL) — see README "Custom URL
 * resolution".
 */
typealias UrlResolver = (url: String, nodeHash: String, basePath: String) -> String

private val HEX_CHARS = (('0'..'9') + ('a'..'f') + ('A'..'F')).toHashSet()

/**
 * Library default: produces canonical NomadNet URLs without any
 * app-specific wrapping.
 *
 * - `http(s)://` URLs pass through unchanged.
 * - `hash:/...` and `nomadnetwork://` URLs are canonicalized to
 *   `hash://<hash>/<path>`.
 * - Relative paths are resolved against ([nodeHash], [basePath]).
 * - `/file/` links are blocked (returns "#") — matches NomadNet's own
 *   download-file convention, which a renderer shouldn't silently follow
 *   as a plain page link.
 * - Empty/unknown returns "#".
 *
 * Ported from Micron2HTML/micron2kivy's `default_url_resolver` — same
 * algorithm, same edge cases (bare-hash `<hex>:/path` detection, blocked
 * paths checked before the scheme is finalized).
 */
fun defaultUrlResolver(url: String, nodeHash: String, basePath: String): String {
    if (url.isEmpty()) return "#"

    if (url.startsWith("http://") || url.startsWith("https://")) return url

    fun isBlocked(u: String) = u.contains("/file/")

    if (url.startsWith("nomadnetwork://")) {
        val body = url.removePrefix("nomadnetwork://")
        return if (isBlocked("/$body")) "#" else "hash://$body"
    }

    if (url.startsWith("hash://")) {
        return if (isBlocked(url)) "#" else url
    }

    if (url.startsWith("hash:/")) {
        return if (isBlocked(url)) "#" else "hash://${url.removePrefix("hash:/")}"
    }

    // Bare-hash format: <hex>:/path, or :/path meaning "this node".
    val colonSlash = url.indexOf(":/")
    if (colonSlash == 0 && nodeHash.isNotEmpty()) {
        val pathPart = url.substring(1)
        return if (isBlocked(pathPart)) "#" else "hash://$nodeHash$pathPart"
    }
    if (colonSlash > 0) {
        val candidate = url.substring(0, colonSlash)
        if (candidate.length in 8..64 && candidate.all { it in HEX_CHARS }) {
            val full = "hash://$candidate${url.substring(colonSlash + 1)}"
            return if (isBlocked(full)) "#" else full
        }
    }

    if (url.startsWith("/") && nodeHash.isNotEmpty()) {
        return if (isBlocked(url)) "#" else "hash://$nodeHash$url"
    }

    if (nodeHash.isNotEmpty() && url.isNotEmpty()) {
        val baseDir = if (basePath.contains("/")) basePath.substringBeforeLast("/") + "/" else "/"
        return "hash://$nodeHash$baseDir$url"
    }

    return "#"
}

// ---------------------------------------------------------------------------
// Anchors
//
// Ported from Micron2HTML/micron2kivy's slugify_micron, itself ported from
// NomadNet's own MicronParser.py (cross-checked against current upstream
// this project's design session, no drift) — so auto-anchor slugs generated
// here match what real NomadNet would generate for the same heading text.
// ---------------------------------------------------------------------------

private val MICRON_STRIP_RE = Regex(
    "`[FB]T[0-9a-fA-F]{6}" +
        "|`[FB][0-9a-fA-F]{3}" +
        "|`:[A-Za-z0-9_-]*" +
        "|`[!*_=fbacrl`<>{]"
)

private val NON_ALPHANUMERIC_RE = Regex("[^A-Za-z0-9]+")

/**
 * Slugify heading text into an anchor name, matching NomadNet exactly.
 *
 * Strips Micron formatting tokens first (color, bold/italic/underline/
 * reset, alignment, link/field/partial-open, and anchor-declaration
 * tokens), then lowercases, collapses runs of non-alphanumeric characters
 * into a single hyphen, and strips leading/trailing hyphens.
 *
 * `">Hello World"` -> `"hello-world"`; `">Introduction & Setup"` ->
 * `"introduction-setup"`.
 */
fun slugifyMicron(text: String?): String {
    if (text == null) return ""
    val stripped = MICRON_STRIP_RE.replace(text, "")
    return NON_ALPHANUMERIC_RE.replace(stripped, "-").trim('-').lowercase()
}
