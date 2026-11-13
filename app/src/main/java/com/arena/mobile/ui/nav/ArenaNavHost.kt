package com.arena.mobile.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arena.feature.arena.agent.AgentModeScreen
import com.arena.feature.arena.battle.BattleScreen
import com.arena.feature.arena.code.CodeEditorScreen
import com.arena.feature.arena.direct.DirectChatScreen
import com.arena.feature.arena.home.HomeAdaptiveScreen
import com.arena.feature.arena.home.HomeMode
import com.arena.feature.arena.imageeditor.NativeImageEditorScreen
import com.arena.feature.arena.leaderboard.OfflineLeaderboardScreen
import com.arena.feature.arena.preview.LivePreviewScreen
import com.arena.feature.arena.session.ArenaSessionHostViewModel
import com.arena.feature.arena.session.SessionWebViewHost
import com.arena.feature.arena.sidebyside.SideBySideScreen

/**
 * Predictive-back friendly navigation graph. All primary modes are one tap
 * from Home; secondary surfaces (editor, leaderboard) stack on top.
 */
object Routes {
    const val HOME = "home"
    const val BATTLE = "battle"
    const val DIRECT = "direct"
    const val SIDE_BY_SIDE = "side_by_side"
    const val AGENT = "agent"
    const val IMAGE_EDITOR = "image_editor"
    const val CODE_EDITOR = "code_editor"
    const val LEADERBOARD = "leaderboard"
    const val PREVIEW = "preview"
}

@Composable
fun ArenaNavHost(
    navController: NavHostController = rememberNavController(),
) {
    // The authenticated arena.ai document lives in a hidden session WebView
    // that exists only while a mode screen is on top (never on Home, never at
    // idle) — this is what the typed bridge talks to.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val sessionHolder: ArenaSessionHostViewModel = hiltViewModel()
    val sessionActive = currentRoute in setOf(
        Routes.BATTLE,
        Routes.DIRECT,
        Routes.SIDE_BY_SIDE,
        Routes.AGENT,
        Routes.IMAGE_EDITOR,
        Routes.CODE_EDITOR,
    )

    Box(Modifier.fillMaxSize()) {
        ArenaNavGraph(navController)

        if (sessionActive) {
            SessionWebViewHost(session = sessionHolder.sessionRepository)
        }
    }
}

@Composable
private fun ArenaNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(160)) },
        popExitTransition = { slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(220)) },
    ) {
        composable(Routes.HOME) {
            HomeAdaptiveScreen(
                onOpenMode = { mode ->
                    when (mode) {
                        HomeMode.BATTLE -> navController.navigate(Routes.BATTLE)
                        HomeMode.DIRECT -> navController.navigate(Routes.DIRECT)
                        HomeMode.SIDE_BY_SIDE -> navController.navigate(Routes.SIDE_BY_SIDE)
                        HomeMode.AGENT -> navController.navigate(Routes.AGENT)
                        HomeMode.PREVIEW -> navController.navigate(Routes.PREVIEW)
                    }
                },
                onOpenLeaderboard = { navController.navigate(Routes.LEADERBOARD) },
                onOpenImageEditor = { navController.navigate(Routes.IMAGE_EDITOR) },
                onOpenCodeEditor = { navController.navigate(Routes.CODE_EDITOR) },
            )
        }
        composable(Routes.BATTLE) { BattleScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.DIRECT) { DirectChatScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SIDE_BY_SIDE) { SideBySideScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.AGENT) { AgentModeScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.IMAGE_EDITOR) { NativeImageEditorScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.CODE_EDITOR) { CodeEditorScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.LEADERBOARD) { OfflineLeaderboardScreen(onBack = { navController.popBackStack() }) }
        // Live Preview owns its own pooled WebView (no session host needed).
        composable(Routes.PREVIEW) { LivePreviewScreen(onBack = { navController.popBackStack() }) }
    }
}
