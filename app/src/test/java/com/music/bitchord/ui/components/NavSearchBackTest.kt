package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NavSearchBackTest {

    @Test
    fun `closed search owns no Back action`() {
        assertEquals(
            NavSearchBackAction.NONE,
            NavSearchState().backAction(),
        )
    }

    @Test
    fun `keyboard Back stays nested in visual search`() {
        val state = NavSearchState(
            visualActive = true,
            keyboardActive = true,
        )

        assertEquals(NavSearchBackAction.CLOSE_KEYBOARD, state.backAction())
    }

    @Test
    fun `visual Back leaves search after keyboard is closed`() {
        val state = NavSearchState(
            visualActive = true,
            keyboardActive = false,
        )

        assertEquals(NavSearchBackAction.EXIT_VISUAL_SEARCH, state.backAction())
    }
}
