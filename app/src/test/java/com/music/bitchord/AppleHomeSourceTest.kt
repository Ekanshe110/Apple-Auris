package com.music.bitchord

import com.music.bitchord.data.apple.AppleHomeSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.addon.AddonHomeResponse
import com.music.bitchord.data.sources.addon.AddonHomeItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mapping from addon home items to shelf cards. */
class AppleHomeSourceTest {

    @Test
    fun `song item becomes playable shelf card with apple catalog id`() {
        val item = AddonHomeItem(
            type = "song",
            id = "12345",
            title = "Song",
            artist = "Artist",
            duration = 275.0,
            artworkURL = "https://example.test/art.jpg",
            contentRating = "explicit",
            explicit = true,
            isrc = "USXXX1234567",
        )
        val card = AppleHomeSource.run { item.toShelfItem() }
        assertEquals("Song", card.title)
        assertEquals("Artist", card.subtitle)
        assertEquals("12345", card.videoId)
        assertEquals("12345", card.appleCatalogId)
        assertEquals("4:35", card.durationText)
        assertEquals(true, card.isExplicit)
        assertNull(card.browseId)
    }

    @Test
    fun `album item becomes browse card`() {
        val item = AddonHomeItem(
            type = "album",
            id = "a.1",
            title = "Album",
            artist = "Band",
            artworkURL = "https://example.test/album.jpg",
        )
        val card = AppleHomeSource.run { item.toShelfItem() }
        assertEquals("Album", card.title)
        assertEquals("a.1", card.browseId?.removePrefix("apple:album:"))
        assertNull(card.videoId)
        assertNull(card.appleCatalogId)
    }

    @Test
    fun `playlist item becomes browse card`() {
        val item = AddonHomeItem(
            type = "playlist",
            id = "p.2",
            title = "Playlist",
            artist = "Curator",
        )
        val card = AppleHomeSource.run { item.toShelfItem() }
        assertEquals("apple:playlist:p.2", card.browseId)
        assertNull(card.videoId)
    }

    @Test
    fun `song toShelfItem maps Apple fields`() {
        val song = Song(
            videoId = "src:apple::track-1",
            title = "Track",
            artist = "Singer",
            thumbnailUrl = "https://example.test/t.jpg",
            durationText = "3:30",
            appleCatalogId = "999",
            isExplicit = false,
            contentRating = "clean",
        )
        val card = AppleHomeSource.run { song.toShelfItem() }
        assertEquals("src:apple::track-1", card.videoId)
        assertEquals("999", card.appleCatalogId)
        assertEquals("3:30", card.durationText)
    }

    @Test
    fun `home response parses server shelves json`() {
        val json = Json { ignoreUnknownKeys = true }
        val text = """
            {
              "shelves": [
                {
                  "title": "Made for You",
                  "subtitle": "Curated just for you",
                  "artworkURL": "https://example.test/hero.jpg",
                  "items": [
                    {"type": "song", "id": "1", "title": "One", "artist": "A", "duration": 200.0},
                    {"type": "album", "id": "a.1", "title": "Album", "artist": "B"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val response = json.decodeFromString(AddonHomeResponse.serializer(), text)
        assertEquals(1, response.shelves.size)
        assertEquals("Made for You", response.shelves[0].title)
        assertEquals(2, response.shelves[0].items.size)
        assertEquals("song", response.shelves[0].items[0].type)
        assertEquals("album", response.shelves[0].items[1].type)
    }
}
