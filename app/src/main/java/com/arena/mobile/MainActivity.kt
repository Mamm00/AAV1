package com.arena.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.mobile.ui.nav.ArenaNavHost
import com.arena.mobile.ui.theme.ArenaTheme
import com.arena.mobile.ui.theme.DarkColorScheme
import com.arena.mobile.ui.theme.LightColorScheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. Edge-to-edge is mandatory: content flows under the
 * system bars via Compose WindowInsets and is never clipped.
 *
 * Dark mode is the default; [ArenaTheme] follows the system only when the user
 * has not pinned an explicit theme in preferences.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            ArenaTheme(darkTheme = darkTheme, colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme) {
                ArenaNavHost()
            }
        }
    }
}
