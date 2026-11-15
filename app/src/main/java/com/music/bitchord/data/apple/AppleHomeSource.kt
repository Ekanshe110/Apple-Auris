package com.music.bitchord.data.apple

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonHomeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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

    /**
     * Apple Music's actual Home shelves, grouped the way Apple returned them.
     *
     * Albums and playlists are kept as collection cards; songs are playable
     * tracks. The caller decides how to render each row.
     */
    suspend fun shelves(limit: Int = 20): Result<List<HomeShelf>> = withContext(Dispatchers.IO) {
        val config = SourceRegistry.appleRecommendationConfig()
            ?: return@withContext Result.failure(IllegalStateException("Apple Music is not configured"))
        val source = SourceRegistry.instance(config.id) as? AddonSource
            ?: return@withContext Result.failure(IllegalStateException("Apple Music addon is unavailable"))
        source.homeShelvesResult(limit).map { list ->
            list.map { shelf ->
                HomeShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle,
                    items = shelf.items.map { it.toShelfItem() },
                )
            }
        }
    }

    internal fun Song.toShelfItem() = ShelfItem(
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

    internal fun AddonHomeItem.toShelfItem(): ShelfItem {
        val isSong = type.equals("song", ignoreCase = true)
        val browseId = when {
            isSong -> null
            type.equals("album", ignoreCase = true) -> "apple:album:$id"
            type.equals("playlist", ignoreCase = true) -> "apple:playlist:$id"
            else -> null
        }
        return ShelfItem(
            title = title,
            subtitle = artist,
            thumbnailUrl = artworkURL,
            videoId = if (isSong) id else null,
            browseId = browseId,
            appleCatalogId = if (isSong) id else null,
            durationText = duration?.let {
                val sec = it.toInt()
                "${sec / 60}:${"%02d".format(Locale.ROOT, sec % 60)}"
            },
            isExplicit = explicit,
            contentRating = contentRating,
            isrc = isrc,
        )
    }
}
