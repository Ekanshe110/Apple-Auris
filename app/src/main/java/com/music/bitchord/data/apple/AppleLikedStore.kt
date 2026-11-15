package com.music.bitchord.data.apple

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
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
 * The stream id is the addon track key; the catalog id is the Apple id the
 * server needs for rating writes.
 */
internal fun Song.asLibraryItem(): AddonLibraryItem = AddonLibraryItem(
    id = videoId,
    catalogId = appleCatalogId.orEmpty(),
    streamId = videoId,
    title = title,
    artist = artist,
    album = albumName.orEmpty(),
    artworkURL = thumbnailUrl.orEmpty(),
    duration = durationText?.let { parseDurationText(it) },
    contentRating = contentRating,
    explicit = isExplicit,
    isrc = isrc,
)

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
