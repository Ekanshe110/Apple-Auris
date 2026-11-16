package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState

private val MORE_TO_EXPLORE = listOf(
    "Concerts",
    "Browse by Genre",
    "Decades",
    "Moods and Activities",
    "Worldwide",
    "Charts",
    "Music Videos",
    "Spatial Audio",
)

/**
 * Apple's New page: a short set of titled shelves, then the browse list.
 *
 * Nothing here is a grid: Apple draws these as horizontal covers over a
 * vertical list, and the list rows open the same discovery surfaces the
 * Search tab owns.
 */
@Composable
fun AppleNewScreen(
    state: UiState<List<HomeShelf>>,
    onItemClick: (ShelfItem) -> Unit,
    onExploreAll: () -> Unit,
    onRetry: () -> Unit,
    showExploreList: Boolean = true,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("New", style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onExploreAll) { Text("Explore all ›") }
            }
        }
        when (state) {
            is UiState.Loading -> items(3) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .height(150.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            is UiState.Error -> item(key = "error") {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
            is UiState.Success -> {
                val shelves = state.data
                    .filter { it.items.isNotEmpty() }
                    .take(3)
                shelves.forEach { shelf ->
                    item(key = "title:${shelf.title}") {
                        Text(
                            text = shelf.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                        )
                    }
                    item(key = "row:${shelf.title}") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                shelf.items,
                                key = { it.browseId ?: it.videoId ?: it.title },
                            ) { item ->
                                NewCover(item = item, onClick = { onItemClick(item) })
                            }
                        }
                    }
                }
                item(key = "more") {
                    Text(
                        text = "More to Explore",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp),
                    )
                }
                items(MORE_TO_EXPLORE, key = { "explore:$it" }) { label ->
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onExploreAll() }
                                .padding(vertical = 14.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text("›", style = MaterialTheme.typography.titleMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            if (showExploreList) {
                item(key = "more") {
                    Text(
                        text = "More to Explore",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp),
                    )
                }
                items(MORE_TO_EXPLORE, key = { "explore:$it" }) { label ->
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onExploreAll() }
                                .padding(vertical = 14.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text("›", style = MaterialTheme.typography.titleMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            Unit
        }
    }
}

@Composable
private fun NewCover(item: ShelfItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(156.dp)
            .clickable(onClick = onClick),
    ) {
        val shape = RoundedCornerShape(14.dp)
        if (item.thumbnailUrl != null) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .clip(shape),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
