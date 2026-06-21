package org.kundakarlab.nimsfastsummarymobile.navigation

sealed class NimsNavigationOutcome {
    data object CrSearchReady : NimsNavigationOutcome()
    data object ReportListReady : NimsNavigationOutcome()
    data object ManualLoginRequired : NimsNavigationOutcome()
    data object SessionExpired : NimsNavigationOutcome()
    data object Timeout : NimsNavigationOutcome()
    data object Cancelled : NimsNavigationOutcome()
    data class Failed(val errorCode: String) : NimsNavigationOutcome()
}
