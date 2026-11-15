package com.music.bitchord

import com.music.bitchord.data.model.HomeHeader
import com.music.bitchord.ui.spotifyHeaderAfterRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who owns the Home header after one Spotify read.
 *
 * A failed read must not erase what the listener already had. An answered read
 * owns the header even when it brings none, so a stale greeting and chips do
 * not survive a Spotify page that stopped sending them.
 */
class HomeHeaderOwnershipTest {

    @Test
    fun `failed read keeps the header the listener already had`() {
        val previous = HomeHeader(greeting = "Good evening")

        assertEquals(
            previous,
            spotifyHeaderAfterRead(
                previous = previous,
                answered = false,
                outcomeHeader = null,
            ),
        )
    }

    @Test
    fun `answered read with no header clears the stale header`() {
        assertNull(
            spotifyHeaderAfterRead(
                previous = HomeHeader(greeting = "Good evening"),
                answered = true,
                outcomeHeader = null,
            ),
        )
    }

    @Test
    fun `answered read publishes its own header`() {
        val fresh = HomeHeader(greeting = "Hello")

        assertEquals(
            fresh,
            spotifyHeaderAfterRead(
                previous = HomeHeader(greeting = "Good evening"),
                answered = true,
                outcomeHeader = fresh,
            ),
        )
    }
}
