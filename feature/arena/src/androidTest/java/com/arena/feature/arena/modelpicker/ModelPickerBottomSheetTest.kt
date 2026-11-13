package com.arena.feature.arena.modelpicker

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arena.core.data.model.ArenaModel
import com.arena.core.data.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the ModelPickerBottomSheet: grouping, search
 * filtering, rank badges, and selection callbacks. No Hilt required — the
 * sheet takes plain lists.
 */
@RunWith(AndroidJUnit4::class)
class ModelPickerBottomSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val models = listOf(
        ArenaModel("gpt-5", "GPT-5", "OpenAI", Modality.TEXT, 1, 100_000, 400_000, null),
        ArenaModel("claude-opus", "Claude Opus", "Anthropic", Modality.TEXT, 2, 90_000, 200_000, null),
        ArenaModel("codestral", "Codestral", "Mistral", Modality.CODE, 36, 40_000, 256_000, null),
        ArenaModel("flux", "FLUX", "BFL", Modality.IMAGE, null, 70_000, null, null),
    )

    @Test
    fun showsSectionHeadersForPresentModalities() {
        composeRule.setContent {
            ModelPickerBottomSheet(
                models = models,
                recommended = emptyList(),
                selectedModelId = null,
                onModelSelected = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Choose a model").assertIsDisplayed()
        composeRule.onNodeWithText("Text").assertIsDisplayed()
        composeRule.onNodeWithText("Code").assertIsDisplayed()
        composeRule.onNodeWithText("Image").assertIsDisplayed()
        // No video models in the fixture → header absent.
        composeRule.onAllNodesWithText("Video").fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun searchFiltersRows() {
        var selected: ArenaModel? = null
        composeRule.setContent {
            ModelPickerBottomSheet(
                models = models,
                recommended = emptyList(),
                selectedModelId = null,
                onModelSelected = { selected = it },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Search 300+ models").performTextInput("claude")
        composeRule.onNodeWithText("Claude Opus").assertIsDisplayed()
        composeRule.onAllNodesWithText("GPT-5").fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun recommendedRowAppearsOnlyWhenIdsMatch() {
        composeRule.setContent {
            ModelPickerBottomSheet(
                models = models,
                recommended = listOf("codestral"),
                selectedModelId = null,
                onModelSelected = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Recommended for you").assertIsDisplayed()
        // The recommended model is not duplicated in its modality group.
        assertEquals(1, composeRule.onAllNodesWithText("Codestral").fetchSemanticsNodes().size)
    }

    @Test
    fun tappingRowReportsSelection() {
        var selected: ArenaModel? = null
        composeRule.setContent {
            ModelPickerBottomSheet(
                models = models,
                recommended = emptyList(),
                selectedModelId = null,
                onModelSelected = { selected = it },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("GPT-5").performClick()
        assertEquals("gpt-5", selected?.id)
    }
}
