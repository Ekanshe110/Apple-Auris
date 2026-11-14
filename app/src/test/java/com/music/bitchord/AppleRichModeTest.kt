package com.music.bitchord

import com.music.bitchord.data.apple.AppleEnrichment
import com.music.bitchord.data.flow.decodeFlowSnapshot
import com.music.bitchord.data.flow.encodeFlowSnapshot
import com.music.bitchord.data.liked.decodeLikedRows
import com.music.bitchord.data.liked.encodeLikedRows
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.HomeProvider
import com.music.bitchord.data.sources.SourceCatalogRole
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.playback.requiresStrictAppleIdentity
import com.music.bitchord.playback.strictAppleWatchUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleRichModeTest {

    private fun song(
        id: String = "spotify:track",
        title: String = "Sanam Teri Kasam",
        artist: String = "Shaan",
        duration: String? = "4:00",
        appleId: String? = null,
        lookupError: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        appleCatalogId = appleId,
        appleLookupError = lookupError,
    )

    @Test
    fun `exact Apple identity ignores punctuation and case`() {
        val wanted = song()
        val candidate = song(
            id = "src:apple::123",
            title = "Sanam Teri Kasam!",
            artist = "SHAAN",
            duration = "4:01",
            appleId = "123",
        )

        assertEquals("123", AppleEnrichment.exactAppleId(wanted, listOf(candidate)))
    }

    @Test
    fun `near artist is not an exact Apple identity`() {
        val wanted = song()
        val candidate = song(
            id = "src:apple::123",
            title = wanted.title,
            artist = "Shaan & Someone Else",
            appleId = "123",
        )

        assertNull(AppleEnrichment.exactAppleId(wanted, listOf(candidate)))
    }

    @Test
    fun `ambiguous Apple rows are rejected rather than guessed`() {
        val wanted = song()
        val candidates = listOf(
            song(id = "src:apple::123", appleId = "123"),
            song(id = "src:apple::456", appleId = "456"),
        )

        assertNull(AppleEnrichment.exactAppleId(wanted, candidates))
    }

    @Test
    fun `Apple identity survives liked and Flow persistence`() {
        val original = song(appleId = "1234567890")

        assertEquals("1234567890", decodeLikedRows(encodeLikedRows(listOf(original))).single().appleCatalogId)
        assertEquals("1234567890", decodeFlowSnapshot(encodeFlowSnapshot(listOf(original))).single().appleCatalogId)
    }

    @Test
    fun `lookup failure survives persistence without becoming an Apple id`() {
        val original = song(lookupError = "Apple Music catalogue not found")

        val liked = decodeLikedRows(encodeLikedRows(listOf(original))).single()
        val flow = decodeFlowSnapshot(encodeFlowSnapshot(listOf(original))).single()
        assertNull(liked.appleCatalogId)
        assertEquals("Apple Music catalogue not found", liked.appleLookupError)
        assertNull(flow.appleCatalogId)
        assertEquals("Apple Music catalogue not found", flow.appleLookupError)
    }

    @Test
    fun `legacy Apple migration never overrides an existing provider choice`() {
        assertEquals(
            HomeProvider.APPLE,
            AppSettings.migratedHomeProvider(storedProvider = null, legacyAppleEnabled = true),
        )
        assertEquals(
            HomeProvider.YOUTUBE,
            AppSettings.migratedHomeProvider(
                storedProvider = HomeProvider.YOUTUBE.name,
                legacyAppleEnabled = true,
            ),
        )
        assertEquals(
            HomeProvider.SPOTIFY,
            AppSettings.migratedHomeProvider(
                storedProvider = HomeProvider.SPOTIFY.name,
                legacyAppleEnabled = true,
            ),
        )
    }

    @Test
    fun `Apple enrichment identity is scoped to source and account`() {
        val base = SourceConfig(
            id = "apple-config",
            kind = SourceKind.ADDON,
            baseUrl = "https://apple.invalid",
            settings = mapOf("libraryToken" to "account-one"),
        )
        val otherAccount = base.copy(settings = mapOf("libraryToken" to "account-two"))
        val otherSource = base.copy(id = "another-apple-config")

        assertNotEquals(
            AppleEnrichment.sourceIdentity(base),
            AppleEnrichment.sourceIdentity(otherAccount),
        )
        assertNotEquals(
            AppleEnrichment.sourceIdentity(base),
            AppleEnrichment.sourceIdentity(otherSource),
        )
    }

    @Test
    fun `persisted lookup error remains retryable`() {
        val failed = song(lookupError = "Apple Music catalogue not found")

        with(AppleEnrichment) {
            assertTrue(failed.needsAppleLookup())
        }
    }

    @Test
    fun `shelf lookup error survives card propagation`() {
        val card = ShelfItem(
            title = "Sanam Teri Kasam",
            subtitle = "Shaan",
            thumbnailUrl = null,
            videoId = "spotify:track",
            browseId = null,
            appleLookupError = "Apple Music catalogue not found",
        )

        assertEquals("Apple Music catalogue not found", card.copy().appleLookupError)
    }

    @Test
    fun `unresolved Apple playback uses a strict generic watch URI`() {
        val uri = song().strictAppleWatchUri()

        assertTrue(uri.startsWith("bitchord://watch?"))
        assertTrue(uri.contains("appleStrict=1"))
    }

    @Test
    fun `Apple mode keeps unresolved discovery lyrics strict`() {
        val previousProvider = AppSettings.homeProvider.value
        try {
            AppSettings.homeProvider.value = HomeProvider.APPLE

            assertTrue(song().requiresStrictAppleIdentity())
            assertTrue(song(appleId = "123").requiresStrictAppleIdentity())
        } finally {
            AppSettings.homeProvider.value = previousProvider
        }
    }

    @Test
    fun `master switch controls Apple strict routing and catalogue selection`() {
        val previousConfigs = SourceRegistry.configs.value
        val previousProvider = AppSettings.homeProvider.value
        try {
            val apple = SourceConfig(
                id = "apple-config",
                kind = SourceKind.ADDON,
                baseUrl = "https://apple.invalid",
                enabled = true,
                catalogRole = SourceCatalogRole.APPLE_MUSIC,
            )
            SourceRegistry.configs.value = listOf(apple)

            AppSettings.homeProvider.value = HomeProvider.YOUTUBE
            assertFalse(SourceRegistry.isAppleStrictRouting(apple.id))
            assertNull(SourceRegistry.appleCatalogConfig())

            AppSettings.homeProvider.value = HomeProvider.APPLE
            assertTrue(SourceRegistry.isAppleStrictRouting(apple.id))
            assertEquals(apple.id, SourceRegistry.appleCatalogConfig()?.id)
        } finally {
            AppSettings.homeProvider.value = previousProvider
            SourceRegistry.configs.value = previousConfigs
        }
    }
}
