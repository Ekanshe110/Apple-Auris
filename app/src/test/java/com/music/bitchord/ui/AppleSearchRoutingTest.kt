package com.music.bitchord.ui

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.HomeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSearchRoutingTest {

    private fun song(
        videoId: String = "spotify:track",
        appleCatalogId: String? = null,
        localUri: String? = null,
    ) = Song(
        videoId = videoId,
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        appleCatalogId = appleCatalogId,
        localUri = localUri,
    )

    @Test
    fun `Apple content source starts Search in Apple catalogue`() {
        assertEquals(SearchSource.APPLE, defaultSearchSource(HomeProvider.APPLE))
    }

    @Test
    fun `other content sources keep their Search defaults`() {
        assertEquals(SearchSource.YOUTUBE, defaultSearchSource(HomeProvider.YOUTUBE))
        assertEquals(SearchSource.SPOTIFY, defaultSearchSource(HomeProvider.SPOTIFY))
    }

    @Test
    fun `discovery row without Apple identity is unavailable in Apple mode`() {
        assertTrue(appleCatalogueMatchUnavailable(song(), appleModeEnabled = true))
        assertEquals("Apple catalogue match unavailable", APPLE_CATALOGUE_MATCH_UNAVAILABLE)
    }

    @Test
    fun `existing Apple identity remains directly playable`() {
        assertFalse(
            appleCatalogueMatchUnavailable(
                song(appleCatalogId = "123456"),
                appleModeEnabled = true,
            ),
        )
    }

    @Test
    fun `source-backed and local rows need no discovery match`() {
        assertFalse(
            appleCatalogueMatchUnavailable(
                song(videoId = "src:apple-config::123456"),
                appleModeEnabled = true,
            ),
        )
        assertFalse(
            appleCatalogueMatchUnavailable(
                song(videoId = "content://media/1", localUri = "content://media/1"),
                appleModeEnabled = true,
            ),
        )
    }

    @Test
    fun `search rows report a missing Apple identity`() {
        val rows = listOf(
            SearchResult.Track(song(appleCatalogId = "123456")),
            SearchResult.Track(song(videoId = "youtube:another-track")),
        )

        assertTrue(appleCatalogueMatchUnavailable(rows, appleModeEnabled = true))
        assertFalse(
            appleCatalogueMatchUnavailable(
                rows.map { (it as SearchResult.Track).copy(song = it.song.copy(appleCatalogId = "654321")) },
                appleModeEnabled = true,
            ),
        )
    }

    @Test
    fun `Apple mode off leaves discovery routing unchanged`() {
        assertFalse(appleCatalogueMatchUnavailable(song(), appleModeEnabled = false))
    }
}
