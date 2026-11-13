package com.arena.feature.arena.session

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Tiny holder exposing the singleton [ArenaSessionRepository] to the
 * navigation host so it can compose the hidden session WebView only while a
 * mode screen is on top. No state of its own — feature view models talk to
 * the repository directly.
 */
@HiltViewModel
class ArenaSessionHostViewModel @Inject constructor(
    val sessionRepository: ArenaSessionRepository,
) : ViewModel()
