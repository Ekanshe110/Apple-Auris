package com.music.bitchord.data.apple

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry

/**
 * Writes Apple Music library state through the configured addon.
 *
 * Ratings and playlist writes both need the active Apple Music source and the
 * song's exact Apple catalog id. The app keeps no Apple credentials itself.
 */
object AppleLikeRepository {

    /** Set or clear the Apple "love" rating for [song]. */
    suspend fun setLiked(
        config: SourceConfig,
        song: Song,
        liked: Boolean,
    ): Result<Unit> {
        val appleId = song.appleCatalogId?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("Song has no Apple catalog id"))
        val client = AppleLibraryStore.repository(config).clientOrNull()
            ?: return Result.failure(IllegalStateException("Apple Music source is not available"))
        return client.libraryRate(appleId, liked)
    }

    /** Add [song] to an Apple Music library playlist. */
    suspend fun addToPlaylist(
        config: SourceConfig,
        playlistId: String,
        song: Song,
    ): Result<Unit> {
        val appleId = song.appleCatalogId?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("Song has no Apple catalog id"))
        val client = AppleLibraryStore.repository(config).clientOrNull()
            ?: return Result.failure(IllegalStateException("Apple Music source is not available"))
        return client.libraryAddToPlaylist(playlistId, appleId)
    }

    /** Remove [song] from one Apple Music library playlist. */
    suspend fun removeFromPlaylist(
        config: SourceConfig,
        playlistId: String,
        song: Song,
    ): Result<Unit> {
        val appleId = song.appleCatalogId?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("Song has no Apple catalog id"))
        val client = AppleLibraryStore.repository(config).clientOrNull()
            ?: return Result.failure(IllegalStateException("Apple Music source is not available"))
        return client.libraryRemoveFromPlaylist(playlistId, appleId)
    }

    /** Remove [song] from the Favourite/Favorite Songs playlist. */
    suspend fun removeFromFavorites(
        config: SourceConfig,
        song: Song,
    ): Result<Unit> {
        val appleId = song.appleCatalogId?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("Song has no Apple catalog id"))
        val client = AppleLibraryStore.repository(config).clientOrNull()
            ?: return Result.failure(IllegalStateException("Apple Music source is not available"))
        return client.libraryRemoveFromFavorites(appleId)
    }

    /** The currently configured Apple Music source, if any. */
    fun activeConfig(): SourceConfig? = SourceRegistry.appleCatalogConfig()
}
