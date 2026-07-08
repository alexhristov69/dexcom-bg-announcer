package com.dexcom.bgannouncer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class WorkflowPhase {
    IDLE,
    FETCHING_READING,
    ANNOUNCING,
    FLASHING_BT,
    HANDLING_UNAVAILABLE,
    WAITING_FOR_NEXT_POLL,
}

enum class WorkflowSource {
    NONE,
    MONITORING,
    TEST_BROADCAST,
}

data class WorkflowState(
    val phase: WorkflowPhase = WorkflowPhase.IDLE,
    val message: String? = null,
    val source: WorkflowSource = WorkflowSource.NONE,
    val lastTestBroadcastSummary: String? = null,
    val lastTestBroadcastTime: Long? = null,
) {
    val isActive: Boolean = phase != WorkflowPhase.IDLE

    val isTestBroadcastActive: Boolean =
        source == WorkflowSource.TEST_BROADCAST && phase != WorkflowPhase.IDLE
}

@Singleton
class MonitorWorkflowRepository @Inject constructor() {
    private val _state = MutableStateFlow(WorkflowState())
    val state: StateFlow<WorkflowState> = _state.asStateFlow()

    fun setActive(phase: WorkflowPhase, message: String, source: WorkflowSource) {
        _state.update {
            it.copy(
                phase = phase,
                message = message,
                source = source,
            )
        }
    }

    fun updateFromStep(message: String, source: WorkflowSource) {
        val phase = when {
            message.contains("Announcing", ignoreCase = true) -> WorkflowPhase.ANNOUNCING
            message.contains("BT", ignoreCase = true) -> WorkflowPhase.FLASHING_BT
            else -> WorkflowPhase.ANNOUNCING
        }
        setActive(phase = phase, message = message, source = source)
    }

    fun setWaitingForNextPoll(source: WorkflowSource = WorkflowSource.MONITORING) {
        setActive(
            phase = WorkflowPhase.WAITING_FOR_NEXT_POLL,
            message = "Waiting for next scheduled poll",
            source = source,
        )
    }

    fun completeTestBroadcast(summary: String) {
        _state.update {
            it.copy(
                phase = WorkflowPhase.IDLE,
                message = null,
                source = WorkflowSource.NONE,
                lastTestBroadcastSummary = summary,
                lastTestBroadcastTime = System.currentTimeMillis(),
            )
        }
    }

    fun clearMonitoringWorkflow() {
        _state.update { current ->
            if (current.source == WorkflowSource.MONITORING) {
                current.copy(
                    phase = WorkflowPhase.IDLE,
                    message = null,
                    source = WorkflowSource.NONE,
                )
            } else {
                current
            }
        }
    }
}
