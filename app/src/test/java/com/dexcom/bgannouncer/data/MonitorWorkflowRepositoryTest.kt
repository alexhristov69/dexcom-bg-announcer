package com.dexcom.bgannouncer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorWorkflowRepositoryTest {
    @Test
    fun updateFromStep_mapsBluetoothFlashPhase() {
        val repository = MonitorWorkflowRepository()
        repository.updateFromStep("Flashing BT art…", WorkflowSource.MONITORING)

        val state = repository.state.value
        assertEquals(WorkflowPhase.FLASHING_BT, state.phase)
        assertEquals(WorkflowSource.MONITORING, state.source)
        assertTrue(state.isActive)
    }

    @Test
    fun completeTestBroadcast_storesSummaryAndReturnsIdle() {
        val repository = MonitorWorkflowRepository()
        repository.setActive(
            phase = WorkflowPhase.ANNOUNCING,
            message = "Announcing…",
            source = WorkflowSource.TEST_BROADCAST,
        )

        repository.completeTestBroadcast("137 mg/dL → · announced · BT flash")

        val state = repository.state.value
        assertEquals(WorkflowPhase.IDLE, state.phase)
        assertEquals("137 mg/dL → · announced · BT flash", state.lastTestBroadcastSummary)
        assertFalse(state.isTestBroadcastActive)
    }
}
