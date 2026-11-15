package com.music.bitchord.desktop

import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/** Desktop search history keeps the metadata of the exact track that was picked. */
class DesktopSearchHistoryMetadataTest {

    @Test
    fun `desktop search row keeps catalogue metadata`() {
        val song = Song(
            videoId = "track-id",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
            isExplicit = false,
            appleCatalogId = "catalog-id",
            contentRating = "clean",
            isrc = "USRC17607839",
        )

        val entity = desktopSearchHistoryEntity(song)

        assertEquals(EntityType.TRACK, entity.entityType)
        assertEquals(false, entity.isExplicit)
        assertEquals("catalog-id", entity.appleCatalogId)
        assertEquals("clean", entity.contentRating)
        assertEquals("USRC17607839", entity.isrc)
    }
}
