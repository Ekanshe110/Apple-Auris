package com.music.bitchord.data.apple

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonAuthenticationException
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import com.music.bitchord.data.sources.addon.AddonUnsupportedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** The account-library collections exposed by the Apple addon contract. */
enum class AppleLibraryCollection(val routeName: String, val label: String) {
    PLAYLISTS("playlists", "Playlists"),
    ALBUMS("albums", "Albums"),
    SONGS("songs", "Songs"),
}

/** The state of one Apple library page. These outcomes are deliberately distinct. */
sealed interface AppleLibraryState {
    data object Unconfigured : AppleLibraryState
    data object Loading : AppleLibraryState
    data object SignedOut : AppleLibraryState
    data object Empty : AppleLibraryState
    data class Failure(val message: String, val retryable: Boolean = true) : AppleLibraryState
    data class Ready(
        val items: List<AddonLibraryItem>,
        val hasMore: Boolean = false,
        val nextOffset: Int? = null,
    ) : AppleLibraryState
}

/**
 * The addon-backed Apple Music library.
 *
 * No Apple credentials are read here. The addon owns the Apple account; the
 * app sends only the addon-scoped credential named by its manifest.
 */
class AppleLibraryRepository(private val config: SourceConfig) {

    /**
     * The one client for this repository's config.
     *
     * The manifest is needed to learn which setting holds the credential. The
     * same client is reused for every collection, track page, and load-more
     * request; its current manifest names the credential setting on each
     * request, so a server-side setting rename cannot leave the old account
     * bound.
     */
    private val clientLock = Mutex()
    private var client: AddonClient? = null

    suspend fun load(
        collection: AppleLibraryCollection,
        offset: Int = 0,
    ): AppleLibraryState {
        val client = client().getOrElse { return it.state() }
        val page = when (collection) {
            AppleLibraryCollection.PLAYLISTS -> client.libraryPlaylists(offset)
            AppleLibraryCollection.ALBUMS -> client.libraryAlbums(offset)
            AppleLibraryCollection.SONGS -> client.librarySongs(offset)
        }.getOrElse { return it.state() }
        return page.state(offset)
    }

    suspend fun tracks(
        collection: AppleLibraryCollection,
        collectionId: String,
        offset: Int = 0,
    ): AppleLibraryState {
        val client = client().getOrElse { return it.state() }
        val page = when (collection) {
            AppleLibraryCollection.PLAYLISTS -> client.libraryPlaylistTracks(collectionId, offset)
            AppleLibraryCollection.ALBUMS -> client.libraryAlbumTracks(collectionId, offset)
            AppleLibraryCollection.SONGS -> client.librarySongTracks(collectionId, offset)
        }.getOrElse { return it.state() }
        return page.state(offset)
    }

    private suspend fun client(): Result<AddonClient> = clientLock.withLock {
        try {
            val candidate = client ?: AddonClient(config.baseUrl).also { created ->
                // Prime the manifest before the credential can be bound: the
                // manifest names the setting which holds it.
                created.manifest().getOrThrow()
            }
            val manifest = candidate.manifest().getOrThrow()
            val capability = manifest.library
                ?: throw LibraryUnconfiguredException()
            if (!manifest.isAppleMusicCatalog) throw LibraryUnconfiguredException()
            if (!capability.isAppleMusic) throw LibraryUnconfiguredException()
            // Re-read the setting from the current manifest on every library
            // request. A later manifest may name a different setting, and a
            // client permanently bound to the first one would keep sending the
            // old account's credential.
            val setting = capability.authentication?.setting
                ?.takeIf { it.isNotBlank() }
                ?: "libraryToken"
            candidate.bindLibraryCredential(config.settings[setting])
            client = candidate
            Result.success(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    private fun Throwable.state(): AppleLibraryState = when (this) {
        is LibraryUnconfiguredException -> AppleLibraryState.Unconfigured
        is AddonAuthenticationException -> when (code) {
            null, "signed_out", "token_expired", "missing_credential" -> AppleLibraryState.SignedOut
            else -> AppleLibraryState.Failure(message ?: "Apple Music sign-in failed", retryable = false)
        }
        is AddonUnsupportedException -> AppleLibraryState.Failure(
            message ?: "This addon does not provide an Apple Music library",
            retryable = false,
        )
        else -> AppleLibraryState.Failure(message ?: "Apple Music library is unavailable")
    }
}

private class LibraryUnconfiguredException : Exception("Apple Music library capability is not configured")

private fun com.music.bitchord.data.sources.addon.AddonLibraryPage.state(
    requestedOffset: Int,
): AppleLibraryState {
    if (items.isEmpty()) return AppleLibraryState.Empty
    val next = nextOffset
    val hasNext = hasMore ?: (
        next != null || total?.let { requestedOffset + items.size < it } == true
    )
    return AppleLibraryState.Ready(
        items = items,
        hasMore = hasNext,
        nextOffset = next ?: (requestedOffset + items.size).takeIf { hasNext },
    )
}

/** The playable row for a library track, preserving its exact addon stream id. */
fun AddonLibraryItem.toSong(configId: String): Song? {
    val streamId = streamId.takeIf { it.isNotBlank() } ?: return null
    return Song(
        videoId = SourceRegistry.trackKey(configId, streamId),
        title = title.ifBlank { "Untitled" },
        artist = artist,
        albumName = album.ifBlank { null },
        thumbnailUrl = artwork,
        durationText = durationSec?.let {
            "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
        },
        appleCatalogId = catalogId.trim().takeIf { it.isNotEmpty() },
        isExplicit = explicit ?: when (contentRating?.lowercase(Locale.ROOT)) {
            "explicit" -> true
            "clean" -> false
            else -> null
        },
        contentRating = contentRating?.lowercase(Locale.ROOT),
        isrc = isrc?.trim()?.takeIf { it.isNotEmpty() },
    )
}
