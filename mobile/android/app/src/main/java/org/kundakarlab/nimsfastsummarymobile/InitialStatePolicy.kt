package org.kundakarlab.nimsfastsummarymobile

import org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode

data class InitialAppState(val state: AppState, val message: String)

object InitialStatePolicy {
    fun derive(mode: ProcessingMode, hasHelperUrl: Boolean, hasApiKey: Boolean): InitialAppState = when (mode) {
        ProcessingMode.LOCAL_ONLY -> InitialAppState(AppState.HELPER_READY, "On-device processing enabled. PDF reports are not yet supported.")
        ProcessingMode.AUTO -> if (hasHelperUrl && hasApiKey) {
            InitialAppState(AppState.HELPER_READY, "Automatic mode ready. Supported text reports process on-device; PDFs use Railway.")
        } else {
            InitialAppState(AppState.HELPER_READY, "On-device processing available. Configure Railway for PDF and unsupported reports.")
        }
        ProcessingMode.REMOTE_ONLY -> if (hasHelperUrl && hasApiKey) {
            InitialAppState(AppState.HELPER_READY, "Railway helper ready. Login to NIMS manually.")
        } else {
            InitialAppState(AppState.NEED_HELPER_SETTINGS, "Configure Railway helper URL and API key.")
        }
    }
}
