package com.arena.feature.arena.preview

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Thin holder so the Live Preview screen can reach the process-wide
 * [LivePreviewController] through Hilt (the pool must outlive the screen).
 */
@HiltViewModel
class LivePreviewViewModel @Inject constructor(
    val controller: LivePreviewController,
) : ViewModel()
