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
 * - Empty/unknown returns "#".
 *
 * This does **not** special-case NomadNet's `/file/` download-file
 * convention — an earlier version collapsed those links to a bare "#",
 * but that's exactly as ambiguous as it sounds: "#" is *also* the real
 * href for a `` `[label`#] `` "jump to the next heading" link with no
 * following heading, so a consuming app couldn't tell the two apart, and
 * the fact a file link was even there got thrown away entirely — which
 * blocks a real need (confirming filename/size before download) that
 * only the host app can act on anyway. [LinkRun.target]'s
 * [LinkTarget.isFileDownload] carries that signal now instead, same
 * "expose the metadata, don't discard it" pattern already used for
 * partials — see [isFileDownloadLink].
 *
 * Ported from Micron2HTML/micron2kivy's `default_url_resolver` — same
 * algorithm and edge cases (bare-hash `<hex>:/path` detection), minus
 * their `/file/`-blocking behavior for the reason above (a divergence
 * worth backporting there too, but out of scope for this repo).
 */
fun defaultUrlResolver(url: String, nodeHash: String, basePath: String): String {
    if (url.isEmpty()) return "#"

    if (url.startsWith("http://") || url.startsWith("https://")) return url

    if (url.startsWith("nomadnetwork://")) {
        return "hash://${url.removePrefix("nomadnetwork://")}"
    }

    if (url.startsWith("hash://")) return url

    if (url.startsWith("hash:/")) {
        return "hash://${url.removePrefix("hash:/")}"
    }

    // Bare-hash format: <hex>:/path, or :/path meaning "this node".
    val colonSlash = url.indexOf(":/")
    if (colonSlash == 0 && nodeHash.isNotEmpty()) {
        return "hash://$nodeHash${url.substring(1)}"
    }
    if (colonSlash > 0) {
        val candidate = url.substring(0, colonSlash)
        if (candidate.length in 8..64 && candidate.all { it in HEX_CHARS }) {
            return "hash://$candidate${url.substring(colonSlash + 1)}"
        }
    }

    if (url.startsWith("/") && nodeHash.isNotEmpty()) {
        return "hash://$nodeHash$url"
    }

    if (nodeHash.isNotEmpty() && url.isNotEmpty()) {
        val baseDir = if (basePath.contains("/")) basePath.substringBeforeLast("/") + "/" else "/"
        return "hash://$nodeHash$baseDir$url"
    }

    return "#"
}

/**
 * Whether a raw (pre-resolution) Micron link URL points at NomadNet's
 * `/file/` download-file convention.
 *
 * Deliberately checked on the *raw* url exactly as the page author wrote
 * it, not on whatever a (possibly custom, possibly app-specific-scheme)
 * [UrlResolver] produces from it — every real form of a file link
 * (`hash://.../file/x`, `hash:/.../file/x`, a bare-hash `<hex>:/file/x`,
 * an absolute `/file/x`) already contains the literal `/file/` segment as
 * written, before any resolution happens, so this works correctly
 * regardless of what resolver is configured.
 *
 * Explicitly excludes `http(s)://` URLs — the `/file/` convention is
 * NomadNet's own internal-addressing scheme, not something that applies
 * to arbitrary external web URLs. An ordinary external link that happens
 * to have `/file/` somewhere in its path (e.g. a blog permalink) is not a
 * NomadNet file download and shouldn't be flagged as one.
 */
fun isFileDownloadLink(url: String): Boolean =
    !url.startsWith("http://") && !url.startsWith("https://") && url.contains("/file/")

/**
 * Classifies a link for a host app's own activation-safety branching
 * (nomadportal-android's specific need: e.g. warn before leaving the mesh
 * for an external web link, vs. plain in-app navigation for an internal
 * page) — see [LinkKind].
 *
 * Takes both the raw [url] and its resolved [href] because neither alone
 * is enough: [url] is needed for the `/file/` and anchor checks (which
 * must run before resolution — see [isFileDownloadLink]), but [href] is
 * needed to detect `http(s)://` since a custom [UrlResolver] could map an
 * internal reference to an external-looking URL or vice versa.
 */
fun classifyLink(url: String, href: String): LinkKind = when {
    url == "#" || url.startsWith("#") -> LinkKind.ANCHOR
    isFileDownloadLink(url) -> LinkKind.FILE_DOWNLOAD
    href.startsWith("http://") || href.startsWith("https://") -> LinkKind.EXTERNAL_WEB
    else -> LinkKind.INTERNAL_PAGE
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
