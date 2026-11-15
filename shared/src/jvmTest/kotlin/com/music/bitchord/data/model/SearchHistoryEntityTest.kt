package com.music.bitchord.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Search-history track rows keep exact-recording metadata across persistence. */
class SearchHistoryEntityTest {

    @Test
    fun `song factory carries catalogue metadata into the history row`() {
        val song = Song(
            videoId = "track-id",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = "https://art/a.jpg",
            isExplicit = false,
            appleCatalogId = "catalog-id",
            contentRating = "clean",
            isrc = "USRC17607839",
        )

        val entity = SearchHistoryEntity.fromSong(song)

        assertEquals(EntityType.TRACK, entity.entityType)
        assertEquals(false, entity.isExplicit)
        assertEquals("catalog-id", entity.appleCatalogId)
        assertEquals("clean", entity.contentRating)
        assertEquals("USRC17607839", entity.isrc)
    }

    @Test
    fun `catalogue metadata survives json persistence`() {
        val entity = SearchHistoryEntity(
            id = "track-id",
            title = "Title",
            subtitle = "Artist",
            artworkUrl = null,
            entityType = EntityType.TRACK,
            isExplicit = true,
            contentRating = "explicit",
            isrc = "USRC17607839",
        )

        val encoded = Json.encodeToString(SearchHistoryEntity.serializer(), entity)
        val restored = Json.decodeFromString(SearchHistoryEntity.serializer(), encoded)

        assertEquals(true, restored.isExplicit)
        assertEquals("explicit", restored.contentRating)
        assertEquals("USRC17607839", restored.isrc)
    }

    @Test
    fun `legacy rows without catalogue metadata still decode`() {
        val legacy = """
            {"id":"track-id","title":"Title","subtitle":"Artist","artworkUrl":null,"entityType":"TRACK"}
        """.trimIndent()

        val restored = Json.decodeFromString(SearchHistoryEntity.serializer(), legacy)

        assertNull(restored.isExplicit)
        assertNull(restored.contentRating)
        assertNull(restored.isrc)
    }
}
