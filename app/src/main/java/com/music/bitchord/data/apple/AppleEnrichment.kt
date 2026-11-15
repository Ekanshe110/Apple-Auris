package com.music.bitchord.data.apple

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.APPLE_CATALOGUE_NOT_FOUND
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/**
 * Attaches Apple's exact catalogue id to discovery rows before they enter a
 * queue.
 *
 * Spotify and YouTube remain discovery catalogues. When Apple Rich Mode is on,
 * this asks the configured Apple addon once per distinct track and stores the
 * exact catalogue id on the [Song]. Playback then opens that id directly; it
 * never converts a provider id on ExoPlayer's loader thread and never asks a
 * metadata matcher to guess another recording.
 *
 * Lookups are bounded, cached, and shared between concurrent callers. A
 * catalogue miss is remembered briefly, while a transient addon or transport
 * failure is not cached and remains immediately retryable.
 */
object AppleEnrichment {

    private const val TAG = "BitChord"
    private const val MAX_CONCURRENT_LOOKUPS = 4
    private const val MAX_CACHE_ENTRIES = 512
    private const val POSITIVE_CACHE_MS = 6 * 60 * 60 * 1000L
    private const val NEGATIVE_CACHE_MS = 5 * 60 * 1000L

    /**
     * Version of the catalogue matcher used by lookup keys.
     *
     * Bump this whenever matching changes. A cached miss made by an older
     * matcher must not survive the new rule, so this version is part of every
     * key and a changed matcher naturally reaches the catalogue again.
     */
    internal const val MATCHER_VERSION = 4

    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookupGate = Semaphore(MAX_CONCURRENT_LOOKUPS)
    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<LookupKey, Deferred<Result<AppleResolution?>>>()

    private data class LookupKey(
        val matcherVersion: Int,
        val sourceIdentity: String,
        val title: String,
        val artist: String,
        val durationSec: Long?,
        val explicitPreference: Boolean?,
        val ratingIdentity: String,
        val isrc: String?,
    )

    private data class CachedLookup(
        val result: Result<AppleResolution?>,
        val expiresAtMs: Long,
    )

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<LookupKey, CachedLookup>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<LookupKey, CachedLookup>) =
                size > MAX_CACHE_ENTRIES
        },
    )

    /** One exact Apple catalogue match, with the rating and recording identity it carried. */
    internal data class AppleResolution(
        val id: String,
        val isExplicit: Boolean?,
        val contentRating: String?,
        val isrc: String?,
    )

    /** Applies catalogue identity while keeping source metadata when Apple omits it. */
    internal fun withAppleResolution(song: Song, resolution: AppleResolution): Song = song.copy(
        appleCatalogId = resolution.id,
        appleLookupError = null,
        isExplicit = resolution.isExplicit ?: song.isExplicit,
        contentRating = resolution.contentRating ?: song.contentRating,
        isrc = resolution.isrc ?: song.isrc,
    )

    /**
     * Enriches rows which still need an exact Apple id.
     *
     * Existing ids are kept. Local files, already source-backed rows and
     * direct media URLs are outside this path: they are not Spotify or YouTube
     * discovery rows and must not be sent to Apple.
     */
    suspend fun enrich(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        if (!AppSettings.appleRichModeEnabled) {
            // Errors describe an active Apple attempt. Once the mode is off,
            // an old persisted failure must not keep colouring the row.
            return songs.map { song ->
                if (song.appleLookupError != null) song.copy(appleLookupError = null) else song
            }
        }

        val config = SourceRegistry.appleCatalogConfig()
        if (config == null) {
            return songs.map { song ->
                if (song.needsAppleLookup()) {
                    song.copy(appleLookupError = "Apple Music is not configured")
                } else {
                    song
                }
            }
        }
        val source = SourceRegistry.instance(config.id) as? AddonSource
        if (source == null) {
            return songs.map { song ->
                if (song.needsAppleLookup()) {
                    song.copy(appleLookupError = "Apple Music addon is unavailable")
                } else {
                    song
                }
            }
        }

        val targets = songs.filter { it.needsAppleLookup() }
        if (targets.isEmpty()) return songs
        val sourceIdentity = sourceIdentity(config)

        val results = coroutineScope {
            targets.distinctBy { it.appleLookupKey(sourceIdentity) }.map { song ->
                async {
                    val key = song.appleLookupKey(sourceIdentity)
                    key to lookup(song, source, key)
                }
            }.awaitAll().toMap()
        }

        return songs.map { song ->
            val result = results[song.appleLookupKey(sourceIdentity)] ?: return@map song
            result.fold(
                onSuccess = { resolution ->
                    if (resolution == null) {
                        song.copy(
                            appleCatalogId = null,
                            appleLookupError = APPLE_CATALOGUE_NOT_FOUND,
                        )
                    } else {
                        withAppleResolution(song, resolution)
                    }
                },
                onFailure = { failure ->
                    song.copy(
                        appleCatalogId = null,
                        appleLookupError = failure.message ?: "Apple Music lookup failed",
                    )
                },
            )
        }
    }

    /** Whether this row is a discovery row which still needs Apple's catalogue id. */
    internal fun Song.needsAppleLookup(): Boolean =
        appleCatalogId == null &&
            localUri == null &&
            !videoId.startsWith("http://") &&
            !videoId.startsWith("https://") &&
            !videoId.startsWith("content://") &&
            !videoId.startsWith("file://") &&
            SourceRegistry.parseTrackKey(videoId) == null

    private fun Song.appleLookupKey(sourceIdentity: String): LookupKey = LookupKey(
        matcherVersion = MATCHER_VERSION,
        sourceIdentity = sourceIdentity,
        title = normalizedTitle(title),
        artist = normalized(artist),
        durationSec = durationMillis().takeIf { it > 0 }?.div(1000),
        explicitPreference = explicitState(this),
        ratingIdentity = explicitRatingIdentity(this),
        isrc = normalizedIsrc(isrc),
    )

    /** A private, collision-resistant identity for this addon and account. */
    internal fun sourceIdentity(config: SourceConfig): String {
        val material = buildString {
            append(config.id).append('\u0000')
            append(config.baseUrl).append('\u0000')
            config.settings.toSortedMap().forEach { (key, value) ->
                append(key).append('\u0000').append(value).append('\u0000')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private suspend fun lookup(
        song: Song,
        source: AddonSource,
        key: LookupKey,
    ): Result<AppleResolution?> {
        cached(key)?.let { return it.result }

        val deferred = inFlightLock.withLock {
            inFlight[key] ?: lookupScope.async {
                lookupGate.withPermit { performLookup(song, source) }
            }.also { inFlight[key] = it }
        }
        return try {
            val result = deferred.await()
            // A transport or addon failure is not evidence that the catalogue
            // lacks the recording. Keep it out of the cache so the next caller
            // retries instead of inheriting a temporary outage as a miss.
            if (shouldCacheLookup(result)) {
                cache[key] = CachedLookup(
                    result = result,
                    expiresAtMs = System.currentTimeMillis() + if (result.getOrNull() == null) {
                        NEGATIVE_CACHE_MS
                    } else {
                        POSITIVE_CACHE_MS
                    },
                )
            }
            result
        } finally {
            inFlightLock.withLock {
                if (inFlight[key] === deferred) inFlight.remove(key)
            }
        }
    }

    /** Whether a lookup answer is durable enough to remember. */
    internal fun shouldCacheLookup(result: Result<*>): Boolean = result.isSuccess

    private fun cached(key: LookupKey): CachedLookup? {
        val entry = cache[key] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry
    }

    /** One Apple catalogue lookup, followed by the pure recording matcher. */
    private suspend fun performLookup(song: Song, source: AddonSource): Result<AppleResolution?> {
        val query = listOf(song.title, song.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (query.isBlank()) return Result.success(null)

        val rows = source.searchResult(query, limit = 10, request = null)
            .getOrElse { failure ->
                TrackLog.w(TAG, "Apple enrichment failed for '${song.title}': ${failure.message}")
                return Result.failure(failure)
            }
        return Result.success(exactAppleMatch(song, rows)?.toResolution())
    }

    internal data class AppleMatch(
        val id: String,
        val artistOverlap: Int,
        val titleStrength: Int,
        val ratingPreference: Int,
        val isExplicit: Boolean?,
        val contentRating: String?,
        val isrc: String?,
    ) {
        fun toResolution(): AppleResolution = AppleResolution(
            id = id,
            isExplicit = isExplicit,
            contentRating = contentRating,
            isrc = isrc,
        )
    }

    /**
     * The Apple id for [song], or null when no row identifies that recording.
     *
     * Kept pure so the identity rule can be tested without a network or an
     * addon. A title-only hit, a near artist, an unknown candidate runtime
     * when the wanted runtime is known, and a runtime outside the tolerance
     * are all refused. Duplicate catalogue editions of one recording are not
     * an ambiguity: rating compatibility comes first, then the fullest artist
     * credit, the closest title spelling, and finally the stable Apple id.
     */
    internal fun exactAppleId(song: Song, candidates: List<Song>): String? =
        exactAppleMatch(song, candidates)?.id

    /** The exact Apple match for [song], including its catalogue metadata. */
    internal fun exactAppleMatch(song: Song, candidates: List<Song>): AppleMatch? {
        val wantedDuration = song.durationSeconds()
        val wantedTitle = normalizedTitle(song.title)
        if (wantedTitle.isBlank()) return null
        val wantedExplicit = explicitState(song)
        val wantedIsrc = normalizedIsrc(song.isrc)

        val matches = candidates.mapNotNull { candidate ->
            val id = candidate.appleCatalogId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val titleStrength = titleMatchStrength(song.title, candidate.title, wantedTitle)
                ?: return@mapNotNull null
            val artistOverlap = artistOverlap(song.artist, candidate.artist)
                ?: return@mapNotNull null
            val candidateDuration = candidate.durationSeconds()
            if (
                wantedDuration != null &&
                (candidateDuration == null ||
                    kotlin.math.abs(candidateDuration - wantedDuration) > DURATION_TOLERANCE_SEC)
            ) {
                return@mapNotNull null
            }
            val candidateIsrc = normalizedIsrc(candidate.isrc)
            // ISRC names the recording, not merely its title or artist. When
            // both catalogue and request publish one, a mismatch is a
            // different recording and must not be resolved by a loose match.
            if (wantedIsrc != null && candidateIsrc != null && wantedIsrc != candidateIsrc) {
                return@mapNotNull null
            }
            val candidateExplicit = explicitState(candidate)
            val candidateRating = normalizedRating(candidate.contentRating)
            val candidateExplicitOnly = candidateExplicit == true || candidateRating == "explicit"
            // An explicit request never falls back to a clean master, and a
            // clean or unknown request never silently becomes explicit. These
            // are hard refusals: changing the words in the track is worse than
            // reporting that the requested master was unavailable.
            if (wantedExplicit == true && candidateExplicit != true) return@mapNotNull null
            if (wantedExplicit != true && candidateExplicitOnly) return@mapNotNull null
            // Clean and unknown candidates are preferred for an unknown
            // request; a clean request prefers a known clean candidate over an
            // unknown one. Explicit candidates have already been refused
            // above unless they were explicitly requested.
            val ratingPreference = when {
                wantedExplicit == true -> 1
                candidateExplicit == false -> 2
                else -> 1
            }
            AppleMatch(
                id = id,
                artistOverlap = artistOverlap,
                titleStrength = titleStrength,
                ratingPreference = ratingPreference,
                isExplicit = candidateExplicit,
                contentRating = candidateRating,
                isrc = candidate.isrc?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        return matches.minWithOrNull(
            compareByDescending<AppleMatch> { it.ratingPreference }
                .thenByDescending { it.artistOverlap }
                .thenByDescending { it.titleStrength }
                .thenBy { it.id },
        )
    }

    /** Explicit state from the row's own flag, rating, or title marker. */
    internal fun explicitState(song: Song): Boolean? = when {
        song.isExplicit != null -> song.isExplicit
        normalizedRating(song.contentRating) == "explicit" -> true
        normalizedRating(song.contentRating) == "clean" -> false
        EXPLICIT_TITLE_MARKER.containsMatchIn(song.title) -> true
        else -> null
    }

    /** A stable rating identity for cache keys and match decisions. */
    internal fun normalizedRating(value: String?): String? =
        value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /** ISRC formatting is not identity: punctuation and case do not matter. */
    internal fun normalizedIsrc(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalized)

    /** Distinguishes clean, explicit, and unknown rating states in cache keys. */
    internal fun explicitRatingIdentity(song: Song): String {
        val state = when (explicitState(song)) {
            true -> "explicit"
            false -> "clean"
            null -> "unknown"
        }
        return "$state|${normalizedRating(song.contentRating) ?: "unknown"}"
    }

    /** Whole seconds available from [Song]'s current `M:SS` duration text. */
    private fun Song.durationSeconds(): Long? =
        durationMillis().takeIf { it > 0 }?.div(1000)

    /**
     * How strongly two titles agree after catalogue packaging is removed.
     *
     * The normalized form drops parentheticals such as `(From "Movie")`, a
     * trailing `From "Movie"` clause, and remix/edition suffixes. The raw
     * punctuation-and-spacing form is kept as a tie-break, so a plainly named
     * Apple row beats the same title carrying packaging when both identify
     * the recording.
     */
    private fun titleMatchStrength(wantedRaw: String, candidateRaw: String, wantedTitle: String): Int? {
        val candidateTitle = normalizedTitle(candidateRaw)
        if (candidateTitle.isBlank() || candidateTitle != wantedTitle) return null
        return if (normalized(candidateRaw) == normalized(wantedRaw)) 2 else 1
    }

    /**
     * The title's recording identity, without catalogue packaging.
     *
     * Only known packaging is removed: a `From "..."` clause and the
     * `[Explicit]` marker. Remix, live, acoustic and edition wording stays:
     * those name different recordings, and collapsing them let an ordinary
     * cut match a remix or an alternate edition.
     */
    internal fun normalizedTitle(value: String): String {
        var text = EXPLICIT_TITLE_MARKER.replace(value, " ")
        repeat(TITLE_PARENTHETICAL_PASSES) {
            text = FROM_PARENTHETICAL.replace(text, " ")
        }
        text = FROM_TAIL.replace(text, " ")
        return normalized(text)
    }

    /**
     * The number of shared artist tokens, or null when the credits do not
     * identify one artist strongly enough.
     *
     * One shared word is too weak: `Shaan` and `Shaan & Someone Else` are a
     * near artist, not proof of the same recording. Two or more meaningful
     * shared tokens are required unless the token sets are identical. A
     * trailing `Topic` is an artist-channel role rather than part of the name
     * and is ignored.
     */
    internal fun artistOverlap(wanted: String, candidate: String): Int? {
        val wantedTokens = artistTokens(wanted)
        val candidateTokens = artistTokens(candidate)
        if (wantedTokens.isEmpty() || candidateTokens.isEmpty()) return null

        val shared = wantedTokens.intersect(candidateTokens)
        if (wantedTokens == candidateTokens) return shared.size
        if (shared.size < MIN_ARTIST_OVERLAP_TOKENS) return null
        if (shared.size * 4 < wantedTokens.size * 3) return null
        return shared.size
    }

    /** Distinct artist tokens, with the YouTube artist-channel role removed. */
    private fun artistTokens(value: String): Set<String> {
        val tokens = value
            .lowercase(Locale.ROOT)
            .split(NON_ALNUM)
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (tokens.lastOrNull() == "topic") {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens.toSet()
    }

    /** Letters and numbers only, so punctuation and spacing cannot split identity. */
    internal fun normalized(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(NON_ALNUM, "")

    private const val DURATION_TOLERANCE_SEC = 3L
    private const val MIN_ARTIST_OVERLAP_TOKENS = 2
    private const val TITLE_PARENTHETICAL_PASSES = 3

    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val FROM_PARENTHETICAL = Regex(
        """\s*\(\s*from\b[^)]*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val FROM_TAIL = Regex("""\s+[-–—]\s+from\b.*$""", RegexOption.IGNORE_CASE)
    private val EXPLICIT_TITLE_MARKER = Regex(
        """\s*[\[(]\s*explicit\s*[\])]""",
        RegexOption.IGNORE_CASE,
    )
}
