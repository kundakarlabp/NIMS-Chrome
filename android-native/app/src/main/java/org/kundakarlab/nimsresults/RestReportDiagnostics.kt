package org.kundakarlab.nimsresults

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Read-only, PHI-free instrumentation for the actual NIMS REST reportList.
 * Reports source field NAMES and counts, never CRs, values, URLs, tokens,
 * lab numbers, patient names or raw JSON. Does not claim API completeness.
 */
object RestReportDiagnostics {
    data class ArrayCandidate(val path: String, val rows: JSONArray, val score: Int)
    private val metrics = linkedMapOf<String, String>()
    @Synchronized fun reset() { metrics.clear(); metrics["rest.stage"] = "not_checked" }
    @Synchronized fun status(http: Int, bytes: Int, elapsedMs: Long, kind: String) {
        metrics["rest.httpStatus"] = http.toString()
        metrics["rest.responseBytes"] = bytes.toString()
        metrics["rest.elapsedMs"] = elapsedMs.toString()
        metrics["rest.responseType"] = when {
            kind.contains("json",true) -> "json"
            kind.contains("html",true) -> "html"
            kind.contains("text",true) -> "text"
            else -> "other"
        }
    }
    @Synchronized fun stage(value: String) {
        metrics["rest.stage"] = value.take(50)
    }

    @Synchronized fun elapsedMs(): Long = metrics["rest.elapsedMs"]?.toLongOrNull() ?: 0L

    @Synchronized fun snapshot(): String =
        metrics.entries.joinToString("\n") { (k,v) -> "$k=$v" } +
            "\nrest.privacy=no_CR_values_names_tokens_urls_or_lab_numbers"

    private fun canonical(s: String): String =
        s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private val patientKeys = setOf("crno","crnumber","patientcrno","patcrno")
    private val reportKeys = setOf("reportname","investigationname","testname",
        "reportid","reportno","reportnumber","labno","labnumber","labstudyno",
        "sampleno","samplenumber","reporturl","reporttoken","filename",
        "reportdate","collectiondate","reportingdate","requisitionno",
        "specimenno","accessionno","url")
    private val detailKeys = setOf("parametername","resultvalue","result",
        "resulttext","value","observations","results","valuesbydate",
        "referrange","referencerange","unit","units","comments","remark",
        "remarks","organism","isolate","susceptibility","antibiotics",
        "method","reporttext","reportcontent","finding","findings")

    fun candidates(node: Any?): List<ArrayCandidate> {
        val items = mutableListOf<ArrayCandidate>()
        fun visit(value: Any?, path: String, depth: Int) {
            if (depth > 7 || items.size > 100) return
            when (value) {
                is JSONArray -> {
                    val size=value.length()
                    val objRows = (0 until minOf(24,size))
                        .mapNotNull { value.optJSONObject(it) }
                    if (objRows.isNotEmpty()) {
                        val keys = objRows.flatMap { x -> x.keys().asSequence().toList() }
                            .map(::canonical).toSet()
                        val identity = keys.count { it in reportKeys }
                        val details = keys.count { it in detailKeys }
                        val score=identity*5 + details*2 +
                            if (keys.any { it in setOf("testname","reportname","investigationname") }) 8 else 0
                        items += ArrayCandidate(path, value, score)
                    }
                    for (i in 0 until minOf(size,10)) visit(value.opt(i), "$path[]",depth+1)
                }
                is JSONObject -> {
                    val names=value.keys().asSequence().toList()
                    for (key in names.take(100)) visit(value.opt(key),
                        if(path == "$") "$."+key else "$path."+key,depth+1)
                }
            }
        }
        visit(node,"$",0)
        return items
    }

    /** Never prefer a nested one-row metadata array over an actual report list.
     * Equal suitability: prefer the longer array, but no patient-identity
     * authority is inferred from these scores.
     */
    fun bestArray(payload: Any): JSONArray =
        candidates(payload).maxWithOrNull(compareBy<ArrayCandidate> { it.score }
            .thenBy { it.rows.length() })?.rows ?: JSONArray()

    fun inspect(payload: Any, expectedCr: String) {
        val arrays=candidates(payload)
        val selected=arrays.maxWithOrNull(compareBy<ArrayCandidate> { it.score }
            .thenBy { it.rows.length() })
        val objects=selected?.rows?.let { arr ->
            (0 until arr.length()).mapNotNull(arr::optJSONObject)
        }.orEmpty()
        val keys=objects.flatMap { obj -> obj.keys().asSequence().toList() }
        val names=keys.map(::canonical).toSet()
        // Server-controlled property names may themselves encode patient data.
        // Only known schema labels may leave the app in copied diagnostics.
        val allowed = reportKeys + detailKeys + patientKeys +
            setOf("data","reports","patient","patientdetails","status",
                "success","message","items","list","results","result",
                "reportlist","observation","measurement","measurements",
                "labname","department","lab","reportstatus","testcode",
                "referenceinterval","referencevalue","collectiondatetime",
                "reportingdatetime","reportdetails","reportdata")
        val fields=names.filter { it in allowed }.sorted().take(75)
        val rootKeys=(payload as? JSONObject)?.keys()?.asSequence()?.toList()
            ?.map(::canonical)?.filter { it in allowed }
            ?.sorted()?.take(35).orEmpty()
        val present = listOf("labno","labnumber","labstudyno","sampleno",
            "samplenumber","requisitionno","reportnumber","reportno",
            "collectiondate","reportingdate","validateddate","specimentype",
            "reporturl","filename","reporttoken")
            .filter { it in names }
        val possibleDetailed=objects.count { obj ->
            val k=obj.keys().asSequence().map(::canonical).toSet()
            k.any { it in setOf("parametername","resultvalue","valuesbydate",
                "reportcontent","reporttext","observations","findings") }
        }
        fun assign(key: String, value: Any) { synchronized(this) { metrics[key]=value.toString() } }
        assign("rest.rootType", if(payload is JSONArray) "array" else "object")
        assign("rest.rootKeys",rootKeys.joinToString(","))
        assign("rest.arrayCount",arrays.size)
        assign("rest.arrayPaths",arrays.take(15).map {
            // Paths from server keys can contain identifiers; print only
            // generic path shapes, NOT raw key names.
            "$"+".array["+it.rows.length()+"]"
        }.joinToString(","))
        assign("rest.candidateRows",selected?.rows?.length() ?: 0)
        assign("rest.candidateObjects",objects.size)
        assign("rest.fieldNames",fields.joinToString(","))
        assign("rest.unrecognizedFieldCount",names.count { it !in allowed })
        assign("rest.identifierFieldsPresent",present.joinToString(","))
        assign("rest.rowsWithLabNo",objects.count { value(obj=it,"labNo","labNumber","labStudyNo","accessionNo") })
        assign("rest.rowsWithSampleNo",objects.count { value(obj=it,"sampleNo","sampleNumber","specimenNo") })
        assign("rest.rowsWithCollectionTime",objects.count { value(obj=it,"collectionDate","sampleCollectionDate") })
        assign("rest.rowsWithSourceReportDate",objects.count {
            value(obj=it,"reportDate")
        })
        assign("rest.rowsWithExplicitReportingTimestamp",objects.count {
            value(obj=it,"reportingDate","reportedDate","reportingDateTime",
                "reportedDateTime")
        })
        assign("rest.rowsWithReportNumber",objects.count { value(obj=it,"reportNo","reportNumber") })
        assign("rest.rowsWithDocumentRef",objects.count { value(obj=it,"fileName","reportUrl","pdfUrl","reportToken") })
        assign("rest.possibleDetailRecords",possibleDetailed)
        val identityFields = listOf("crNo","crNumber","patientCrNo","patCrNo")
        val root=(payload as? JSONObject)
        val patient=root?.optJSONObject("patient") ?: root?.optJSONObject("patientDetails")
            ?: root?.optJSONObject("patient_details")
        val rootCr=listOfNotNull(patient,root).firstNotNullOfOrNull { obj ->
            val normalized=obj.keys().asSequence().associateBy(::canonical)
            identityFields.firstNotNullOfOrNull { k ->
                normalized[canonical(k)]?.let { actual ->
                    obj.optString(actual).takeIf(String::isNotBlank)
                }
            }
        }.orEmpty()
        val rowCrs=objects.mapNotNull { obj ->
            identityFields.firstNotNullOfOrNull { k ->
                obj.optString(k).takeIf(String::isNotBlank)
            }
        }
        assign("rest.crEvidence",when {
            rootCr.isNotBlank() || rowCrs.isNotEmpty() ->
                if ((listOf(rootCr)+rowCrs).filter(String::isNotBlank)
                        .all { it.filter(Char::isDigit)==expectedCr }) "matching"
                else "conflict"
            else -> "not_returned"
        })
    }

    @Synchronized fun binding(acceptedWithoutResponseCr: Boolean) {
        metrics["rest.patientBinding"] = if (acceptedWithoutResponseCr)
            "authenticated_patient_header_context_only" else "response_cr_required"
    }

    private fun value(obj: JSONObject, vararg fields: String): Boolean {
        val names=obj.keys().asSequence().associateBy(::canonical)
        return fields.any { f ->
            val key=names[canonical(f)]
            key != null && !obj.isNull(key) && obj.optString(key).isNotBlank()
        }
    }
}
