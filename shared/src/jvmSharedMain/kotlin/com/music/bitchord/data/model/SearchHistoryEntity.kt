package com.music.bitchord.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An entity the user tapped in search results — a track, album, artist, or playlist.
 *
 * Unlike raw query strings, these carry the exact identity (id, artwork, type) of what was
 * clicked, so recents render instantly with real cover art and tapping one navigates directly
 * to that entity instead of re-running a text search.
 */
@Serializable
data class SearchHistoryEntity(
    /** Unique id: videoId for tracks, browseId for albums/artists/playlists. */
    val id: String,
    /** Display title — song name, album name, artist name, etc. */
    val title: String,
    /** Subtitle — e.g. "Song • Drake", "Album • Artist", or just the type label. */
    val subtitle: String,
    /** Artwork URL for the cover/thumbnail. */
    val artworkUrl: String?,
    /** The kind of entity this represents. */
    val entityType: EntityType,
    /** Timestamp (epoch millis) — updated on each re-tap to bubble it to the top. */
    val timestamp: Long = System.currentTimeMillis(),
    /** Exact Apple catalogue id for a track row, when it was enriched. */
    val appleCatalogId: String? = null,
    /** Why an exact Apple catalogue id could not be attached. */
    val appleLookupError: String? = null,
    /** Explicit-content state of the exact recording. */
    val isExplicit: Boolean? = null,
    /** Apple content rating, when the catalogue published one. */
    val contentRating: String? = null,
    /** ISRC naming the exact recording. */
    val isrc: String? = null,
) {
    companion object {
        /**
         * Build a track row from the song that was actually selected.
         *
         * Keeping this copy in one place stops search-history callers from
         * persisting the catalogue id while silently dropping the explicit
         * state, rating, and ISRC that identify the same recording.
         */
        fun fromSong(song: Song, subtitle: String = song.artist): SearchHistoryEntity =
            SearchHistoryEntity(
                id = song.videoId,
                title = song.title,
                subtitle = subtitle,
                artworkUrl = song.thumbnailUrl,
                entityType = EntityType.TRACK,
                appleCatalogId = song.appleCatalogId,
                appleLookupError = song.appleLookupError,
                isExplicit = song.isExplicit,
                contentRating = song.contentRating,
                isrc = song.isrc,
            )
    }
}

enum class EntityType {
    @SerialName("TRACK") TRACK,
    @SerialName("ALBUM") ALBUM,
    @SerialName("ARTIST") ARTIST,
    @SerialName("PLAYLIST") PLAYLIST,
}
