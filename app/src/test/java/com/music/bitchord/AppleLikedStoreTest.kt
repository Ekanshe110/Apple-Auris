package com.music.bitchord

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.apple.AppleLikedStore
import com.music.bitchord.data.apple.asLibraryItem
import com.music.bitchord.data.apple.parseDurationText
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Local Apple-liked persistence and helper mapping. */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class AppleLikedStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setup() {
        context.getSharedPreferences("apple_liked", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        AppleLikedStore.init(context)
    }

    @Test
    fun `toggle adds and removes a liked row`() {
        val item = sampleItem("s1")
        assertFalse(AppleLikedStore.isLiked("s1"))
        assertTrue(AppleLikedStore.toggle(item))
        assertTrue(AppleLikedStore.isLiked("s1"))
        assertFalse(AppleLikedStore.toggle(item))
        assertFalse(AppleLikedStore.isLiked("s1"))
    }

    @Test
    fun `persisted rows survive a fresh init`() {
        AppleLikedStore.add(sampleItem("s2", title = "Persisted"))
        AppleLikedStore.init(context)
        assertTrue(AppleLikedStore.isLiked("s2"))
        assertEquals("Persisted", AppleLikedStore.items.value.first().title)
    }

    @Test
    fun `song maps to library item preserving ids and artwork`() {
        val song = Song(
            videoId = "src:apple::stream-1",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = "https://example.test/art.jpg",
            durationText = "3:45",
            albumName = "Album",
            appleCatalogId = "catalog-1",
            contentRating = "explicit",
            isExplicit = true,
            isrc = "USXXX1234567",
        )
        val item = song.asLibraryItem()
        assertEquals(song.videoId, item.id)
        assertEquals(song.appleCatalogId, item.catalogId)
        assertEquals(song.title, item.title)
        assertEquals(song.artist, item.artist)
        assertEquals(song.albumName, item.album)
        assertEquals(song.thumbnailUrl, item.artworkURL)
        assertEquals(225.0, item.duration ?: 0.0, 0.001)
        assertEquals(song.contentRating, item.contentRating)
        assertEquals(song.isExplicit, item.explicit)
        assertEquals(song.isrc, item.isrc)
    }

    @Test
    fun `duration parser handles minutes and hours`() {
        assertEquals(275.0, parseDurationText("4:35") ?: 0.0, 0.001)
        assertEquals(3661.0, parseDurationText("1:01:01") ?: 0.0, 0.001)
        assertEquals(null, parseDurationText("nope"))
        assertEquals(null, parseDurationText("1:2:3:4"))
    }

    private fun sampleItem(id: String, title: String = "Song") =
        com.music.bitchord.data.sources.addon.AddonLibraryItem(
            id = id,
            streamId = id,
            title = title,
            artist = "Artist",
        )
}
