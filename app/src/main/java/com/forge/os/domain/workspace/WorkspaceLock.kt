package com.forge.os.domain.workspace

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global lock for workspace operations.
 * Ensures that snapshots and file writes don't conflict, 
 * preventing corrupted snapshots or partial writes.
 */
@Singleton
class WorkspaceLock @Inject constructor() {
    val mutex = Mutex()
}
