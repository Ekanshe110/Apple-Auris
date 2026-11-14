package com.music.bitchord

import com.music.bitchord.ui.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSearchSourcesTest {

    @Test
    fun `Apple mode adds Apple without displacing other catalogues`() {
        val sources = visibleSearchSources(appleModeEnabled = true)

        assertTrue(SearchSource.YOUTUBE in sources)
        assertTrue(SearchSource.SPOTIFY in sources)
        assertTrue(SearchSource.LIBRARY in sources)
        assertTrue(SearchSource.APPLE in sources)
    }

    @Test
    fun `Apple mode off keeps YouTube and Spotify available`() {
        val sources = visibleSearchSources(appleModeEnabled = false)

        assertTrue(SearchSource.YOUTUBE in sources)
        assertTrue(SearchSource.SPOTIFY in sources)
        assertTrue(SearchSource.LIBRARY in sources)
        assertFalse(SearchSource.APPLE in sources)
    }

    @Test
    fun `source order is stable when Apple mode changes`() {
        assertEquals(
            visibleSearchSources(appleModeEnabled = false).take(3),
            visibleSearchSources(appleModeEnabled = true).take(3),
        )
    }
}
