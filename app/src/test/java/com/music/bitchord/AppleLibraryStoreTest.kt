package com.music.bitchord

import com.music.bitchord.data.apple.AppleLibraryCollection
import com.music.bitchord.data.apple.AppleLibraryScopeKey
import com.music.bitchord.data.apple.AppleLibraryStore
import com.music.bitchord.data.apple.appendItems
import com.music.bitchord.data.apple.rowKey
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** Session cache and list math for the Apple library. */
class AppleLibraryStoreTest {

    @Before
    fun setup() {
        AppleLibraryStore.clear()
    }

    @Test
    fun `store returns the same scope for the same key`() {
        val config = sourceConfig(id = "test")
        val a = AppleLibraryStore.scope(config, AppleLibraryCollection.PLAYLISTS, null)
        val b = AppleLibraryStore.scope(config, AppleLibraryCollection.PLAYLISTS, null)
        assertSame(a, b)
    }

    @Test
    fun `store returns different scopes for different collections or details`() {
        val config = sourceConfig(id = "test")
        val root = AppleLibraryStore.scope(config, AppleLibraryCollection.PLAYLISTS, null)
        val detail = AppleLibraryStore.scope(config, AppleLibraryCollection.PLAYLISTS, "pl.1")
        val albums = AppleLibraryStore.scope(config, AppleLibraryCollection.ALBUMS, null)
        assertNotSame(root, detail)
        assertNotSame(root, albums)
        assertNotSame(detail, albums)
    }

    @Test
    fun `scope key is stable across process death`() {
        val key = AppleLibraryScopeKey(
            configId = "test",
            collection = AppleLibraryCollection.PLAYLISTS,
            collectionId = "pl.1",
        )
        assertEquals("test|playlists|pl.1", key.cacheKey)
    }

    @Test
    fun `row key prefers id then catalogId then title and artist`() {
        val idRow = AddonLibraryItem(id = "a", catalogId = "b", title = "x", artist = "y")
        val catRow = AddonLibraryItem(id = "", catalogId = "b", title = "x", artist = "y")
        val textRow = AddonLibraryItem(id = "", catalogId = "", title = "x", artist = "y")
        assertEquals("a", idRow.rowKey())
        assertEquals("b", catRow.rowKey())
        assertEquals("x\u0000y", textRow.rowKey())
    }

    @Test
    fun `append items keeps first occurrence and deduplicates repeats by key`() {
        val a = AddonLibraryItem(id = "1", title = "One", artist = "A")
        val b = AddonLibraryItem(id = "2", title = "Two", artist = "B")
        val aAgain = AddonLibraryItem(id = "1", title = "One again", artist = "A")
        val merged = appendItems(listOf(a, b), listOf(aAgain))
        assertEquals(2, merged.size)
        assertEquals("One", merged[0].title)
    }

    @Test
    fun `append items keeps rows with blank ids but different titles`() {
        val a = AddonLibraryItem(id = "", title = "One", artist = "A")
        val b = AddonLibraryItem(id = "", title = "Two", artist = "B")
        val merged = appendItems(listOf(a), listOf(b))
        assertEquals(2, merged.size)
    }

    private fun sourceConfig(id: String) = com.music.bitchord.data.sources.SourceConfig(
        id = id,
        baseUrl = "https://example.test/$id",
        kind = com.music.bitchord.data.sources.SourceKind.ADDON,
    )
}
