package org.kundakarlab.nimsresults

/** A source-aware clinical report. No date or accession can be inferred from
 * the REST reportList: it contains only test name, request number and date. */
data class FullClinicalFinding(val parameter: String, val value: String,
    val unit: String = "", val reference: String = "",
    val unresolved: Boolean = false, val selectionKey: String = "")

data class FullClinicalStudy(
    val title: String, val sourceDate: String,
    val dateKind: String, val accession: String,
    val collectedAt: String, val sentAt: String, val reportedAt: String,
    val specimen: String, val evidence: String, val originalUrl: String,
    val findings: List<FullClinicalFinding>, val conflictingCulture: Boolean
)

data class FullClinicalSource(
    val title:String, val labNumber:String, val collectedAt:String,
    val reportedAt:String, val specimen:String, val stage:String,
    val evidence:String
)

/** Device-local dataset shared by CSV and full PDF. All unverified fields are
 * visibly labelled; original HIS results and candidate URLs are unchanged. */
object FullClinicalResultsDocument {
    private fun single(values: List<String>): String =
        values.map(String::trim).filter(String::isNotBlank)
            .distinct().singleOrNull().orEmpty()

    fun build(bundle: ClinicalBundle): List<FullClinicalStudy> {
        val out=mutableListOf<FullClinicalStudy>()
        for(day in ClinicalChronology.days(bundle.results)) for(study in day.studies) {
            val rows=study.rows
            val fromRowsLab=single(rows.map { it.labNumber.ifBlank { it.accessionNumber } })
            val fromRowsCol=single(rows.map { it.collectionDateTime })
            val fromRowsSent=single(rows.map { it.sentDate })
            val fromRowsRep=single(rows.map { it.reportedDate })
            val fromRowsSpecimen=single(rows.map { it.specimenType })
            val isMicro=ClinicalReportRenderer.isMicrobiologyInvestigation(study.title)
            val candidates=SourceMetadataReview.pdfCandidates(rows,bundle.reports)
            val verified=if(isMicro)
                MicrobiologyPdfIdentity.unique(rows,candidates,bundle.patient.crNo,study.date)
                else null
            val header=verified?.header
            // If a structured value and a PDF disagree, retain the structured
            // value and do not present conflicting PDF provenance as certain.
            fun agreed(explicit: String, observed: String):String =
                if(explicit.isNotBlank())explicit
                else observed
            val lab=agreed(fromRowsLab,header?.labNumber.orEmpty())
            val col=agreed(fromRowsCol,header?.collectionDate.orEmpty())
            val sent=agreed(fromRowsSent,header?.sentDate.orEmpty())
            val rep=agreed(fromRowsRep,header?.reportedDate.orEmpty())
            val specimen=agreed(fromRowsSpecimen,verified?.specimen.orEmpty())
            val readable=ClinicalResultsLayout.ordered(study.title,
                ClinicalChronology.inlineRows(study)).map { row ->
                FullClinicalFinding(ClinicalChronology.parameterLabel(row),
                    row.valueText,row.unit,row.refRange,selectionKey=
                        row.test.trim().lowercase()+"|"+row.parameter.trim().lowercase())
            }
            val unresolved=ClinicalChronology.unresolvedRows(study)
                .filter { row -> readable.none { it.value==row.valueText &&
                    it.parameter==ClinicalChronology.parameterLabel(row) } }
                .map { row -> FullClinicalFinding(row.parameter,row.valueText,
                    row.unit,row.refRange,unresolved=true,selectionKey=
                        row.test.trim().lowercase()+"|"+row.parameter.trim().lowercase()) }
            val findings=readable+unresolved
            out+=FullClinicalStudy(
                title=if(isMicro)MicrobiologyPdfIdentity.displayTitle(study.title,rows)
                    else study.title,
                sourceDate=if(study.date==java.time.LocalDate.MIN)""
                    else study.date.toString(),
                dateKind=if(study.reportedIsExplicit)"Explicit reported date"
                    else "Structured result date (not collection/report proof)",
                accession=lab,collectedAt=col,sentAt=sent,reportedAt=rep,
                specimen=specimen,
                evidence=when {
                    verified!=null->"CR-verified source PDF and investigation match"
                    listOf(fromRowsLab,fromRowsCol,fromRowsRep).any(String::isNotBlank)->
                        "Explicit structured metadata; PDF not uniquely verified"
                    else ->"Source metadata unresolved; do not infer from date/REST catalogue"
                },originalUrl=verified?.report?.url.orEmpty(),
                findings=findings,
                conflictingCulture=(verified?.discordant==true) ||
                    rows.any { MicrobiologyPdfIdentity.contradictoryCulture(it.valueText) }
            )
        }
        return out
    }

    /** Source-index appendix is never folded into a patient result.
     * Source reportList REST rows only offer title/date and are omitted
     * from the lab-ID list to avoid duplicate metadata-only events.
     */
    fun sourceInventory(bundle:ClinicalBundle):List<FullClinicalSource> {
        return bundle.reports.asSequence()
            .filter { !it.sourceTag.startsWith("nims_rest_reportList") }
            .distinctBy { it.url.ifBlank { it.id } }
            .map { report ->
                val checked=ReportTitleCache.header(report.url)
                    ?.takeIf { it.crNo==bundle.patient.crNo &&
                        (report.labNumber.isBlank() ||
                            it.labNumber.equals(report.labNumber,true)) }
                FullClinicalSource(
                    title=report.title,
                    labNumber=report.labNumber.ifBlank { checked?.labNumber.orEmpty() },
                    collectedAt=report.collectionDateTime
                        .ifBlank { checked?.collectionDate.orEmpty() },
                    reportedAt=checked?.reportedDate.orEmpty()
                        .ifBlank { report.reportedDate },
                    specimen=checked?.let {
                        PdfSourceEvidence.specimen(
                            ReportTitleCache.extracted(report.url).orEmpty())
                    }.orEmpty(),
                    stage=report.reportStage.ifBlank {
                        checked?.let {
                            PdfSourceEvidence.stage(
                                ReportTitleCache.extracted(report.url).orEmpty())
                        }.orEmpty()
                    },
                    evidence=if(checked!=null)"PDF CR verified; NOT linked to a result"
                        else "Source-index metadata only; NOT linked to a result")
            }.toList()
    }

    /** Pure model for CSV tests and for the PDF preview, no network calls. */
    fun csv(studies: List<FullClinicalStudy>,
            selected: Set<String>? = null): String {
        fun quote(value: String):String="\""+value.replace("\"","\"\"")+"\""
        val columns=listOf("Result date","Date evidence","Test","Parameter",
            "Value","Unit","Reference range","Lab/Study ID","Collection date/time",
            "Sent date/time","Reported date/time","Specimen","Source status",
            "Contradictory culture","Unmapped LIS field")
        val lines=studies.flatMap { study ->
            (if(selected==null)
                study.findings.ifEmpty {
                    listOf(FullClinicalFinding("Result","Not available")) }
                else study.findings.filter { it.selectionKey in selected })
                .map { item ->
                    listOf(study.sourceDate,study.dateKind,study.title,
                        item.parameter,item.value,item.unit,item.reference,
                        study.accession,study.collectedAt,study.sentAt,study.reportedAt,
                        study.specimen,study.evidence,
                        study.conflictingCulture.toString(),item.unresolved.toString())
                        .joinToString(",") { quote(it) }
                }
        }
        return (listOf(columns.joinToString(",") { quote(it) })+lines)
            .joinToString("\r\n",postfix="\r\n")
    }
}
