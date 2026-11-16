package com.music.bitchord.data.apple

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Locally persisted Apple-liked songs.
 *
 * Apple Music does not expose a "Liked Songs" smart playlist that the addon
 * can read reliably, so the app keeps its own list. The full row is stored so
 * the Liked tab has artwork, title and artist without a round trip.
 */
object AppleLikedStore {

    private const val PREFS_NAME = "apple_liked"
    private const val KEY_ITEMS = "items"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var prefs: SharedPreferences
    private val _items = MutableStateFlow<List<AddonLibraryItem>>(emptyList())
    val items: StateFlow<List<AddonLibraryItem>> = _items.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _items.value = loadItems()
    }

    /** Whether this exact Apple row is currently liked. */
    fun isLiked(videoId: String): Boolean = _items.value.any { it.id == videoId }

    /**
     * The library page with Apple's local likes merged into its Liked Music rows.
     *
     * Apple's local collection is independent of YouTube's account. Keeping
     * the two lists apart in the model would make the same heart appear empty
     * after a restart even though its exact Apple row was persisted.
     */
    internal fun mergeIntoLibrary(
        library: UiState<LibraryPage>,
        items: List<AddonLibraryItem> = _items.value,
        playlists: List<AddonLibraryItem> = emptyList(),
    ): UiState<LibraryPage> {
        val appleLiked = items.mapNotNull { it.toLikedSong() }
        val playlistShelf = playlists
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .map { item ->
                ShelfItem(
                    title = item.title,
                    subtitle = item.artist,
                    thumbnailUrl = item.artworkURL.ifBlank { null },
                    videoId = null,
                    browseId = "apple:playlist:${item.id}",
                )
            }
            .takeIf { it.isNotEmpty() }
            ?.let { HomeShelf("Apple Music Playlists", items = it) }
        if (appleLiked.isEmpty() && playlistShelf == null) return library
        val page = (library as? UiState.Success)?.data
            ?: return if (library is UiState.Loading) {
                UiState.Success(
                    LibraryPage(
                        likedSongs = appleLiked,
                        librarySongs = emptyList(),
                        shelves = listOfNotNull(playlistShelf),
                    ),
                )
            } else {
                library
            }
        val known = page.likedSongs.mapTo(HashSet()) { it.videoId }
        val merged = appleLiked.filter { known.add(it.videoId) } + page.likedSongs
        val shelves = if (playlistShelf != null) {
            listOf(playlistShelf) + page.shelves.filterNot {
                it.title == playlistShelf.title
            }
        } else {
            page.shelves
        }
        return UiState.Success(page.copy(likedSongs = merged, shelves = shelves))
    }

    /** Rating state seeded from the persisted Apple collection. */
    internal fun likeStatuses(
        items: List<AddonLibraryItem> = _items.value,
    ): Map<String, LikeStatus> =
        items.mapNotNull { it.toLikedSong() }.associate { it.videoId to LikeStatus.LIKE }

    /** Adds or removes a row, returning the new liked state. */
    fun toggle(item: AddonLibraryItem): Boolean {
        val current = _items.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        val liked = if (index >= 0) {
            current.removeAt(index)
            false
        } else {
            current.add(item)
            true
        }
        _items.value = current
        saveItems(current)
        return liked
    }

    fun add(item: AddonLibraryItem) {
        if (_items.value.any { it.id == item.id }) return
        val next = _items.value + item
        _items.value = next
        saveItems(next)
    }

    fun remove(videoId: String) {
        val next = _items.value.filterNot { it.id == videoId }
        if (next.size == _items.value.size) return
        _items.value = next
        saveItems(next)
    }

    private fun loadItems(): List<AddonLibraryItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(AddonLibraryItem.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<AddonLibraryItem>) {
        prefs.edit()
            .putString(
                KEY_ITEMS,
                json.encodeToString(ListSerializer(AddonLibraryItem.serializer()), items),
            )
            .apply()
    }
}

/**
 * A compact library item from a playing song, used for the local liked list.
 *
 * [id] is the app's track key, so the rating state and the persisted row use
 * one identity. [streamId] is only the addon's raw stream id: it is what the
 * addon accepts when opening a stream and must not contain the app's own
 * `src:<config>::` prefix.
 */
internal fun Song.asLibraryItem(): AddonLibraryItem = AddonLibraryItem(
    id = videoId,
    catalogId = appleCatalogId.orEmpty(),
    streamId = SourceRegistry.parseTrackKey(videoId)?.second ?: videoId,
    title = title,
    artist = artist,
    album = albumName.orEmpty(),
    artworkURL = thumbnailUrl.orEmpty(),
    duration = durationText?.let { parseDurationText(it) },
    contentRating = contentRating,
    explicit = isExplicit,
    isrc = isrc,
    atmos = atmosAvailable,
)

/**
 * Rebuilds a persisted Apple-liked row without inventing a second identity.
 *
 * The persisted id is already the app's track key. Rebuilding it through
 * `toSong(configId)` would prefix that key again and produce
 * `src:<config>::src:<config>::<stream>`, so this path preserves the exact id
 * while retaining the raw stream id for playback.
 */
internal fun AddonLibraryItem.toLikedSong(): Song? {
    val id = id.takeIf { it.isNotBlank() } ?: return null
    val normalizedRating = contentRating
        ?.trim()
        ?.lowercase(java.util.Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
    return Song(
        videoId = id,
        title = title.ifBlank { "Untitled" },
        artist = artist,
        albumName = album.ifBlank { null },
        thumbnailUrl = artworkURL.ifBlank { null },
        durationText = durationSec?.let {
            "${it / 60}:${"%02d".format(java.util.Locale.ROOT, it % 60)}"
        },
        appleCatalogId = catalogId.trim().takeIf { it.isNotEmpty() },
        isExplicit = explicit ?: when (normalizedRating) {
            "explicit" -> true
            "clean" -> false
            else -> null
        },
        contentRating = normalizedRating,
        isrc = isrc?.trim()?.takeIf { it.isNotEmpty() },
        atmosAvailable = atmos == true,
    )
}

/** Converts "4:35" to 275.0 seconds, or null if it does not parse. */
internal fun parseDurationText(text: String): Double? {
    val parts = text.split(":")
    return try {
        when (parts.size) {
            2 -> parts[0].toInt() * 60.0 + parts[1].toInt()
            3 -> parts[0].toInt() * 3600.0 + parts[1].toInt() * 60.0 + parts[2].toInt()
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}
