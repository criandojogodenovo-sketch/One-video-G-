package com.onevideog.editor.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide export state shared between [ExportService] (producer) and any
 * UI observer (consumer). Survives configuration changes; the service is the
 * single writer.
 */
object ExportBus {

    data class Snapshot(
        val projectId: String? = null,
        val projectName: String? = null,
        val state: ExportManager.State = ExportManager.State.Idle,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> get() = _snapshot

    fun update(projectId: String?, projectName: String?, state: ExportManager.State) {
        _snapshot.value = Snapshot(projectId, projectName, state)
    }

    fun reset() {
        _snapshot.value = Snapshot()
    }
}
