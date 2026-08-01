package org.kundakarlab.nimsfastsummarymobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiPatientSnapshot

internal data class PatientSnapshotPresentation(
    val title: String,
    val identifier: String,
    val demographics: String,
    val location: String
) {
    val hasSecondaryDetails: Boolean
        get() = identifier.isNotBlank() || demographics.isNotBlank() || location.isNotBlank()

    companion object {
        fun from(snapshot: UiPatientSnapshot): PatientSnapshotPresentation = PatientSnapshotPresentation(
            title = snapshot.name.ifBlank { "Patient details unavailable" },
            identifier = snapshot.crNumber.takeIf(String::isNotBlank)?.let { "CR / UHID: $it" }.orEmpty(),
            demographics = snapshot.demographicLine,
            location = snapshot.location.takeIf(String::isNotBlank)?.let { "Location: $it" }.orEmpty()
        )
    }
}

@Composable
internal fun PatientSnapshotCard(
    snapshot: UiPatientSnapshot,
    modifier: Modifier = Modifier
) {
    val presentation = PatientSnapshotPresentation.from(snapshot)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.isAvailable) Color(0xFFF4F8FC) else Color(0xFFF7F7F7)
        )
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("Patient snapshot", style = MaterialTheme.typography.labelMedium, color = Color(0xFF555555))
            Text(
                presentation.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (presentation.hasSecondaryDetails) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (presentation.identifier.isNotBlank()) {
                        Text(presentation.identifier, style = MaterialTheme.typography.bodySmall)
                    }
                    if (presentation.demographics.isNotBlank()) {
                        Text(presentation.demographics, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (presentation.location.isNotBlank()) {
                    Text(presentation.location, style = MaterialTheme.typography.bodySmall, color = Color(0xFF555555))
                }
            } else {
                Text(
                    "Demographic metadata was not available in the parsed report set.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}
