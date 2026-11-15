package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayCandidatesTest {

    @Test
    fun `recommendation feeds interleave before the visible limit`() {
        val spotify = (1..10).map { song("spotify-$it") }
        val youtube = (1..10).map { song("youtube-$it") }
        val apple = (1..10).map { song("apple-$it") }

        val result = interleaveRecommendationCandidates(
            groups = listOf(spotify, youtube, apple),
            limit = 4,
        )

        assertEquals(
            listOf("spotify-1", "youtube-1", "apple-1", "spotify-2"),
            result.map { it.videoId },
        )
    }

    @Test
    fun `Apple pool rotates past consumed catalogue ids and excludes exact duplicates`() {
        val pool = (1..8).map { index ->
            song("apple-video-$index", appleId = "apple-id-$index")
        }
        val consumed = listOf(
            song("existing-video-1", appleId = "apple-id-1"),
            song("existing-video-2", appleId = "apple-id-2"),
            song("existing-video-3", appleId = "apple-id-3"),
        )

        val result = selectUnseenAppleCandidates(pool, consumed, limit = 3)

        assertEquals(
            listOf("apple-id-4", "apple-id-5", "apple-id-6"),
            result.map { it.appleCatalogId },
        )
        val consumedIds = consumed.mapNotNull { it.appleCatalogId }.toSet()
        assertTrue(result.none { it.appleCatalogId?.let(consumedIds::contains) == true })
    }

    @Test
    fun `strict Apple resolution failure keeps the lookup reason`() {
        val failure = strictAppleResolutionFailure(
            listOf(song("unresolved", appleLookupError = "Apple Music catalogue not found")),
        )

        assertTrue(failure.message.orEmpty().contains("catalogue not found"))
    }

    @Test
    fun `first source failure is retained when no source answered`() {
        val sourceFailure = IllegalStateException("Apple Music is not configured")

        assertEquals(sourceFailure, firstRecommendationFailure(listOf(sourceFailure)))
    }

    private fun song(
        id: String,
        appleId: String? = null,
        appleLookupError: String? = null,
    ) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        fromAutoplay = false,
        appleCatalogId = appleId,
        appleLookupError = appleLookupError,
    )
}
