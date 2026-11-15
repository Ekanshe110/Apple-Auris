/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.bitchord.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import com.music.bitchord.ui.SearchSource

/** What one hardware Back press should do in the folding search stack. */
internal enum class NavSearchBackAction {
    /** Search owns neither Back layer. */
    NONE,

    /** The keyboard is open; close it without leaving visual search. */
    CLOSE_KEYBOARD,

    /** Visual search is open; leave it and restore the previous tab and player. */
    EXIT_VISUAL_SEARCH,
}

@Immutable
data class NavSearchState(
    val visualActive: Boolean = false,
    val keyboardActive: Boolean = false,
    /** A detail page or overlay owns Back while it is above the search stack. */
    val higherLayerActive: Boolean = false,
    val query: String = "",
    /**
     * The field's own text and caret.
     *
     * Held here rather than inside the field, because the bar is rebuilt every
     * time it folds and unfolds — and a text field that is rebuilt starts with
     * its caret at the very beginning of its text. Tapping back into a search
     * that had already been typed put the cursor in front of everything that
     * had been written, and every character after that went in front of it too.
     */
    val textValue: TextFieldValue = TextFieldValue(""),
    val onTextValueChange: (TextFieldValue) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val searchSource: SearchSource = SearchSource.YOUTUBE,
    val onToggleSource: () -> Unit = {},
    val canToggleSource: Boolean = true,
    val onTapSearchIcon: () -> Unit = {},
    val onTapBar: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onCloseKeyboard: () -> Unit = {},
    val focusRequester: FocusRequester = FocusRequester(),
)

/**
 * The Back action for the current search layer.
 *
 * Keyboard search is nested: Back closes only the keyboard and leaves visual
 * search in place. Once the keyboard is gone, Back leaves visual search and
 * restores the previous tab together with the folding player stack.
 */
internal fun NavSearchState.backAction(): NavSearchBackAction = when {
    higherLayerActive -> NavSearchBackAction.NONE
    keyboardActive -> NavSearchBackAction.CLOSE_KEYBOARD
    visualActive -> NavSearchBackAction.EXIT_VISUAL_SEARCH
    else -> NavSearchBackAction.NONE
}

val LocalNavSearchState = staticCompositionLocalOf { NavSearchState() }
