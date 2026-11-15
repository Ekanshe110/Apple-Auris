package com.music.bitchord.ui.apple

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.sources.addon.AddonLibraryItem
import com.music.bitchord.ui.components.FolderPickerSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Add-to-folder flow must expose Apple Music playlists separately. */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class FolderPickerSheetInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun applePlaylistSelectionIsPassedToAppleWriter() {
        var added: List<AddonLibraryItem> = emptyList()
        compose.setContent {
            FolderPickerSheet(
                folders = emptyList(),
                onAdd = {},
                onCreate = {},
                applePlaylists = UiState.Success(
                    listOf(
                        AddonLibraryItem(
                            id = "pl.1",
                            title = "Road Trip",
                            artist = "My Playlists",
                        ),
                    ),
                ),
                onAddToApple = { added = it },
            )
        }

        compose.onNodeWithText("Apple Music").assertIsDisplayed().performClick()
        compose.onNodeWithText("Road Trip").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add to Apple Music playlist").assertIsDisplayed().performClick()

        assertEquals(listOf("pl.1"), added.map { it.id })
    }
}
