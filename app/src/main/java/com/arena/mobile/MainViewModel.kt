package com.arena.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.core.data.prefs.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Thin app-level MVI-style view model: only cross-cutting state (theme) lives
 * here. Feature state stays inside :feature:arena view models.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    userPreferences: UserPreferencesRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = userPreferences.themeMode
        .map { raw ->
            runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.DARK)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.DARK, // dark mode default
        )
}
