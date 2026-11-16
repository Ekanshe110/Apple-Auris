package com.music.bitchord.data.apple

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.ModuleSource
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonStation
import com.music.bitchord.data.sources.addon.AddonTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Apple Music's own stations.
 *
 * A station is Apple's rolling selection of songs: the account's personal
 * station, and the stations Apple builds for catalogue songs. The tracks of a
 * station are ordinary catalogue songs, so they play through exactly the same
 * path as a searched song — same FairPlay template, same decryption, same
 * cache — and every row keeps Apple's exact catalogue id.
 *
 * The addon owns the Apple token, so the station list and its tracks are read
 * through the configured Apple addon and never from the device.
 */
internal object AppleRadio {

    /** Tracks asked for in one station read. */
    const val DEFAULT_LIMIT = 20

    /**
     * The stations this account can start.
     *
     * An addon without radio answers 404, so this fails rather than pretending
     * the account has no stations.
     */
    suspend fun stations(): Result<List<AddonStation>> = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext Result.failure(
            IllegalStateException("Apple Music is not configured"),
        )
        client.radioStations()
    }

    /**
     * The tracks one station would play next, as playable songs.
     */
    suspend fun tracks(
        stationId: String,
        limit: Int = DEFAULT_LIMIT,
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        if (stationId.isBlank()) return@withContext Result.success(emptyList())
        val config = config() ?: return@withContext Result.failure(
            IllegalStateException("Apple Music is not configured"),
        )
        val client = client() ?: return@withContext Result.failure(
            IllegalStateException("Apple Music addon is unavailable"),
        )
        client.radioStationTracks(stationId, limit).map { rows ->
            rows.asSequence()
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .map { it.toStationSong(config.id) }
                .toList()
        }
    }

    /**
     * The station Apple built for one song, when it has one.
     */
    suspend fun stationForSong(songId: String): Result<AddonStation?> =
        withContext(Dispatchers.IO) {
            if (songId.isBlank()) return@withContext Result.success(null)
            val client = client() ?: return@withContext Result.failure(
                IllegalStateException("Apple Music addon is unavailable"),
            )
            client.radioStationForSong(songId)
        }

    /**
     * One station row as the Radio tab's own station tile.
     *
     * The id is kept in the browse id so the tile can be told apart from a
     * catalogue playlist, and so the station can be asked for by name later.
     */
    fun tile(station: AddonStation) = AppleStationTile(
        browseId = "$BROWSE_PREFIX${station.id}",
        title = station.name.ifBlank { "Radio" },
        subtitle = station.subtitle.orEmpty(),
        thumbnailUrl = station.artworkURL?.ifBlank { null },
        stationId = station.id,
    )

    /** The browse-id prefix that marks a tile as an Apple station. */
    const val BROWSE_PREFIX = "apple-radio:"

    private fun config() = SourceRegistry.appleRecommendationConfig()

    private fun client() = config()?.let { config ->
        SourceRegistry.instance(config.id) as? AddonSource
    }?.client

    /**
     * One station row as a playable song.
     *
     * The same shape a searched Apple row has: the source-keyed video id the
     * player resolves through `/stream`, and Apple's own catalogue id kept
     * beside it.
     */
    internal fun AddonTrack.toStationSong(configId: String): Song = Song(
        videoId = SourceRegistry.trackKey(configId, id),
        title = title,
        artist = artist,
        albumName = album.ifBlank { null },
        thumbnailUrl = artwork,
        durationText = durationSec?.let {
            "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
        },
        isExplicit = explicit ?: when (contentRating?.lowercase(Locale.ROOT)) {
            "explicit" -> true
            "clean" -> false
            else -> null
        },
        contentRating = contentRating?.lowercase(Locale.ROOT),
        isrc = isrc?.trim()?.takeIf { it.isNotEmpty() },
        sourceQuality = if (isDolbyAtmos) {
            ModuleSource.DOLBY
        } else {
            ModuleSource.qualityTier("$audioQuality $format")
        },
        atmosAvailable = atmos == true,
        appleCatalogId = id,
    )
}

/** One Apple station, as the Radio tab shows it. */
internal data class AppleStationTile(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val stationId: String,
)
