package com.music.bitchord.data.apple

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import java.util.Locale

/**
 * Public Apple Music catalog pages (albums, playlists) reached through the
 * configured addon. The app keeps no Apple developer token itself.
 */
object AppleCatalogRepository {

    /** Tracks of a public Apple Music album or playlist. */
    suspend fun tracks(
        config: SourceConfig,
        collection: String,
        id: String,
        limit: Int = 100,
    ): Result<List<Song>> {
        if (collection !in setOf("albums", "playlists", "artists")) {
            return Result.failure(
                IllegalArgumentException("collection must be albums, playlists or artists"),
            )
        }
        val client = AppleLibraryStore.repository(config).clientOrNull()
            ?: return Result.failure(IllegalStateException("Apple Music source is not available"))
        return client.catalogTracks(collection, id, limit).map { list ->
            list.filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .map { track ->
                    val sec = track.durationSec
                    Song(
                        videoId = SourceRegistry.trackKey(config.id, track.id),
                        title = track.title,
                        artist = track.artist,
                        albumName = track.album.ifBlank { null },
                        thumbnailUrl = track.artwork,
                        durationText = sec?.let {
                            "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
                        },
                        isExplicit = track.explicit ?: when (track.contentRating?.lowercase(Locale.ROOT)) {
                            "explicit" -> true
                            "clean" -> false
                            else -> null
                        },
                        contentRating = track.contentRating?.lowercase(Locale.ROOT),
                        isrc = track.isrc?.trim()?.takeIf { it.isNotEmpty() },
                        atmosAvailable = track.atmos == true,
                        appleCatalogId = track.id,
                    )
                }
        }
    }

    /** The currently configured Apple Music source, if any. */
    fun activeConfig(): SourceConfig? = AppleLikeRepository.activeConfig()
}
