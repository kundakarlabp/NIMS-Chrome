package org.kundakarlab.nimsresults

/** Incremental, patient-scoped accumulation of dated Custom Sheet payloads.
 * Stable, page-qualified observation IDs prevent Compose's duplicate lazy
 * list keys when each independent NIMS response starts its IDs at nims-0.
 */
object BulkAccumulator {
    fun append(prior: ClinicalBundle?, next: ClinicalBundle, batchNumber: Int): ClinicalBundle {
        require(next.patient.crNo.matches(Regex("""\d{15}"""))) {
            "Invalid patient CR for batch merge."
        }
        require(batchNumber > 0) { "Invalid Custom Sheet batch number." }
        if (prior != null && prior.patient.crNo != next.patient.crNo) {
            throw IllegalStateException("Mismatched patient CR in staged results.")
        }
        val present = prior?.results.orEmpty()
        val firstPage = next.results.mapIndexed { index, row ->
            row.copy(id = "batch-" + batchNumber + "-row-" + index)
        }
        // Dates are non-overlapping when the NIMS date-checkbox labels can be
        // decoded. If a server repeats rows at a page boundary, exclude only
        // exact source duplicates across batches, not repeated rows within
        // a single source response.
        val seen = present.mapTo(HashSet()) { identity(it) }
        val added = firstPage.filter { seen.add(identity(it)) }
        return ClinicalBundle(
            patient = next.patient.copy(
                name = next.patient.name.ifBlank { prior?.patient?.name.orEmpty() },
                age = next.patient.age.ifBlank { prior?.patient?.age.orEmpty() },
                sex = next.patient.sex.ifBlank { prior?.patient?.sex.orEmpty() }
            ),
            results = (present + added).sortedWith(
                compareByDescending<LabResult> { BulkParser.dateKey(it.date) }
                    .thenBy { it.test }.thenBy { it.parameter }
                    .thenBy { it.id }
            ),
            reports = prior?.reports.orEmpty().ifEmpty { next.reports },
            source = "nims_bulk_api"
        )
    }

    private fun identity(result: LabResult): String =
        listOf(result.sourceKey, result.test, result.parameter, result.date,
            result.valueText, result.unit, result.refRange,
            result.labNumber, result.sentDate, result.reportedDate,
            result.orderDate).joinToString("\u001F")
}
