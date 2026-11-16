package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.apple.AppleEnrichment
import com.music.bitchord.data.apple.AppleHomeSource
import com.music.bitchord.data.apple.AppleRadio
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.RecommendationAlgorithm
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Cached Apple recommendation pool per seed, so tapping the 5th/8th AutoPlay
 * row does not re-fetch the same upstream answer. The pool is refreshed after
 * a few minutes or when it runs dry.
 */
private data class AppleAutoplayCache(
    val songs: List<Song>,
    val fetchedAt: Long,
)

private val appleAutoplayCache = mutableMapOf<String, AppleAutoplayCache>()
private const val APPLE_AUTOCACHE_TTL_MS = 5 * 60 * 1000L

internal fun clearAppleAutoplayCache() {
    appleAutoplayCache.clear()
}

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * In a party it is the party's setting and not this phone's: AutoPlay is shared
 * there, so one member's preference cannot be what decides. [localPreference] is
 * `AppSettings.autoplay` and applies everywhere else.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * because they went their own ways once and the result was a button that lied:
 * the player read the local preference while the service obeyed the party's, so
 * a listener could sit under "AutoPlay on" getting no suggestions at all, press
 * it, and watch the label stay exactly as it was.
 */
fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

/**
 * The one device in a party that tops the queue up, or null if there is none.
 *
 * Everybody runs this over the same membership and gets the same answer, which
 * is what stops five phones appending five different sets of suggestions to one
 * queue. The host supplies while it is connected; otherwise the lowest member id
 * takes over, chosen for being stable rather than for meaning anything.
 *
 * Except in a party locked to its host, where there is no stand-in. The server
 * refuses a `queueAdd` from a listener outright — `host_only`, whatever the
 * listener believes it is doing — so electing one produced a control that was
 * always refused, an error on that listener's phone and suggestions for nobody.
 * Returning null instead lets AutoPlay wait for the host, which is what the
 * party's own policy already says should happen.
 */
fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * Finds the YouTube id that should seed AutoPlay for a song. Source-backed
 * tracks do not carry YouTube ids, so they are matched on YouTube before the
 * radio request.
 */
suspend fun youtubeSeedFor(song: Song): String? = resolveYoutubeSeed(song).getOrNull()

/**
 * Resolves [song] to the YouTube id accepted by the radio endpoint.
 *
 * A source-backed id is not a YouTube id. It is resolved from the row's own
 * recording metadata first; the radio is asked only for a real YouTube seed.
 * Search failures remain failures so AutoPlay can report why no source
 * answered instead of mistaking the error for an empty station.
 */
internal suspend fun resolveYoutubeSeed(song: Song): Result<String?> {
    // A normal YouTube id can seed YouTube radio directly. A Spotify id
    // cannot: it names a Spotify recording and YouTube's radio endpoint
    // would receive an id it does not know.
    if (SourceRegistry.parseTrackKey(song.videoId) == null &&
        !song.videoId.startsWith("spotify:")
    ) {
        return Result.success(song.videoId)
    }

    val target = TrackMatcher.targetOf(song)
    val queries = TrackMatcher.queries(target)
    if (queries.isEmpty()) return Result.success(null)

    var searchFailure: Throwable? = null
    for (query in queries) {
        captureSourceFailure {
            YtMusicRepository.search(query, SearchFilter.SONGS)
        }.fold(
            onSuccess = { rows ->
                val matched = rows
                    .mapNotNull { row ->
                        when (row) {
                            is SearchResult.TopTrack -> row.song
                            is SearchResult.Track -> row.song
                            is SearchResult.Browse -> null
                        }
                    }
                    .let { TrackMatcher.best(it, target) }
                if (matched != null) return Result.success(matched.videoId)
            },
            onFailure = { failure ->
                if (searchFailure == null) searchFailure = failure
            },
        )
    }
    return searchFailure?.let { Result.failure(it) } ?: Result.success(null)
}

/** YouTube radio rows for [song], after resolving a source-backed seed. */
internal suspend fun youtubeRadioCandidates(song: Song): Result<List<Song>> {
    val seed = resolveYoutubeSeed(song).getOrElse { return Result.failure(it) }
        ?: return Result.success(emptyList())
    return captureSourceFailure { YtMusicRepository.radio(seed) }
}

/**
 * Interleaves recommendation feeds so no feed can consume the whole answer.
 *
 * The groups are read in turns. In All mode this keeps Spotify, YouTube and
 * Apple present even when the first feed is much longer than the others.
 */
internal fun interleaveRecommendationCandidates(
    groups: List<List<Song>>,
    limit: Int,
): List<Song> {
    if (limit <= 0) return emptyList()
    return buildList {
        var index = 0
        while (size < limit && groups.any { it.size > index }) {
            groups.forEach { group ->
                if (size < limit) group.getOrNull(index)?.let(::add)
            }
            index++
        }
    }
}

/** How many Apple rows to request before selecting the small visible answer. */
internal fun appleRecommendationPoolSize(limit: Int): Int = when {
    limit <= 0 -> 0
    else -> maxOf(limit * APPLE_RECOMMENDATION_POOL_MULTIPLIER, APPLE_RECOMMENDATION_POOL_MIN)
}

/**
 * Apple candidates for AutoPlay, cached per seed so repeated top-ups do not
 * re-hit the server for the same pool.
 */
private suspend fun appleAutoplayPool(seed: Song, limit: Int): Result<List<Song>> {
    val seedId = seed.appleCatalogId?.takeIf { it.isNotBlank() }
        ?: return Result.success(emptyList())
    val seedKey = seed.videoId
    val now = System.currentTimeMillis()
    val cached = appleAutoplayCache[seedKey]
    if (cached != null && now - cached.fetchedAt < APPLE_AUTOCACHE_TTL_MS && cached.songs.isNotEmpty()) {
        return Result.success(cached.songs)
    }
    return captureSourceFailure {
        AppleHomeSource.autoplay(seedId, appleRecommendationPoolSize(limit))
    }.onSuccess { tracks ->
        appleAutoplayCache[seedKey] = AppleAutoplayCache(tracks, now)
    }
}

/**
 * The tracks the station itself would play next.
 *
 * A station is Apple's own rolling selection, so this is the true answer for
 * a queue that belongs to one. Only the station's own rows are asked for: the
 * catalogue-neighbour feed is a different thing and is kept as the fallback.
 */
private suspend fun appleStationPool(stationId: String, limit: Int): Result<List<Song>> =
    captureSourceFailure {
        AppleRadio.tracks(stationId, appleRecommendationPoolSize(limit))
    }

/**
 * Selects unseen Apple rows from a larger feed pool.
 *
 * Apple's recommendation feed can repeat the same head rows on every request.
 * Existing exact Apple catalogue ids are excluded, and the pool is rotated by
 * the number already consumed before the unseen rows are selected. The whole
 * pool remains available after rotation, so excluding an old id never makes
 * later unseen rows disappear.
 */
internal fun selectUnseenAppleCandidates(
    pool: List<Song>,
    existing: Collection<Song>,
    limit: Int,
): List<Song> {
    if (limit <= 0 || pool.isEmpty()) return emptyList()
    val existingAppleIds = existing.mapNotNullTo(HashSet<String>()) {
        it.appleCatalogId?.takeIf(String::isNotBlank)
    }
    val uniquePool = pool.distinctBy { song ->
        song.appleCatalogId?.takeIf(String::isNotBlank) ?: "video:${song.videoId}"
    }
    if (uniquePool.isEmpty()) return emptyList()

    val offset = existingAppleIds.size % uniquePool.size
    val rotated = uniquePool.drop(offset) + uniquePool.take(offset)
    return rotated
        .filterNot { song -> song.appleCatalogId?.takeIf(String::isNotBlank) in existingAppleIds }
        .take(limit)
}

/** The failure to report when every configured source failed to answer. */
internal fun firstRecommendationFailure(failures: List<Throwable>): Throwable? =
    failures.firstOrNull()

/** Why strict Apple mode could not keep any of its candidate rows. */
internal fun strictAppleResolutionFailure(rows: List<Song>): Throwable {
    val lookupError = rows.firstNotNullOfOrNull { row ->
        row.appleLookupError?.takeIf(String::isNotBlank)
    }
    return IllegalStateException(
        lookupError ?: "No exact Apple Music catalogue match was found",
    )
}

/** Runs a source request while preserving cancellation and its real failure. */
private suspend fun <T> captureSourceFailure(block: suspend () -> Result<T>): Result<T> =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

private const val APPLE_RECOMMENDATION_POOL_MULTIPLIER = 4
private const val APPLE_RECOMMENDATION_POOL_MIN = 40

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
    station: Boolean = false,
    stationId: String? = null,
): Result<List<Song>> {
    if (limit <= 0) return Result.success(emptyList())

    // The recommendation algorithm decides who is asked what comes next. The
    // seed song is unchanged, and the queue-entry mapping below is shared, so
    // every algorithm's batch lands in the queue in the same shape.
    val algorithm = AppSettings.recommendationAlgorithm.value
    val excluded = existing.mapTo(HashSet()) { it.videoId }
    val sourceFailures = mutableListOf<Throwable>()
    // Strict Apple mode resolves the pool after asking each source. Ask for a
    // wider set first, so an unresolved early row cannot shrink the answer.
    val candidateLimit = if (AppSettings.appleRichModeEnabled) limit * 3 else limit

    // Whether Apple's rows came from the station the queue belongs to. Kept
    // beside the pool because the pool itself is a plain list of songs, and a
    // station that answered only with already-queued rows needs the fallback.
    val appleFromStation = java.util.concurrent.atomic.AtomicBoolean(false)

    val (spotifyCandidates, youtubeCandidates, appleCandidates) = coroutineScope {
        val spotify = async {
            if (!algorithm.includes(RecommendationAlgorithm.SPOTIFY)) return@async emptyList<Song>()
            captureSourceFailure {
                Result.success(
                    com.music.bitchord.data.spotify.SpotifyHomeSource.autoplayTracks(
                        exclude = excluded,
                        limit = candidateLimit,
                        seed = seedSong,
                        station = station,
                    ),
                )
            }.getOrElse {
                sourceFailures += it
                emptyList()
            }
        }
        val youtube = async {
            if (!algorithm.includes(RecommendationAlgorithm.YOUTUBE)) return@async emptyList<Song>()
            youtubeRadioCandidates(seedSong).getOrElse {
                sourceFailures += it
                emptyList()
            }
        }
        val apple = async {
            // An Apple-backed song has an exact catalogue id, so its seeded
            // neighbours are the right answer even when discovery is set to
            // YouTube or Spotify. Only a non-Apple song falls back to the
            // selected algorithm's own feed.
            if (!algorithm.includes(RecommendationAlgorithm.APPLE) &&
                seedSong.appleCatalogId.isNullOrBlank()
            ) {
                return@async emptyList<Song>()
            }
            // A queue that belongs to a station is topped up from that
            // station, so it keeps being the station the listener chose.
            val stationPool = stationId?.takeIf { seedSong.appleCatalogId != null }
            if (stationPool != null) {
                val rows = appleStationPool(stationPool, candidateLimit).getOrElse {
                    sourceFailures += it
                    emptyList()
                }
                if (rows.isNotEmpty()) {
                    appleFromStation.set(true)
                    return@async rows
                }
            }
            appleAutoplayPool(seedSong, candidateLimit).getOrElse {
                sourceFailures += it
                emptyList()
            }
        }
        awaitAll(spotify, youtube, apple)
    }

    var unseenAppleCandidates = selectUnseenAppleCandidates(
        pool = appleCandidates,
        existing = existing,
        limit = candidateLimit,
    )

    // A station that answered only with rows already queued would leave the
    // queue dry. The neighbour feed is the honest fallback then: it is still
    // Apple's own answer for the same recording, and it keeps the station
    // playing instead of stopping at the end of the list.
    if (unseenAppleCandidates.isEmpty() && appleFromStation.get()) {
        unseenAppleCandidates = selectUnseenAppleCandidates(
            pool = appleAutoplayPool(seedSong, candidateLimit).getOrElse {
                sourceFailures += it
                emptyList()
            },
            existing = existing,
            limit = candidateLimit,
        )
    }

    // All mode takes turns between the three feeds. Concatenating them would
    // let the first full feed consume the whole limit and leave the other two
    // algorithms with no representation at all.
    val candidates = interleaveRecommendationCandidates(
        groups = listOf(spotifyCandidates, youtubeCandidates, unseenAppleCandidates),
        limit = candidateLimit,
    )

    // A source failure must not look like an empty station. If no source
    // answered, keep the real error for the caller to log or show.
    if (candidates.isEmpty()) {
        firstRecommendationFailure(sourceFailures)?.let { return Result.failure(it) }
    }

    // Apple mode keeps Apple as the audio backend. Rows without an exact
    // Apple identity are dropped rather than silently falling through to a
    // generic catalogue. Resolve the wider pool first, then take the visible
    // limit, so an unresolved early row cannot shrink the answer.
    val resolvedCandidates = if (AppSettings.appleRichModeEnabled) {
        val enriched = AppleEnrichment.enrich(candidates)
        val resolved = enriched
            .filter { !it.appleCatalogId.isNullOrBlank() }
            .distinctBy { it.appleCatalogId }
        if (resolved.isEmpty()) return Result.failure(strictAppleResolutionFailure(enriched))
        resolved.take(limit)
    } else {
        candidates.distinctBy { it.videoId }.take(limit)
    }

    val extra = QueueBuilder.extend(existing, resolvedCandidates, limit)
        .filterNot(AppSettings::isSongBlocked)
    if (extra.isEmpty()) {
        firstRecommendationFailure(sourceFailures)?.let { return Result.failure(it) }
        return Result.success(emptyList())
    }

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}
