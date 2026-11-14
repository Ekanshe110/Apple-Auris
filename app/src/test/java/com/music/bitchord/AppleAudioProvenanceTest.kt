package com.music.bitchord

import com.music.bitchord.download.AudioIdentity
import com.music.bitchord.download.audioIdentityMatches
import com.music.bitchord.download.exactAppleDownloadIdentity
import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apple cache/download identity and stale-recording admission rules. */
class AppleAudioProvenanceTest {

    @Test
    fun `Apple cache key uses source and catalogue id, not original video id`() {
        val key = AudioCache.appleCacheKey("apple-config", "catalog-183")

        assertEquals("apple|apple-config|catalog-183", key)
        assertNotEquals("youtube-72", key)
    }

    @Test
    fun `stale 72 second cache is not selected for 183 second Apple track`() {
        val descriptor = AudioCache.AppleStreamDescriptor(
            sourceId = "apple-config",
            appleCatalogId = "catalog-183",
            originalVideoId = "youtube-72",
            durationMs = 183_000L,
            byteLength = 4_000_000L,
        )

        assertFalse(
            AudioCache.isAppleCacheUsable(
                cachedDurationMs = 72_000L,
                cachedByteLength = 4_000_000L,
                descriptor = descriptor,
            ),
        )
    }

    @Test
    fun `matching Apple duration and byte length is admitted`() {
        val descriptor = AudioCache.AppleStreamDescriptor(
            sourceId = "apple-config",
            appleCatalogId = "catalog-183",
            originalVideoId = "youtube-183",
            durationMs = 183_000L,
            byteLength = 4_000_000L,
        )

        assertTrue(
            AudioCache.isAppleCacheUsable(
                cachedDurationMs = 183_000L,
                cachedByteLength = 4_000_000L,
                descriptor = descriptor,
            ),
        )
    }

    @Test
    fun `enriched discovery download uses exact catalogue id and current Apple source`() {
        val identity = exactAppleDownloadIdentity(
            originalVideoId = "spotify-row",
            sourceId = null,
            sourceStreamId = null,
            sourceIsApple = false,
            appleCatalogId = "catalog-183",
            currentAppleSourceId = "apple-config",
        )

        assertEquals("apple-config", identity?.sourceId)
        assertEquals("catalog-183", identity?.streamId)
        assertEquals("catalog-183", identity?.appleCatalogId)
    }

    @Test
    fun `source-backed Apple download keeps pinned source and stream id`() {
        val identity = exactAppleDownloadIdentity(
            originalVideoId = "src:apple-config::stream-456",
            sourceId = "apple-config",
            sourceStreamId = "stream-456",
            sourceIsApple = true,
            appleCatalogId = "catalog-183",
            currentAppleSourceId = "other-apple-config",
        )

        assertEquals("apple-config", identity?.sourceId)
        assertEquals("stream-456", identity?.streamId)
        assertEquals("catalog-183", identity?.appleCatalogId)
    }

    @Test
    fun `missing exact Apple catalogue identity is refused`() {
        assertNull(
            exactAppleDownloadIdentity(
                originalVideoId = "spotify-row",
                sourceId = null,
                sourceStreamId = null,
                sourceIsApple = false,
                appleCatalogId = null,
                currentAppleSourceId = "apple-config",
            ),
        )
    }

    @Test
    fun `legacy download without Apple provenance cannot enter Apple playback`() {
        val expected = AudioIdentity(
            provider = "Apple Music",
            sourceId = "apple-config",
            originalVideoId = "spotify-row",
            appleCatalogId = "catalog-183",
            durationMs = 183_000L,
        )
        val legacy = AudioIdentity(
            provider = "YouTube",
            sourceId = null,
            originalVideoId = null,
            appleCatalogId = null,
            durationMs = 72_000L,
        )

        assertFalse(audioIdentityMatches(expected, legacy))
    }
}
