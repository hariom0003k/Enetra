package com.example.peepguard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton bridge between [AppWatcherService] and [FaceWatchService].
 *
 * Uses [StateFlow] for safe, reactive, lifecycle-aware state observation.
 * No raw Handlers or volatile callbacks needed.
 */
object GuardState {

    private val _isActive = MutableStateFlow(false)

    /** Observed by [FaceWatchService] via lifecycleScope.collectLatest. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** Called by [AppWatcherService] when the foreground app changes. */
    fun setActive(value: Boolean) {
        _isActive.value = value
    }

    /** Called when the service starts/stops to ensure a clean slate. */
    fun reset() {
        _isActive.value = false
    }
}
