package org.kundakarlab.nimsfastsummarymobile

import org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode

data class InitialAppState(val state: AppState, val message: String)

object InitialStatePolicy {
    fun derive(mode: ProcessingMode, hasHelperUrl: Boolean, hasApiKey: Boolean): InitialAppState = when (mode) {
        ProcessingMode.LOCAL_ONLY -> InitialAppState(AppState.HELPER_READY, "On-device processing enabled for text, HTML, and text-based PDF reports.")
        ProcessingMode.AUTO -> InitialAppState(AppState.HELPER_READY, "Automatic mode ready. Reports process on-device first; Railway fallback is optional legacy behavior.")
        ProcessingMode.REMOTE_ONLY -> if (hasHelperUrl && hasApiKey) {
            InitialAppState(AppState.HELPER_READY, "Railway helper ready. Login to NIMS manually.")
        } else {
            InitialAppState(AppState.NEED_HELPER_SETTINGS, "Configure Railway helper URL and API key for Railway-only mode.")
        }
    }
}
