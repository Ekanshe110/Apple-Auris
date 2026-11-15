package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.apple.AppleLibraryCollection
import com.music.bitchord.data.apple.AppleLibraryScope
import com.music.bitchord.data.apple.AppleLibraryStore
import com.music.bitchord.data.apple.rowKey
import com.music.bitchord.data.apple.toSong
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import kotlinx.coroutines.launch

/**
 * The authenticated Apple Music library exposed by the configured addon.
 *
 * - Pages are cached for the session, so leaving and coming back shows rows
 *   immediately and refreshes them in the background.
 * - Pages append; failures keep the rows already loaded.
 * - The internal playlist/album detail clears with one Back.
 * - The top app-bar back arrow is the only way out of the detail/list.
 */
@Composable
fun AppleLibraryScreen(
    config: SourceConfig,
    onPlaySong: (Song) -> Unit,
    contentPadding: PaddingValues,
    collectionIndex: Int,
    detailId: String?,
    onCollectionChange: (Int) -> Unit,
    onDetailChange: (String?) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    detailListState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val collection = AppleLibraryCollection.entries[collectionIndex]
    val scope = remember(config.id, collection, detailId) {
        AppleLibraryStore.scope(config, collection, detailId)
    }
    val state by scope.state.collectAsStateWithLifecycle()
    val activeListState = if (detailId == null) listState else detailListState

    // Reset detail scroll when opening a different detail.
    LaunchedEffect(detailId) {
        detailListState.scrollToItem(0)
    }

    // Load/refresh the current scope.
    LaunchedEffect(config.id, collection, detailId) {
        scope.open(config)
    }

    // Load more when the listener is near the end; prefetch a little earlier.
    val nearEnd by remember(activeListState) {
        derivedStateOf {
            val layout = activeListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layout.totalItemsCount
            if (totalItems == 0) 0f else lastVisible.toFloat() / totalItems.toFloat()
        }
    }
    LaunchedEffect(scope) {
        // snapshotFlow of a derived percentage re-emits whenever the list
        // scrolls or grows. Use a small threshold to avoid extra fetches.
        androidx.compose.runtime.snapshotFlow { nearEnd }
            .collect { ratio ->
                val total = scope.state.value.items.size
                if (total == 0) return@collect
                val lastVisible = (ratio * total).toInt()
                if (lastVisible >= total - LOAD_MORE_THRESHOLD) {
                    scope.loadMore(config)
                }
                if (lastVisible >= total - PREFETCH_THRESHOLD) {
                    scope.prefetch(config)
                }
            }
    }

    // Internal detail stack. Back from a playlist returns to the root list.
    BackHandler(enabled = detailId != null) {
        onDetailChange(null)
    }

    val title = if (detailId == null) {
        "Apple Music Library"
    } else {
        collection.label
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = activeListState,
        contentPadding = contentPadding,
    ) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                // Breadcrumb only. The top app-bar back arrow is the only way
                // to navigate up; this text just says where the listener is.
                if (detailId != null) {
                    Text(
                        text = "Apple Music Library / ${collection.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppleLibraryCollection.entries.forEachIndexed { index, item ->
                    TextButton(
                        onClick = { onCollectionChange(index) },
                        enabled = !state.loading,
                    ) {
                        Text(item.label)
                    }
                }
            }
            // A subtle top indicator when an already-loaded list is refreshing.
            if (state.refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            state.unconfigured -> item { Message("Apple Music library is not configured") }
            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.signedOut -> item {
                Message(
                    "Apple Music account is not connected. Sign in through the configured addon.",
                    error = true,
                )
            }
            state.empty -> item { Message("This Apple Music library collection is empty") }
            state.failure != null -> item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Message(state.failure ?: "Apple Music library is unavailable", error = true)
                    Button(onClick = { scope.open(config) }) { Text("Retry") }
                }
            }
        }

        if (state.items.isNotEmpty()) {
            items(
                items = state.items,
                key = { item -> "${collection.routeName}:${detailId.orEmpty()}:${item.rowKey()}" },
            ) { item ->
                val playable = detailId != null || collection == AppleLibraryCollection.SONGS
                LibraryRow(
                    title = item.title.ifBlank { "Untitled" },
                    subtitle = item.artist.ifBlank { item.album },
                    imageUrl = item.artwork,
                    onClick = {
                        if (playable) {
                            item.toSong(config.id)?.let(onPlaySong)
                        } else {
                            onDetailChange(item.id)
                        }
                    },
                )
            }

            if (state.hasMore) {
                item(key = "footer") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator()
                        } else {
                            val scope2 = rememberCoroutineScope()
                            if (state.moreFailure != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = state.moreFailure ?: "",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Button(
                                        onClick = { scope2.launch { scope.loadMore(config) } },
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { scope2.launch { scope.loadMore(config) } },
                                ) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val LOAD_MORE_THRESHOLD = 8
private const val PREFETCH_THRESHOLD = 15

@Composable
private fun Message(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
