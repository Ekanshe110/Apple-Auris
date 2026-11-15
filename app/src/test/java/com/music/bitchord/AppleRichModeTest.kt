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
import com.music.bitchord.data.sources.addon.AddonLibraryCapability
import com.music.bitchord.data.sources.addon.AddonManifest
import com.music.bitchord.data.sources.catalogRoleFromManifest
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
        isExplicit: Boolean? = null,
        contentRating: String? = null,
        isrc: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        appleCatalogId = appleId,
        appleLookupError = lookupError,
        isExplicit = isExplicit,
        contentRating = contentRating,
        isrc = isrc,
    )

    @Test
    fun `Apple identity ignores punctuation and case`() {
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
    fun `near artist is not an Apple identity`() {
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
    fun `title only Apple row is rejected`() {
        val wanted = song()
        val candidate = song(
            id = "src:apple::123",
            title = wanted.title,
            artist = "Someone Else",
            appleId = "123",
        )

        assertNull(AppleEnrichment.exactAppleId(wanted, listOf(candidate)))
    }

    @Test
    fun `duplicate Apple editions choose the stable id`() {
        val wanted = song()
        val candidates = listOf(
            song(id = "src:apple::456", appleId = "456"),
            song(id = "src:apple::123", appleId = "123"),
        )

        assertEquals("123", AppleEnrichment.exactAppleId(wanted, candidates))
    }

    @Test
    fun `rating whitespace does not weaken explicit matching`() {
        val wanted = song(isExplicit = true)
        val clean = song(
            id = "src:apple::123",
            appleId = "123",
            contentRating = " clean ",
        )
        val explicit = song(
            id = "src:apple::456",
            appleId = "456",
            contentRating = " Explicit ",
        )

        assertEquals("456", AppleEnrichment.exactAppleId(wanted, listOf(clean, explicit)))
    }

    @Test
    fun `different durations outside tolerance are rejected`() {
        val wanted = song(duration = "4:00")
        val candidates = listOf(
            song(id = "src:apple::123", duration = "3:30", appleId = "123"),
            song(id = "src:apple::456", duration = "5:00", appleId = "456"),
        )

        assertNull(AppleEnrichment.exactAppleId(wanted, candidates))
    }

    @Test
    fun `Saiyaara artist overlap accepts either Apple edition`() {
        val wanted = song(
            title = "Saiyaara Reprise - Female",
            artist = "Shreya Ghoshal, Irshad Kamil",
            duration = "3:03",
        )
        val artist = "Tanishk Bagchi, Faheem Abdullah, Arslan Nizami, Shreya Ghoshal, Irshad Kamil"
        val first = song(
            id = "src:apple::1824400214",
            title = wanted.title,
            artist = artist,
            duration = "3:03",
            appleId = "1824400214",
        )
        val second = song(
            id = "src:apple::1838877945",
            title = wanted.title,
            artist = artist,
            duration = "3:03",
            appleId = "1838877945",
        )

        assertEquals("1824400214", AppleEnrichment.exactAppleId(wanted, listOf(first)))
        assertEquals("1838877945", AppleEnrichment.exactAppleId(wanted, listOf(second)))
        assertEquals("1824400214", AppleEnrichment.exactAppleId(wanted, listOf(second, first)))
    }

    @Test
    fun `Makhna matches Apple after title packaging is removed`() {
        val wanted = song(
            title = "Makhna",
            artist = "Tanishk Bagchi, Yasser Desai, Asees Kaur",
            duration = "3:03",
        )
        val candidate = song(
            id = "src:apple::1529555769",
            title = "Makhna (From \"Drive\")",
            artist = "Tanishk Bagchi, Yasser Desai & Asees Kaur",
            duration = "3:03",
            appleId = "1529555769",
        )

        assertEquals("1529555769", AppleEnrichment.exactAppleId(wanted, listOf(candidate)))
    }

    @Test
    fun `Ud-daa Punjab matches Apple despite dash spacing and Topic artist`() {
        val wanted = song(
            title = "Ud-daa Punjab",
            artist = "Vishal Dadlani - Topic",
            duration = "4:35",
        )
        val candidate = song(
            id = "src:apple::1529447366",
            title = "Ud - Daa Punjab",
            artist = "Vishal Dadlani & Amit Trivedi",
            duration = "4:34",
            appleId = "1529447366",
        )

        assertEquals("1529447366", AppleEnrichment.exactAppleId(wanted, listOf(candidate)))
    }

    @Test
    fun `unknown candidate duration is rejected when wanted duration is known`() {
        val wanted = song(duration = "4:00")
        val candidate = song(
            id = "src:apple::123",
            duration = null,
            appleId = "123",
        )

        assertNull(AppleEnrichment.exactAppleId(wanted, listOf(candidate)))
    }

    @Test
    fun `fuller artist overlap wins before title or id`() {
        val wanted = song(
            title = "Saiyaara Reprise - Female",
            artist = "Shreya Ghoshal, Irshad Kamil",
            duration = "3:03",
        )
        val full = song(
            id = "src:apple::999",
            title = wanted.title,
            artist = "Shreya Ghoshal, Irshad Kamil, Tanishk Bagchi",
            duration = "3:03",
            appleId = "999",
        )
        val partial = song(
            id = "src:apple::111",
            title = wanted.title,
            artist = "Shreya Ghoshal, Irshad Kumar",
            duration = "3:03",
            appleId = "111",
        )

        assertEquals("999", AppleEnrichment.exactAppleId(wanted, listOf(partial, full)))
    }

    @Test
    fun `exact title spelling wins before Apple id`() {
        val wanted = song(title = "Makhna", artist = "Asees Kaur", duration = "3:03")
        val exact = song(
            id = "src:apple::999",
            title = "Makhna",
            artist = wanted.artist,
            duration = "3:03",
            appleId = "999",
        )
        val packaged = song(
            id = "src:apple::111",
            title = "Makhna (From \"Drive\")",
            artist = wanted.artist,
            duration = "3:03",
            appleId = "111",
        )

        assertEquals("999", AppleEnrichment.exactAppleId(wanted, listOf(packaged, exact)))
    }

    @Test
    fun `explicit request selects the explicit master over a clean edition`() {
        val wanted = song(isExplicit = true, contentRating = "explicit")
        val clean = song(
            id = "src:apple::clean",
            appleId = "clean",
            isExplicit = false,
            contentRating = "clean",
        )
        val explicit = song(
            id = "src:apple::explicit",
            appleId = "explicit",
            isExplicit = true,
            contentRating = "explicit",
        )

        assertEquals("explicit", AppleEnrichment.exactAppleId(wanted, listOf(clean, explicit)))
    }

    @Test
    fun `explicit request never substitutes a clean master`() {
        val wanted = song(isExplicit = true, contentRating = "explicit")
        val clean = song(
            id = "src:apple::clean",
            appleId = "clean",
            isExplicit = false,
            contentRating = "clean",
        )

        assertNull(AppleEnrichment.exactAppleId(wanted, listOf(clean)))
    }

    @Test
    fun `remix and live editions do not match the plain recording`() {
        val wanted = song(title = "Test Song", artist = "Test Artist")
        val remix = song(
            id = "src:apple::remix",
            title = "Test Song (Remix)",
            appleId = "remix",
        )
        val live = song(
            id = "src:apple::live",
            title = "Test Song (Live)",
            appleId = "live",
        )

        assertNull(AppleEnrichment.exactAppleId(wanted, listOf(remix, live)))
    }

    @Test
    fun `exact Apple match carries explicit rating and ISRC`() {
        val wanted = song(isExplicit = true, contentRating = "explicit")
        val candidate = song(
            id = "src:apple::123",
            appleId = "123",
            isExplicit = true,
            contentRating = "explicit",
            isrc = "GBMA22047592",
        )

        val match = AppleEnrichment.exactAppleMatch(wanted, listOf(candidate))
        assertEquals("123", match?.id)
        assertEquals(true, match?.isExplicit)
        assertEquals("explicit", match?.contentRating)
        assertEquals("GBMA22047592", match?.isrc)
    }

    @Test
    fun `transient lookup failure remains retryable`() {
        assertFalse(
            AppleEnrichment.shouldCacheLookup(
                Result.failure<String?>(IllegalStateException("offline")),
            ),
        )
        assertTrue(AppleEnrichment.shouldCacheLookup(Result.success(null)))
        assertTrue(AppleEnrichment.shouldCacheLookup(Result.success("123")))
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
    fun `manifest without catalogue provider preserves Apple role`() {
        val manifest = AddonManifest()

        assertEquals(
            SourceCatalogRole.APPLE_MUSIC,
            catalogRoleFromManifest(manifest, SourceCatalogRole.APPLE_MUSIC),
        )
    }

    @Test
    fun `explicit non-Apple catalogue provider becomes generic`() {
        val manifest = AddonManifest(catalogueProvider = "spotify")

        assertEquals(
            SourceCatalogRole.GENERIC,
            catalogRoleFromManifest(manifest, SourceCatalogRole.APPLE_MUSIC),
        )
    }

    @Test
    fun `catalogue identity does not depend on optional library`() {
        val appleWithoutLibrary = AddonManifest(catalogueProvider = "apple-music")
        val appleLibraryOnly = AddonManifest(
            library = AddonLibraryCapability(provider = "apple-music"),
        )

        assertEquals(
            SourceCatalogRole.APPLE_MUSIC,
            catalogRoleFromManifest(appleWithoutLibrary, SourceCatalogRole.GENERIC),
        )
        assertEquals(
            SourceCatalogRole.GENERIC,
            catalogRoleFromManifest(appleLibraryOnly, SourceCatalogRole.GENERIC),
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
