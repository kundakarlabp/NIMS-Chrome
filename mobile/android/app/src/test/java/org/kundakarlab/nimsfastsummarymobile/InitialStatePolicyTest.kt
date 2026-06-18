package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode

class InitialStatePolicyTest {
    @Test fun localOnlyDoesNotRequireHelper() {
        val state = InitialStatePolicy.derive(ProcessingMode.LOCAL_ONLY, false, false)
        assertEquals(AppState.HELPER_READY, state.state)
        assertTrue(state.message.contains("On-device"))
    }

    @Test fun autoWithoutHelperStillAllowsLocalProcessing() {
        val state = InitialStatePolicy.derive(ProcessingMode.AUTO, false, false)
        assertEquals(AppState.HELPER_READY, state.state)
        assertTrue(state.message.contains("on-device", ignoreCase = true))
    }

    @Test fun remoteOnlyRequiresHelper() {
        assertEquals(AppState.NEED_HELPER_SETTINGS, InitialStatePolicy.derive(ProcessingMode.REMOTE_ONLY, true, false).state)
        assertEquals(AppState.HELPER_READY, InitialStatePolicy.derive(ProcessingMode.REMOTE_ONLY, true, true).state)
    }
}
