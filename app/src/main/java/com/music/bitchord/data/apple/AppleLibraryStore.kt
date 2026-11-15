package com.music.bitchord.data.apple

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One library listing: a collection root, or the tracks of one collection.
 *
 * The scope is the unit that is cached. Leaving the screen and coming back
 * finds the same scope, so the rows, the page cursor and the scroll position
 * the listener had are still there.
 */
internal data class AppleLibraryScopeKey(
    val configId: String,
    val collection: AppleLibraryCollection,
    /** The library id of the opened playlist/album, or null for the root. */
    val collectionId: String?,
) {
    /** Stable across process death, and distinct per collection and detail. */
    val cacheKey: String
        get() = "$configId|${collection.routeName}|${collectionId.orEmpty()}"
}

/**
 * What one listing shows right now.
 *
 * The first page and the pages after it fail differently on purpose: a first
 * page that failed has nothing to show, while a page after the first that
 * failed leaves the rows the listener already has alone.
 */
internal data class AppleLibraryScopeState(
    val items: List<AddonLibraryItem> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    /** The first page is on its way and there is nothing to show yet. */
    val loading: Boolean = false,
    /** A first page is being re-read behind rows that are already on screen. */
    val refreshing: Boolean = false,
    /** A further page is on its way. */
    val loadingMore: Boolean = false,
    /** The first page failed and there are no rows to fall back on. */
    val failure: String? = null,
    /** A further page failed; the rows already loaded stay on screen. */
    val moreFailure: String? = null,
    val signedOut: Boolean = false,
    val unconfigured: Boolean = false,
    val empty: Boolean = false,
)

/**
 * One cached listing.
 *
 * Everything the listener has already loaded stays here for the session: the
 * pages are appended, never replaced, and a refresh never blanks the list.
 */
internal class AppleLibraryScope(val key: AppleLibraryScopeKey) {

    private val firstMutex = Mutex()
    private val moreMutex = Mutex()
    private val prefetchMutex = Mutex()

    private val _state = MutableStateFlow(AppleLibraryScopeState())
    val state: StateFlow<AppleLibraryScopeState> = _state.asStateFlow()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    init {
        if (key.collection == AppleLibraryCollection.LIKED) {
            scope.launch {
                AppleLikedStore.items.collect { items ->
                    _state.value = AppleLibraryScopeState(
                        items = items,
                        hasMore = false,
                        nextOffset = null,
                        empty = items.isEmpty(),
                    )
                }
            }
        }
    }

    internal fun cancel() {
        job.cancel()
    }

    /** Whether the first page has ever been read for this scope. */
    var loaded: Boolean = false
        private set

    /** The page fetched ahead of the listener's next scroll. */
    private var prefetchedOffset: Int? = null
    private var prefetchedPage: AppleLibraryState.Ready? = null

    /**
     * Shows the listing.
     *
     * The first time, the page is read and nothing else is on screen. After
     * that the rows are already there: the page is read again behind them, so
     * the list never blanks and never shows a full-screen spinner.
     */
    suspend fun open(config: SourceConfig) {
        firstMutex.withLock {
            if (loaded) {
                val current = _state.value
                _state.value = current.copy(refreshing = true, failure = null)
                applyFirst(fetch(config, 0), keepRows = true)
            } else {
                _state.value = AppleLibraryScopeState(loading = true)
                applyFirst(fetch(config, 0), keepRows = false)
            }
        }
    }

    /** Appends the next page. Rows already on screen are never replaced. */
    suspend fun loadMore(config: SourceConfig) {
        moreMutex.withLock {
            val current = _state.value
            val offset = current.nextOffset ?: return
            if (!current.hasMore || current.loadingMore) return
            _state.value = current.copy(loadingMore = true, moreFailure = null)
            val page = takePrefetched(offset) ?: fetch(config, offset)
            when (page) {
                is AppleLibraryState.Ready -> {
                    val merged = appendItems(current.items, page.items)
                    _state.value = _state.value.copy(
                        items = merged,
                        hasMore = page.hasMore,
                        nextOffset = page.nextOffset,
                        loadingMore = false,
                        moreFailure = null,
                    )
                }
                is AppleLibraryState.Failure -> {
                    // The rows stay. A failed page is a footer with a retry,
                    // not a reason to throw the list away.
                    _state.value = _state.value.copy(
                        loadingMore = false,
                        moreFailure = page.message,
                    )
                }
                else -> {
                    _state.value = _state.value.copy(loadingMore = false, hasMore = false)
                }
            }
        }
    }

    /**
     * Reads the next page early so the next scroll does not wait for it.
     *
     * The fetch happens off the listener's scroll; the page is kept until
     * [loadMore] asks for it.
     */
    suspend fun prefetch(config: SourceConfig) {
        prefetchMutex.withLock {
            val current = _state.value
            val offset = current.nextOffset ?: return
            if (!current.hasMore || current.loadingMore) return
            if (prefetchedOffset == offset) return
            prefetchedOffset = offset
            val page = fetch(config, offset)
            if (page is AppleLibraryState.Ready) {
                prefetchedPage = page
            } else {
                prefetchedOffset = null
            }
        }
    }

    /** Forgets a prefetched page that the listener has scrolled past. */
    private suspend fun takePrefetched(offset: Int): AppleLibraryState.Ready? {
        prefetchMutex.withLock {
            if (prefetchedOffset != offset) return null
            val page = prefetchedPage
            prefetchedOffset = null
            prefetchedPage = null
            return page
        }
    }

    private suspend fun fetch(config: SourceConfig, offset: Int): AppleLibraryState {
        if (key.collection == AppleLibraryCollection.LIKED) {
            return AppleLibraryState.Ready(
                items = AppleLikedStore.items.value,
                hasMore = false,
                nextOffset = null,
            )
        }
        val repository = AppleLibraryStore.repository(config)
        val collectionId = key.collectionId
        return if (collectionId.isNullOrBlank()) {
            repository.load(key.collection, offset)
        } else {
            repository.tracks(key.collection, collectionId, offset)
        }
    }

    private fun applyFirst(page: AppleLibraryState, keepRows: Boolean) {
        val current = _state.value
        loaded = true
        when (page) {
            is AppleLibraryState.Ready -> {
                _state.value = current.copy(
                    items = page.items,
                    hasMore = page.hasMore,
                    nextOffset = page.nextOffset,
                    loading = false,
                    refreshing = false,
                    failure = null,
                    moreFailure = null,
                    signedOut = false,
                    unconfigured = false,
                    empty = false,
                )
            }
            AppleLibraryState.Empty -> {
                _state.value = current.copy(
                    items = if (keepRows) current.items else emptyList(),
                    hasMore = false,
                    nextOffset = null,
                    loading = false,
                    refreshing = false,
                    failure = null,
                    empty = if (keepRows) false else true,
                )
            }
            is AppleLibraryState.Failure -> {
                _state.value = current.copy(
                    items = if (keepRows) current.items else emptyList(),
                    loading = false,
                    refreshing = false,
                    // A refresh that failed keeps the rows and says nothing
                    // about them. Only a first page with no rows is an error.
                    failure = if (keepRows) null else page.message,
                )
            }
            AppleLibraryState.SignedOut -> {
                _state.value = current.copy(
                    items = if (keepRows) current.items else emptyList(),
                    loading = false,
                    refreshing = false,
                    signedOut = true,
                    failure = null,
                )
            }
            AppleLibraryState.Unconfigured -> {
                _state.value = current.copy(
                    loading = false,
                    refreshing = false,
                    unconfigured = true,
                    failure = null,
                )
            }
            AppleLibraryState.Loading -> Unit
        }
    }
}

/**
 * The session cache for Apple library pages.
 *
 * The repository lives here too: it owns the addon client, and the client's
 * own request cache is what makes a re-entry cheap. A repository built inside
 * the screen threw that cache away every time the listener came back.
 */
object AppleLibraryStore {

    private val repositories = ConcurrentHashMap<String, AppleLibraryRepository>()
    private val scopes = ConcurrentHashMap<String, AppleLibraryScope>()

    /** The one repository for this addon, reused for the whole session. */
    fun repository(config: SourceConfig): AppleLibraryRepository =
        repositories.getOrPut("${config.id}|${config.baseUrl}") {
            AppleLibraryRepository(config)
        }

    /** The one cached listing for this collection or detail. */
    internal fun scope(
        config: SourceConfig,
        collection: AppleLibraryCollection,
        collectionId: String?,
    ): AppleLibraryScope {
        val key = AppleLibraryScopeKey(config.id, collection, collectionId)
        return scopes.getOrPut(key.cacheKey) { AppleLibraryScope(key) }
    }

    /** Forgets every cached listing. Used by tests. */
    internal fun clear() {
        scopes.values.forEach { it.cancel() }
        scopes.clear()
        repositories.clear()
    }
}

/**
 * Appends a page to the rows already on screen.
 *
 * The same library row can appear twice in one listing — a playlist can hold
 * the same song twice — so rows are kept by their own id and a repeat does
 * not become a second line in the list.
 */
/** Stable key for a library row; falls back to title+artist when id is blank. */
internal fun AddonLibraryItem.rowKey(): String =
    id.takeIf { it.isNotBlank() }
        ?: catalogId.takeIf { it.isNotBlank() }
        ?: "${title}\u0000${artist}"

/**
 * Appends a page to the rows already on screen.
 *
 * The same library row can appear twice in one listing — a playlist can hold
 * the same song twice — so rows are kept by their own id and a repeat does
 * not become a second line in the list.
 */
internal fun appendItems(
    existing: List<AddonLibraryItem>,
    incoming: List<AddonLibraryItem>,
): List<AddonLibraryItem> {
    val seen = existing.mapTo(HashSet()) { it.rowKey() }
    return buildList {
        addAll(existing)
        for (item in incoming) {
            if (!seen.add(item.rowKey())) continue
            add(item)
        }
    }
}
