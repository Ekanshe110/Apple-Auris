package com.music.bitchord

import com.music.bitchord.data.apple.asLibraryItem
import com.music.bitchord.data.apple.toSong
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Apple library identity rules that keep playback pinned to one addon. */
class AppleLibraryTest {

    @Test
    fun `library track keeps its exact stream id`() {
        val song = AddonLibraryItem(
            id = "library-resource",
            catalogId = "apple-catalog-id",
            streamId = "apple-stream-id",
            title = "Song",
            artist = "Artist",
        ).toSong("apple-config")

        assertEquals("src:apple-config::apple-stream-id", song?.videoId)
        assertEquals("apple-catalog-id", song?.appleCatalogId)
        assertEquals("Song", song?.title)
    }

    @Test
    fun `persisted liked row round trips to the exact catalog identity`() {
        val original = Song(
            videoId = "src:apple-config::apple-stream-id",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
            appleCatalogId = "apple-catalog-id",
        )

        val restored = original.asLibraryItem().toSong("apple-config")

        assertEquals(original.videoId, restored?.videoId)
        assertEquals(original.appleCatalogId, restored?.appleCatalogId)
    }

    @Test
    fun `catalog id is never used as a stream id`() {
        val song = AddonLibraryItem(
            id = "library-resource",
            catalogId = "apple-catalog-id",
            streamId = "",
            title = "Song",
        ).toSong("apple-config")

        assertNull(song)
    }

    @Test
    fun `content rating is trimmed and normalized`() {
        val song = AddonLibraryItem(
            id = "library-resource",
            streamId = "apple-stream-id",
            title = "Song",
            contentRating = "  ExPlIcIt  ",
        ).toSong("apple-config")

        assertEquals("explicit", song?.contentRating)
        assertTrue(song?.isExplicit == true)
    }

    @Test
    fun `clean rating infers false but unknown rating stays unknown`() {
        val clean = AddonLibraryItem(
            id = "library-resource",
            streamId = "apple-stream-id",
            title = "Clean Song",
            contentRating = " CLEAN ",
        ).toSong("apple-config")
        val unknown = AddonLibraryItem(
            id = "library-resource",
            streamId = "apple-stream-id",
            title = "Unknown Song",
            contentRating = " advisory ",
        ).toSong("apple-config")

        assertEquals(false, clean?.isExplicit)
        assertNull(unknown?.isExplicit)
        assertEquals("advisory", unknown?.contentRating)
    }

    @Test
    fun `published explicit flag overrides rating inference`() {
        val song = AddonLibraryItem(
            id = "library-resource",
            streamId = "apple-stream-id",
            title = "Song",
            contentRating = "clean",
            explicit = true,
        ).toSong("apple-config")

        assertEquals(true, song?.isExplicit)
        assertEquals("clean", song?.contentRating)
    }
}
