package com.music.bitchord

import com.music.bitchord.data.settings.HomeProvider
import com.music.bitchord.ui.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSearchSourcesTest {

    @Test
    fun `Apple provider leads and library stays last`() {
        val sources = visibleSearchSources(HomeProvider.APPLE)

        assertEquals(SearchSource.APPLE, sources.first())
        assertEquals(SearchSource.LIBRARY, sources.last())
        assertTrue(SearchSource.YOUTUBE in sources)
        assertTrue(SearchSource.SPOTIFY in sources)
    }

    @Test
    fun `YouTube provider leads without an Apple chip`() {
        val sources = visibleSearchSources(HomeProvider.YOUTUBE)

        assertEquals(SearchSource.YOUTUBE, sources.first())
        assertEquals(SearchSource.LIBRARY, sources.last())
        assertFalse(SearchSource.APPLE in sources)
    }

    @Test
    fun `Spotify provider leads without an Apple chip`() {
        val sources = visibleSearchSources(HomeProvider.SPOTIFY)

        assertEquals(SearchSource.SPOTIFY, sources.first())
        assertEquals(SearchSource.LIBRARY, sources.last())
        assertFalse(SearchSource.APPLE in sources)
    }
}
