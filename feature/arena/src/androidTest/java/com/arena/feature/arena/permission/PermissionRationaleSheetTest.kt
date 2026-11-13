package com.arena.feature.arena.permission

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arena.core.permissions.PermissionUseCase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the pre-permission rationale sheet: the system dialog
 * must fire only after Continue, and dismiss must never trigger a request.
 */
@RunWith(AndroidJUnit4::class)
class PermissionRationaleSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cameraRationaleExplainsAndContinues() {
        var continued = false
        var dismissed = false
        composeRule.setContent {
            PermissionRationaleSheet(
                useCase = PermissionUseCase.Camera,
                onContinue = { continued = true },
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Camera access").assertIsDisplayed()
        composeRule.onNodeWithText("photo picker").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").performClick()
        assertTrue(continued)
        assertTrue(!dismissed)
    }

    @Test
    fun notNowDismissesWithoutContinuing() {
        var continued = false
        var dismissed = false
        composeRule.setContent {
            PermissionRationaleSheet(
                useCase = PermissionUseCase.RECORD_AUDIO,
                onContinue = { continued = true },
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Microphone access").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").performClick()
        assertTrue(dismissed)
        assertTrue(!continued)
    }

    @Test
    fun notificationRationaleMentionsBattleVoting() {
        var dismissed = false
        composeRule.setContent {
            PermissionRationaleSheet(
                useCase = PermissionUseCase.POST_NOTIFICATIONS,
                onContinue = {},
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Vote").assertIsDisplayed()
    }
}
