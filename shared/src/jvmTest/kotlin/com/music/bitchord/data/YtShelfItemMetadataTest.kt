package com.music.bitchord.data

import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Discovery cards keep exact-recording metadata when converted to songs. */
class YtShelfItemMetadataTest {

    @Test
    fun `shelf conversion preserves all catalogue metadata`() {
        val song = ShelfItem(
            title = "Title",
            subtitle = "Song • Artist",
            thumbnailUrl = "https://art/a.jpg",
            videoId = "track-id",
            browseId = null,
            durationText = "3:42",
            isExplicit = false,
            contentRating = "clean",
            isrc = "USRC17607839",
            appleCatalogId = "catalog-id",
            appleLookupError = "lookup failed",
        ).toSongOrNull()

        assertEquals("track-id", song?.videoId)
        assertEquals("3:42", song?.durationText)
        assertEquals(false, song?.isExplicit)
        assertEquals("clean", song?.contentRating)
        assertEquals("USRC17607839", song?.isrc)
        assertEquals("catalog-id", song?.appleCatalogId)
        assertEquals("lookup failed", song?.appleLookupError)
    }

    @Test
    fun `unknown explicit state stays unknown`() {
        val song = ShelfItem(
            title = "Title",
            subtitle = "Artist",
            thumbnailUrl = null,
            videoId = "track-id",
            browseId = null,
        ).toSongOrNull()

        assertNull(song?.isExplicit)
        assertNull(song?.contentRating)
        assertNull(song?.isrc)
    }
}
