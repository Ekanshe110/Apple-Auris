package com.music.bitchord.data.settings

/**
 * Which recommendation algorithm builds Home, Flow and Autoplay.
 *
 * This is deliberately separate from [HomeProvider]. The content provider
 * owns catalogue/library behaviour and Apple playback routing; this setting
 * owns discovery only. Selecting Apple Music here therefore never changes the
 * audio backend of a library row, and selecting another algorithm never
 * removes an Apple library.
 */
enum class RecommendationAlgorithm(val label: String) {
    /** Apple Music's own personalised recommendations. */
    APPLE("Apple Music"),

    /** Spotify's own personalised recommendations. */
    SPOTIFY("Spotify"),

    /** YouTube Music's own personalised recommendations. */
    YOUTUBE("YouTube Music"),

    /** All three feeds, merged and de-duplicated. */
    ALL("All"),
    ;

    /** Whether this algorithm asks [other] for rows as well. */
    fun includes(other: RecommendationAlgorithm): Boolean =
        this == ALL || this == other

    companion object {
        /** The named algorithm, or [YOUTUBE] when the name is unknown. */
        fun of(name: String?): RecommendationAlgorithm =
            entries.firstOrNull { it.name == name } ?: YOUTUBE
    }
}
