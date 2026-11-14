package com.music.bitchord.data.apple

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apple Music's personalised recommendation feed.
 *
 * Read through the configured Apple addon so the account token stays on the
 * server. Every row already carries Apple's exact catalogue id; no title
 * matching or ExoPlayer-thread lookup is involved.
 */
internal object AppleHomeSource {

    /** Rows requested from one feed read. */
    const val DEFAULT_LIMIT = 40

    /** One feed read, preserving exact Apple ids and recording metadata. */
    suspend fun tracks(limit: Int = DEFAULT_LIMIT): Result<List<Song>> = withContext(Dispatchers.IO) {
        val config = SourceRegistry.appleRecommendationConfig()
            ?: return@withContext Result.failure(IllegalStateException("Apple Music is not configured"))
        val source = SourceRegistry.instance(config.id) as? AddonSource
            ?: return@withContext Result.failure(IllegalStateException("Apple Music addon is unavailable"))
        source.recommendationsResult(limit)
    }

    /** The recommendation feed as one Home shelf. */
    suspend fun shelf(limit: Int = DEFAULT_LIMIT): Result<HomeShelf> =
        tracks(limit).map { songs ->
            HomeShelf(
                title = "Apple Music",
                subtitle = "Made for you",
                items = songs.map { it.toShelfItem() },
            )
        }

    private fun Song.toShelfItem() = ShelfItem(
        title = title,
        subtitle = artist,
        thumbnailUrl = thumbnailUrl,
        videoId = videoId,
        browseId = null,
        appleCatalogId = appleCatalogId,
        appleLookupError = appleLookupError,
        durationText = durationText,
        isExplicit = isExplicit,
        contentRating = contentRating,
        isrc = isrc,
    )
}
