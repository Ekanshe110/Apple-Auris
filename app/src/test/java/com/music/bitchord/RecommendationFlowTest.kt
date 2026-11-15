package com.music.bitchord

import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.RecommendationAlgorithm
import com.music.bitchord.ui.flowBuildMayPublish
import com.music.bitchord.ui.flowUnlockAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused recommendation and Flow policy checks. */
class RecommendationFlowTest {

    @Test
    fun `all does not unlock from enum membership alone`() {
        assertFalse(
            flowUnlockAllowed(
                algorithm = RecommendationAlgorithm.ALL,
                favTrackCount = 0,
                favArtistCount = 0,
                hasSourceEvidence = false,
            ),
        )
        assertTrue(
            flowUnlockAllowed(
                algorithm = RecommendationAlgorithm.ALL,
                favTrackCount = 0,
                favArtistCount = 0,
                hasSourceEvidence = true,
            ),
        )
    }

    @Test
    fun `apple unlock needs Apple source evidence`() {
        assertFalse(
            flowUnlockAllowed(
                algorithm = RecommendationAlgorithm.APPLE,
                favTrackCount = 100,
                favArtistCount = 100,
                hasSourceEvidence = false,
            ),
        )
        assertTrue(
            flowUnlockAllowed(
                algorithm = RecommendationAlgorithm.APPLE,
                favTrackCount = 0,
                favArtistCount = 0,
                hasSourceEvidence = true,
            ),
        )
    }

    @Test
    fun `youtube unlock still uses favorite thresholds`() {
        assertFalse(
            flowUnlockAllowed(
                algorithm = RecommendationAlgorithm.YOUTUBE,
                favTrackCount = 15,
                favArtistCount = 0,
                hasSourceEvidence = true,
            ),
        )
        assertTrue(
            flowUnlockAllowed(
                algorithm = RecommendationAlgorithm.YOUTUBE,
                favTrackCount = 16,
                favArtistCount = 0,
                hasSourceEvidence = false,
            ),
        )
    }

    @Test
    fun `stale or mismatched flow build cannot publish`() {
        assertTrue(
            flowBuildMayPublish(
                generation = 2,
                currentGeneration = 2,
                algorithm = RecommendationAlgorithm.ALL,
                currentAlgorithm = RecommendationAlgorithm.ALL,
            ),
        )
        assertFalse(
            flowBuildMayPublish(
                generation = 1,
                currentGeneration = 2,
                algorithm = RecommendationAlgorithm.ALL,
                currentAlgorithm = RecommendationAlgorithm.ALL,
            ),
        )
        assertFalse(
            flowBuildMayPublish(
                generation = 2,
                currentGeneration = 2,
                algorithm = RecommendationAlgorithm.SPOTIFY,
                currentAlgorithm = RecommendationAlgorithm.ALL,
            ),
        )
    }

    @Test
    fun `search history track keeps recording metadata`() {
        val song = Song(
            videoId = "track",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
            isExplicit = false,
            appleCatalogId = "apple-id",
            contentRating = "clean",
            isrc = "isrc",
        )

        val history = SearchHistoryEntity.fromSong(song, subtitle = "Artist · Other")

        assertEquals(EntityType.TRACK, history.entityType)
        assertEquals("Artist · Other", history.subtitle)
        assertEquals(false, history.isExplicit)
        assertEquals("apple-id", history.appleCatalogId)
        assertEquals("clean", history.contentRating)
        assertEquals("isrc", history.isrc)
    }
}
