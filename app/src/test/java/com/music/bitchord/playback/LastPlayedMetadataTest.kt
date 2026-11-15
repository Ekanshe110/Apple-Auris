package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Metadata needed to identify the exact restored recording survives the queue snapshot. */
class LastPlayedMetadataTest {

    @Test
    fun `stored track persists and restores catalogue metadata`() {
        val song = Song(
            videoId = "track-id",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
            isExplicit = false,
            contentRating = "clean",
            isrc = "USRC17607839",
        )

        val stored = LastPlayed.StoredTrack.from(song)
        val encoded = Json.encodeToString(LastPlayed.StoredTrack.serializer(), stored)
        val restored = Json.decodeFromString(LastPlayed.StoredTrack.serializer(), encoded).toSong()

        assertEquals("clean", restored.contentRating)
        assertEquals("USRC17607839", restored.isrc)
        assertEquals(false, restored.isExplicit)
    }
}
