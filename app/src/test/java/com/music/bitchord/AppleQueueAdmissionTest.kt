package com.music.bitchord

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/** Strict Apple queue admission for selected and bulk playback actions. */
class AppleQueueAdmissionTest {

    private fun song(
        id: String,
        appleId: String? = null,
        localUri: String? = null,
    ) = Song(
        videoId = id,
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        appleCatalogId = appleId,
        localUri = localUri,
    )

    @Test
    fun `Apple mode keeps only exact catalogue rows from a bulk set`() {
        val rows = listOf(
            song("spotify:exact", appleId = "123"),
            song("spotify:missing"),
        )

        assertEquals(
            listOf("spotify:exact"),
            playableAppleSongs(rows, appleModeEnabled = true).map { it.videoId },
        )
    }

    @Test
    fun `source-backed and direct rows stay playable in Apple mode`() {
        val rows = listOf(
            song("src:apple::123"),
            song("https://example.test/audio"),
            song("content://media/audio/1", localUri = "content://media/audio/1"),
            song("file:///audio/track.m4a", localUri = "file:///audio/track.m4a"),
        )

        assertEquals(rows, playableAppleSongs(rows, appleModeEnabled = true))
    }

    @Test
    fun `generic downloaded row still needs an exact Apple identity`() {
        val rows = listOf(
            song("youtube:generic", localUri = "content://media/audio/1"),
        )

        assertEquals(emptyList<Song>(), playableAppleSongs(rows, appleModeEnabled = true))
    }

    @Test
    fun `non Apple mode keeps every row`() {
        val rows = listOf(song("spotify:missing"))

        assertEquals(rows, playableAppleSongs(rows, appleModeEnabled = false))
    }

    @Test
    fun `Search Back never restores a hidden tab`() {
        assertEquals(0, visibleSearchReturnTab(1, visibleTabs = listOf(0, 2, 3)))
        assertEquals(3, visibleSearchReturnTab(3, visibleTabs = listOf(0, 2, 3)))
        assertEquals(0, visibleSearchReturnTab(4, visibleTabs = listOf(0, 2, 3)))
    }
}
