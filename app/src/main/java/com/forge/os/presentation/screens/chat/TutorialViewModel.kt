package com.forge.os.presentation.screens.chat

import androidx.lifecycle.ViewModel
import com.forge.os.domain.tutorial.TutorialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Simple ViewModel to inject TutorialManager into composables.
 */
@HiltViewModel
class TutorialViewModel @Inject constructor(
    val tutorialManager: TutorialManager
) : ViewModel()
