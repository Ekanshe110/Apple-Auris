package com.music.bitchord

import com.music.bitchord.data.apple.AppleLibraryCollection
import com.music.bitchord.data.apple.AppleLibraryScopeKey
import com.music.bitchord.data.apple.AppleLibraryState
import com.music.bitchord.data.apple.AppleLibraryStore
import com.music.bitchord.data.apple.appendItems
import com.music.bitchord.data.apple.libraryAccountFingerprint
import com.music.bitchord.data.apple.occurrenceKey
import com.music.bitchord.data.apple.rowKey
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val a = AppleLibraryStore.scope(
            config,
            AppleLibraryCollection.PLAYLISTS,
            null,
            dispatcher = Dispatchers.Unconfined,
        )
        val b = AppleLibraryStore.scope(
            config,
            AppleLibraryCollection.PLAYLISTS,
            null,
            dispatcher = Dispatchers.Unconfined,
        )
        assertSame(a, b)
    }

    @Test
    fun `store returns different scopes for different collections or details`() {
        val config = sourceConfig(id = "test")
        val root = AppleLibraryStore.scope(
            config,
            AppleLibraryCollection.PLAYLISTS,
            null,
            dispatcher = Dispatchers.Unconfined,
        )
        val detail = AppleLibraryStore.scope(
            config,
            AppleLibraryCollection.PLAYLISTS,
            "pl.1",
            dispatcher = Dispatchers.Unconfined,
        )
        val albums = AppleLibraryStore.scope(
            config,
            AppleLibraryCollection.ALBUMS,
            null,
            dispatcher = Dispatchers.Unconfined,
        )
        assertNotSame(root, detail)
        assertNotSame(root, albums)
        assertNotSame(detail, albums)
    }

    @Test
    fun `account switch isolates cached library scopes`() {
        val first = sourceConfig(id = "test", settings = mapOf("libraryToken" to "account-a"))
        val second = first.copy(settings = mapOf("libraryToken" to "account-b"))
        val firstScope = AppleLibraryStore.scope(
            first,
            AppleLibraryCollection.PLAYLISTS,
            null,
            dispatcher = Dispatchers.Unconfined,
        )
        val secondScope = AppleLibraryStore.scope(
            second,
            AppleLibraryCollection.PLAYLISTS,
            null,
            dispatcher = Dispatchers.Unconfined,
        )
        assertNotSame(firstScope, secondScope)
        assertNotEquals(first.libraryAccountFingerprint(), second.libraryAccountFingerprint())
        assertNotEquals(
            first.libraryAccountFingerprint(),
            first.libraryAccountFingerprint().replace(Regex("[0-9a-f]"), "0"),
        )
    }

    @Test
    fun `scope key is stable across process death`() {
        val key = AppleLibraryScopeKey(
            configId = "test",
            baseUrl = "https://example.test/test",
            accountFingerprint = "account",
            collection = AppleLibraryCollection.PLAYLISTS,
            collectionId = "pl.1",
        )
        assertEquals("test|account|playlists|pl.1", key.cacheKey)
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
    fun `append items preserves legitimate repeated rows`() {
        val first = AddonLibraryItem(id = "1", title = "One", artist = "A")
        val second = AddonLibraryItem(id = "2", title = "Two", artist = "B")
        val firstAgain = AddonLibraryItem(id = "1", title = "One", artist = "A")
        val merged = appendItems(listOf(first, second), listOf(firstAgain))
        assertEquals(3, merged.size)
        assertEquals("1", merged[2].id)
        assertNotEquals(occurrenceKey(merged, 0), occurrenceKey(merged, 2))
    }

    @Test
    fun `append items keeps rows with blank ids but different titles`() {
        val a = AddonLibraryItem(id = "", title = "One", artist = "A")
        val b = AddonLibraryItem(id = "", title = "Two", artist = "B")
        val merged = appendItems(listOf(a), listOf(b))
        assertEquals(2, merged.size)
    }

    @Test
    fun `refresh keeps later pages and cursor`() = runTest {
        val config = sourceConfig(id = "test")
        val pages = mapOf(
            0 to listOf("1", "2"),
            2 to listOf("3", "4"),
            4 to listOf("5", "6"),
        )
        val scope = AppleLibraryStore.scope(
            config,
            AppleLibraryCollection.PLAYLISTS,
            null,
            dispatcher = Dispatchers.Unconfined,
            loader = { _, offset ->
                AppleLibraryState.Ready(
                    items = pages.getValue(offset).map { AddonLibraryItem(id = it, title = it) },
                    hasMore = offset < 4,
                    nextOffset = if (offset < 4) offset + 2 else null,
                )
            },
        )

        scope.open(config)
        scope.loadMore(config)
        scope.loadMore(config)
        assertEquals(6, scope.state.value.items.size)
        assertEquals(6, scope.state.value.nextOffset)

        scope.open(config)

        assertEquals(6, scope.state.value.items.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), scope.state.value.items.map { it.id })
        assertEquals(6, scope.state.value.nextOffset)
    }

    private fun sourceConfig(
        id: String,
        settings: Map<String, String> = emptyMap(),
    ) = com.music.bitchord.data.sources.SourceConfig(
        id = id,
        baseUrl = "https://example.test/$id",
        kind = com.music.bitchord.data.sources.SourceKind.ADDON,
        settings = settings,
    )
}
