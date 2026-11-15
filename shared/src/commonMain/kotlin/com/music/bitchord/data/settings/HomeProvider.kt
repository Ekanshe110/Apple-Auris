package com.music.bitchord.data.settings

/**
 * Which catalogue and playback backend the app owns.
 *
 * This is deliberately separate from recommendation discovery. It controls the
 * provider's libraries, search routing, and source-backed playback identity:
 *
 *  - YouTube Music and Spotify expose their own catalogues and libraries.
 *  - Apple Music owns Apple catalogue playback. Discovery rows are enriched
 *    with an exact Apple catalogue id and played through the configured Apple
 *    addon.
 *
 * Recommendation algorithms are selected independently by
 * [RecommendationAlgorithm]. Changing the catalogue can still require a
 * restart because libraries, source routing, and playback identity all move
 * together.
 */
enum class HomeProvider {
    /** YouTube Music, through the app's own innertube session. The default. */
    YOUTUBE,

    /**
     * Spotify: the listener's own playlists, top tracks and history when they
     * are signed in, and Spotify's public browse lists (featured playlists, new
     * releases, categories) when they are not, so a selection made without a
     * sign-in still shows music.
     */
    SPOTIFY,

    /**
     * Apple Music catalogue playback.
     *
     * Discovery rows may still come from Spotify or YouTube Music, but every
     * row is enriched with its exact Apple catalogue id and opened through the
     * configured Apple addon. This is the single persisted choice that enables
     * Apple Rich Mode; there is no second master switch.
     */
    APPLE,
    ;

    /** Whether this provider can only be read by a signed-in listener. */
    val needsSignIn: Boolean get() = this == SPOTIFY

    companion object {
        /** The provider named [name], or [YOUTUBE] when it names nothing known. */
        fun of(name: String?): HomeProvider =
            entries.firstOrNull { it.name == name } ?: YOUTUBE
    }
}
