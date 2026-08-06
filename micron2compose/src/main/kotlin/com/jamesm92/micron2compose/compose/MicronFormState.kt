package com.jamesm92.micron2compose.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember

/**
 * Backing store for the values of form fields ([com.jamesm92.micron2compose.parser.FieldSpec])
 * rendered by [MicronPage]/[MicronBlock].
 *
 * This library never owns form state itself — deliberately, so a page can
 * be embedded in whatever state-management approach a host app already
 * uses (a `ViewModel`, a `remember`ed map, anything). Implement this
 * interface over your own store, or use [rememberDefaultMicronFormState]
 * for a simple in-memory one scoped to the current composition.
 *
 * Implementations backing RADIO fields correctly need [getValue] reads to
 * participate in Compose's recomposition tracking — sibling radio buttons
 * for the same field name are independent composables that only agree on
 * which one is selected by reading this on every recomposition (there's no
 * per-widget local state for radio groups; see `fieldInlineContent` in
 * `MicronText.kt`). Back your store with Compose `State` (as the default
 * implementation does via `mutableStateMapOf`) to get this for free.
 */
interface MicronFormState {
    fun getValue(name: String): String
    fun setValue(name: String, value: String)
}

private class DefaultMicronFormState : MicronFormState {
    private val values = mutableStateMapOf<String, String>()
    override fun getValue(name: String): String = values[name] ?: ""
    override fun setValue(name: String, value: String) {
        values[name] = value
    }
}

/**
 * A simple in-memory [MicronFormState], scoped to the current composition
 * and backed by Compose state. Good enough for quick use, previews, and
 * tests; a real app will more often implement [MicronFormState] over
 * whatever state it already owns (a `ViewModel`, saved-instance state,
 * etc.) so field values survive beyond a single composition's lifetime.
 */
@Composable
fun rememberDefaultMicronFormState(): MicronFormState = remember { DefaultMicronFormState() }
