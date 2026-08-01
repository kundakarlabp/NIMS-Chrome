from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt"
GRADLE = ROOT / "mobile/android/app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


main = MAIN.read_text(encoding="utf-8")

main = replace_once(
    main,
    "import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor\n",
    "import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor\n"
    "import org.kundakarlab.nimsfastsummarymobile.data.cache.ParsedReportDiskCache\n",
    "cache import",
)
main = replace_once(
    main,
    "import java.util.concurrent.atomic.AtomicInteger\n",
    "import java.util.concurrent.atomic.AtomicInteger\nimport java.util.concurrent.atomic.AtomicLong\nimport java.util.Collections\n",
    "atomic imports",
)

main = replace_once(
    main,
    "    private val parsedReportCache = ConcurrentHashMap<String, ParsedReport>()\n",
    "    private val parsedReportCache = ConcurrentHashMap<String, ParsedReport>()\n"
    "    private val parsedReportDiskCache by lazy { ParsedReportDiskCache(applicationContext) }\n"
    "    private val reportHttpClient by lazy {\n"
    "        NimsReportHttpClient(\n"
    "            cookieProvider = { url -> CookieManager.getInstance().getCookie(url).orEmpty() },\n"
    "            userAgentProvider = { webViewUserAgent }\n"
    "        )\n"
    "    }\n"
    "    private val fetchSemaphore = Semaphore(6)\n"
    "    private val parseSemaphore = Semaphore(2)\n",
    "pipeline fields",
)

main = replace_once(
    main,
    "        callback(prepared)\n    }\n\n    private fun startFetchParseSummarize",
    "        val optimized = ReportRequestOptimizer.optimize(prepared)\n"
    "        if (optimized.size != prepared.size) {\n"
    "            log(\"Optimized report queue: ${prepared.size} → ${optimized.size} unique reports\")\n"
    "        }\n"
    "        callback(optimized)\n"
    "    }\n\n"
    "    private fun startFetchParseSummarize",
    "request optimization",
)

bulk_start = """                val cultureRequests = prepared.filter { request ->
                    val tags = request.row.optJSONArray(\"report_tags\")?.toString().orEmpty()
                    tags.contains(\"culture\", ignoreCase = true)
                }
                val otherRequests = prepared.filterNot { it in cultureRequests }
                val completed = AtomicInteger(0)
                val markCompleted = {
                    val done = completed.incrementAndGet()
                    setState(AppState.FETCHING, \"Processing $done/${prepared.size} reports in parallel…\")
                }
                val (cultureReports, otherReports) = coroutineScope {
                    // Reserve more capacity for microbiology while laboratory
                    // reports begin immediately instead of waiting for every
                    // culture PDF to finish.
                    val cultureJob = async { processBulk(cultureRequests, concurrency = 3, onCompleted = markCompleted) }
                    val laboratoryJob = async { processBulk(otherRequests, concurrency = 3, onCompleted = markCompleted) }
                    val cultures = cultureJob.await()
                    if (cultures.isNotEmpty()) {
                        when (val partial = withContext(Dispatchers.IO) {
                            processingRouter.summarize(cultures, SummaryMode.CULTURES_ONLY)
                        }) {
                            is ProcessingResult.Success -> {
                                val partialJson = partial.value.helperJson ?: localSummaryJson(cultures, partial.value.text)
                                uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(partialJson, physicianNote)
                                selectedTab = 3
                                setState(AppState.FETCHING, \"Cultures ready; laboratory trends are still processing…\")
                            }
                            else -> Unit
                        }
                    }
                    cultures to laboratoryJob.await()
                }
                cultureReports + otherReports
"""

bulk_new = """                val queue = ReportRequestOptimizer.optimize(prepared)
                val completed = AtomicInteger(0)
                val lastProgressUpdate = AtomicLong(0L)
                val cultureRequestCount = queue.count(ReportRequestOptimizer::isCulture)
                val culturesRemaining = AtomicInteger(cultureRequestCount)
                val completedCultures = Collections.synchronizedList(mutableListOf<ParsedReport>())
                processBulk(queue) { request, parsed ->
                    if (ReportRequestOptimizer.isCulture(request)) {
                        if (parsed.cultures.isNotEmpty()) completedCultures.add(parsed)
                        if (culturesRemaining.decrementAndGet() == 0 && completedCultures.isNotEmpty()) {
                            val culturesSnapshot = synchronized(completedCultures) { completedCultures.toList() }
                            when (val partial = withContext(Dispatchers.IO) {
                                processingRouter.summarize(culturesSnapshot, SummaryMode.CULTURES_ONLY)
                            }) {
                                is ProcessingResult.Success -> {
                                    val partialJson = partial.value.helperJson
                                        ?: localSummaryJson(culturesSnapshot, partial.value.text)
                                    uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(partialJson, physicianNote)
                                    selectedTab = 3
                                    setState(AppState.FETCHING, \"Cultures ready; laboratory trends are still processing…\")
                                }
                                else -> Unit
                            }
                        }
                    }
                    val done = completed.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val previous = lastProgressUpdate.get()
                    if (done == queue.size || now - previous >= 250L) {
                        if (lastProgressUpdate.compareAndSet(previous, now) || done == queue.size) {
                            setState(AppState.FETCHING, \"Processing $done/${queue.size} reports…\")
                        }
                    }
                }
"""
main = replace_once(main, bulk_start, bulk_new, "single bounded queue")

old_process = """    private suspend fun processBulk(
        prepared: List<PreparedReportRequest>,
        concurrency: Int,
        onCompleted: () -> Unit
    ): List<ParsedReport> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, 4))
        prepared.mapIndexed { index, request ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    try {
                        parsedReportCache[request.reportId]
                            ?.also { log(\"Reusing an in-memory parsed report\") }
                            ?: validatedReportCache
                                ?.takeIf { it.first == request.reportId }
                                ?.second
                                ?.also { log(\"Reusing the report parsed during validation\") }
                            ?: fetchAndParseOne(request, index, prepared.size).also { parsed ->
                                if (parsed.labs.isNotEmpty() || parsed.cultures.isNotEmpty()) {
                                    parsedReportCache[request.reportId] = parsed
                                }
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        errorParsedReport(request.row, error.message ?: \"Report failed\")
                    } finally {
                        onCompleted()
                    }
                }
            }
        }.awaitAll()
    }
"""

new_process = """    private suspend fun processBulk(
        prepared: List<PreparedReportRequest>,
        onCompleted: suspend (PreparedReportRequest, ParsedReport) -> Unit
    ): List<ParsedReport> = coroutineScope {
        prepared.mapIndexed { index, request ->
            async(Dispatchers.IO) {
                ensureActive()
                val parsed = try {
                    parsedReportCache[request.reportId]
                        ?.also { log(\"Cache hit: memory\") }
                        ?: validatedReportCache
                            ?.takeIf { it.first == request.reportId }
                            ?.second
                            ?.also { log(\"Cache hit: validation report\") }
                        ?: parsedReportDiskCache.get(request.reportId)
                            ?.also {
                                parsedReportCache[request.reportId] = it
                                log(\"Cache hit: disk\")
                            }
                        ?: fetchAndParseOne(request, index, prepared.size).also { result ->
                            if (result.labs.isNotEmpty() || result.cultures.isNotEmpty()) {
                                parsedReportCache[request.reportId] = result
                                parsedReportDiskCache.put(result)
                            }
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errorParsedReport(request.row, error.message ?: \"Report failed\")
                }
                onCompleted(request, parsed)
                parsed
            }
        }.awaitAll()
    }
"""
main = replace_once(main, old_process, new_process, "bounded staged processing")

old_fetch_parse = """        val response = fetchWithWebViewCookies(url)
        val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
        if (classification == \"html_login_or_session\") throw IllegalStateException(\"NIMS session appears expired. Login again in the WebView.\")
        if (classification !in setOf(\"pdf_report\", \"html_report_content\")) throw IllegalStateException(\"Report fetch returned $classification\")
        val input = ReportInput(
            reportId = request.reportId,
            reportName = row.optString(\"report_name\"),
            dateSent = row.optString(\"date_sent\"),
            reportType = row.optString(\"report_type\", \"other\"),
            contentType = contentType(response.contentType),
            bytes = response.bytes,
            safeSource = NimsUrlPolicy.safeSourceForHelper(url)
        )
        log(\"Parsing report ${index + 1}/$total\")
        return when (val parsed = processingRouter.parse(input)) {
            is ProcessingResult.Success -> parsed.value.copy(warnings = (parsed.value.warnings + parsed.warnings).distinct())
            is ProcessingResult.Unsupported -> errorParsedReport(row, parsed.reason)
            is ProcessingResult.Failure -> errorParsedReport(row, parsed.userMessage)
        }
"""

new_fetch_parse = """        val response = fetchSemaphore.withPermit { fetchWithWebViewCookies(url) }
        val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
        if (classification == \"html_login_or_session\") throw IllegalStateException(\"NIMS session appears expired. Login again in the WebView.\")
        if (classification !in setOf(\"pdf_report\", \"html_report_content\")) throw IllegalStateException(\"Report fetch returned $classification\")
        val input = ReportInput(
            reportId = request.reportId,
            reportName = row.optString(\"report_name\"),
            dateSent = row.optString(\"date_sent\"),
            reportType = row.optString(\"report_type\", \"other\"),
            contentType = contentType(response.contentType),
            bytes = response.bytes,
            safeSource = NimsUrlPolicy.safeSourceForHelper(url)
        )
        log(\"Parsing report ${index + 1}/$total\")
        return parseSemaphore.withPermit {
            when (val parsed = processingRouter.parse(input)) {
                is ProcessingResult.Success -> parsed.value.copy(warnings = (parsed.value.warnings + parsed.warnings).distinct())
                is ProcessingResult.Unsupported -> errorParsedReport(row, parsed.reason)
                is ProcessingResult.Failure -> errorParsedReport(row, parsed.userMessage)
            }
        }
"""
main = replace_once(main, old_fetch_parse, new_fetch_parse, "fetch parse semaphores")

fetch_start = main.index("    private fun fetchWithWebViewCookies(url: String): ReportFetchResult {")
fetch_end = main.index("\n    private fun contentType", fetch_start)
main = (
    main[:fetch_start]
    + "    private fun fetchWithWebViewCookies(url: String): ReportFetchResult =\n"
      "        reportHttpClient.fetch(url, MAX_FETCHED_REPORT_BYTES)\n"
    + main[fetch_end:]
)

main = replace_once(
    main,
    "        parsedReportCache.clear()\n        setState(AppState.HELPER_READY, \"Results cleared.\")",
    "        parsedReportCache.clear()\n        parsedReportDiskCache.clear()\n        setState(AppState.HELPER_READY, \"Results cleared.\")",
    "clear persistent cache",
)

MAIN.write_text(main, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
if 'com.squareup.okhttp3:okhttp' not in gradle:
    gradle = replace_once(
        gradle,
        '    implementation("com.tom-roush:pdfbox-android:2.0.27.0")\n',
        '    implementation("com.tom-roush:pdfbox-android:2.0.27.0")\n'
        '    implementation("com.squareup.okhttp3:okhttp:4.12.0")\n',
        "okhttp dependency",
    )
GRADLE.write_text(gradle, encoding="utf-8")

write(
    ROOT / "mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/ReportRequestOptimizer.kt",
    r'''package org.kundakarlab.nimsfastsummarymobile

import org.kundakarlab.nimsfastsummarymobile.data.processing.DateNormalizer

/** Pure queue optimizer: one deterministic, clinically prioritized, deduplicated run. */
object ReportRequestOptimizer {
    fun optimize(requests: List<PreparedReportRequest>): List<PreparedReportRequest> {
        val bestByEpisode = linkedMapOf<String, PreparedReportRequest>()
        requests.forEach { request ->
            val key = episodeKey(request)
            val existing = bestByEpisode[key]
            if (existing == null || stageRank(request) > stageRank(existing)) bestByEpisode[key] = request
        }
        return bestByEpisode.values.sortedWith(
            compareBy<PreparedReportRequest> { clinicalPriority(it) }
                .thenByDescending { DateNormalizer.normalize(it.row.optString("date_sent")).sortEpoch ?: Long.MIN_VALUE }
                .thenBy { it.reportId }
        )
    }

    fun isCulture(request: PreparedReportRequest): Boolean {
        val tags = request.row.optJSONArray("report_tags")?.toString().orEmpty()
        val text = listOf(
            tags,
            request.row.optString("report_type"),
            request.row.optString("report_name"),
            request.row.optString("department")
        ).joinToString(" ")
        return listOf("culture", "microbiology", "bacteriology", "fungal").any { text.contains(it, true) }
    }

    private fun episodeKey(request: PreparedReportRequest): String {
        val row = request.row
        val stableLabId = sequenceOf(
            "lab_study_number", "lab_no", "study_no", "culture_no", "requisition_no", "report_id"
        ).map { row.optString(it).trim() }.firstOrNull(String::isNotBlank)
        return if (stableLabId != null) {
            listOf(stableLabId, row.optString("report_name"), row.optString("date_sent")).joinToString("|").lowercase()
        } else {
            request.reportId
        }
    }

    private fun stageRank(request: PreparedReportRequest): Int {
        val text = listOf(
            request.row.optString("report_stage"),
            request.row.optString("report_name"),
            request.row.optString("status")
        ).joinToString(" ")
        return when {
            text.contains("final", true) -> 3
            text.contains("48", true) && text.contains("prelim", true) -> 2
            text.contains("prelim", true) || text.contains("interim", true) -> 1
            else -> 0
        }
    }

    private fun clinicalPriority(request: PreparedReportRequest): Int {
        if (isCulture(request)) return 0
        val text = listOf(
            request.row.optString("report_name"),
            request.row.optString("report_type"),
            request.row.optString("department")
        ).joinToString(" ").lowercase()
        return when {
            listOf("cbc", "hemogram", "haemogram", "crp", "esr", "procalcitonin").any(text::contains) -> 1
            listOf("renal", "kidney", "creatinine", "electrolyte", "liver", "hepatic").any(text::contains) -> 2
            listOf("urine", "cue", "urinalysis").any(text::contains) -> 3
            else -> 4
        }
    }
}
''',
)

write(
    ROOT / "mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/NimsReportHttpClient.kt",
    r'''package org.kundakarlab.nimsfastsummarymobile

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import java.util.concurrent.TimeUnit

/** Shared authenticated client with connection pooling and bounded per-host requests. */
class NimsReportHttpClient(
    private val cookieProvider: (String) -> String,
    private val userAgentProvider: () -> String
) {
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 6
            maxRequestsPerHost = 6
        })
        .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun fetch(url: String, maxBytes: Int): ReportFetchResult {
        if (!NimsReportTemplate.isAllowedNimsUrl(url)) throw IllegalStateException("NIMS report URL is not allowed")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookieProvider(url))
            .header("User-Agent", userAgentProvider())
            .header("Accept", "application/pdf,text/html,text/plain,*/*")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: throw IllegalStateException("NIMS report response was empty")
            val declaredLength = body.contentLength()
            if (declaredLength > maxBytes) throw IllegalStateException("Report response exceeded 25 MB")
            val source = body.source()
            val buffer = okio.Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buffer, 8192)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IllegalStateException("Report response exceeded 25 MB")
            }
            val bytes = buffer.readByteArray()
            val contentType = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("NIMS report fetch returned ${response.code} (${contentType.substringBefore(';')})")
            }
            return ReportFetchResult(
                contentType = contentType,
                statusCode = response.code,
                finalUrlSafe = NimsUrlPolicy.safeSourceForHelper(response.request.url.toString()),
                bytes = bytes
            )
        }
    }
}
''',
)

write(
    ROOT / "mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/data/cache/ParsedReportDiskCache.kt",
    r'''package org.kundakarlab.nimsfastsummarymobile.data.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import java.io.File
import java.security.MessageDigest

class ParsedReportDiskCache(context: Context) {
    private val directory = File(context.cacheDir, "parsed_reports_v3").apply { mkdirs() }

    @Synchronized
    fun get(reportId: String): ParsedReport? {
        val file = fileFor(reportId)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("parser_version") != PARSER_VERSION) {
                file.delete()
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            ParsedReportJsonCodec.decode(root.getJSONObject("report"))
        }.getOrElse {
            file.delete()
            null
        }
    }

    @Synchronized
    fun put(report: ParsedReport) {
        prune()
        val target = fileFor(report.reportId)
        val temp = File(target.parentFile, target.name + ".tmp")
        val root = JSONObject()
            .put("parser_version", PARSER_VERSION)
            .put("report", ParsedReportJsonCodec.encode(report))
        temp.writeText(root.toString())
        if (!temp.renameTo(target)) {
            target.writeText(root.toString())
            temp.delete()
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach(File::delete)
    }

    private fun prune() {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        files.sortedByDescending(File::lastModified).drop(MAX_ENTRIES).forEach(File::delete)
    }

    private fun fileFor(reportId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(reportId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    companion object {
        const val PARSER_VERSION = 3
        private const val MAX_ENTRIES = 256
    }
}

object ParsedReportJsonCodec {
    fun encode(report: ParsedReport): JSONObject = JSONObject()
        .put("report_id", report.reportId)
        .put("report_name", report.reportName)
        .put("date_sent", report.dateSent)
        .put("report_type", report.reportType)
        .put("processor", report.processorName)
        .put("warnings", JSONArray(report.warnings))
        .put("labs", JSONArray().also { array -> report.labs.forEach { array.put(encodeLab(it)) } })
        .put("cultures", JSONArray().also { array -> report.cultures.forEach { array.put(encodeCulture(it)) } })

    fun decode(json: JSONObject): ParsedReport = ParsedReport(
        reportId = json.optString("report_id"),
        reportName = json.optString("report_name"),
        dateSent = json.optString("date_sent"),
        reportType = json.optString("report_type"),
        labs = json.optJSONArray("labs").objects().map(::decodeLab),
        cultures = json.optJSONArray("cultures").objects().map(::decodeCulture),
        warnings = json.optJSONArray("warnings").strings(),
        processorName = json.optString("processor", "disk-cache")
    )

    private fun encodeLab(value: ParsedLabValue): JSONObject = JSONObject()
        .put("code", value.canonicalCode)
        .put("name", value.displayName)
        .put("source", value.sourceName)
        .put("numeric", value.numericValue ?: JSONObject.NULL)
        .put("text", value.textValue ?: JSONObject.NULL)
        .put("unit", value.unit ?: JSONObject.NULL)
        .put("low", value.referenceLow ?: JSONObject.NULL)
        .put("high", value.referenceHigh ?: JSONObject.NULL)
        .put("range", value.referenceRangeText ?: JSONObject.NULL)
        .put("abnormality", value.abnormality.name)
        .put("date", value.resultDate ?: JSONObject.NULL)
        .put("confidence", value.confidence.name)
        .put("comparator", value.comparator.name)

    private fun decodeLab(json: JSONObject) = ParsedLabValue(
        canonicalCode = json.optString("code"),
        displayName = json.optString("name"),
        sourceName = json.optString("source"),
        numericValue = json.optNullableDouble("numeric"),
        textValue = json.optNullableString("text"),
        unit = json.optNullableString("unit"),
        referenceLow = json.optNullableDouble("low"),
        referenceHigh = json.optNullableDouble("high"),
        referenceRangeText = json.optNullableString("range"),
        abnormality = enumValueOr(json.optString("abnormality"), Abnormality.UNKNOWN),
        resultDate = json.optNullableString("date"),
        confidence = enumValueOr(json.optString("confidence"), ParseConfidence.MEDIUM),
        comparator = enumValueOr(json.optString("comparator"), NumericComparator.EQUAL)
    )

    private fun encodeCulture(value: ParsedCultureValue): JSONObject = JSONObject()
        .put("specimen", value.specimen ?: JSONObject.NULL)
        .put("site", value.site ?: JSONObject.NULL)
        .put("collection_date", value.collectionDate ?: JSONObject.NULL)
        .put("organism", value.organism ?: JSONObject.NULL)
        .put("growth_status", value.growthStatus.name)
        .put("markers", JSONArray(value.explicitResistanceMarkers.toList()))
        .put("comments", JSONArray(value.comments))
        .put("confidence", value.confidence.name)
        .put("lab_no", value.labStudyNumber ?: JSONObject.NULL)
        .put("reporting_date", value.reportingDate ?: JSONObject.NULL)
        .put("stage", value.reportStage ?: JSONObject.NULL)
        .put("bottle_name", value.bottleName ?: JSONObject.NULL)
        .put("set_number", value.setNumber ?: JSONObject.NULL)
        .put("bottle_number", value.bottleNumber ?: JSONObject.NULL)
        .put("isolate_number", value.isolateNumber ?: JSONObject.NULL)
        .put("gram", value.gramStain ?: JSONObject.NULL)
        .put("organism_raw", value.organismRaw ?: JSONObject.NULL)
        .put("susceptibility", JSONArray().also { array ->
            value.susceptibility.forEach { result ->
                array.put(JSONObject()
                    .put("antibiotic", result.antibiotic)
                    .put("interpretation", result.interpretation)
                    .put("confidence", result.confidence.name)
                    .put("mic", result.micValue ?: JSONObject.NULL)
                    .put("mic_comparator", result.micComparator ?: JSONObject.NULL)
                    .put("mic_unit", result.micUnit ?: JSONObject.NULL))
            }
        })

    private fun decodeCulture(json: JSONObject) = ParsedCultureValue(
        specimen = json.optNullableString("specimen"),
        site = json.optNullableString("site"),
        collectionDate = json.optNullableString("collection_date"),
        organism = json.optNullableString("organism"),
        growthStatus = enumValueOr(json.optString("growth_status"), GrowthStatus.UNKNOWN),
        susceptibility = json.optJSONArray("susceptibility").objects().map { result ->
            AntibioticResult(
                antibiotic = result.optString("antibiotic"),
                interpretation = result.optString("interpretation"),
                confidence = enumValueOr(result.optString("confidence"), ParseConfidence.MEDIUM),
                micValue = result.optNullableDouble("mic"),
                micComparator = result.optNullableString("mic_comparator"),
                micUnit = result.optNullableString("mic_unit")
            )
        },
        explicitResistanceMarkers = json.optJSONArray("markers").strings().toSet(),
        comments = json.optJSONArray("comments").strings(),
        confidence = enumValueOr(json.optString("confidence"), ParseConfidence.MEDIUM),
        labStudyNumber = json.optNullableString("lab_no"),
        reportingDate = json.optNullableString("reporting_date"),
        reportStage = json.optNullableString("stage"),
        bottleName = json.optNullableString("bottle_name"),
        setNumber = json.optNullableInt("set_number"),
        bottleNumber = json.optNullableInt("bottle_number"),
        isolateNumber = json.optNullableInt("isolate_number"),
        gramStain = json.optNullableString("gram"),
        organismRaw = json.optNullableString("organism_raw")
    )

    private inline fun <reified T : Enum<T>> enumValueOr(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun JSONObject.optNullableString(name: String): String? =
        takeUnless { isNull(name) }?.optString(name)?.takeIf(String::isNotBlank)

    private fun JSONObject.optNullableDouble(name: String): Double? =
        takeUnless { isNull(name) }?.optDouble(name)?.takeUnless(Double::isNaN)

    private fun JSONObject.optNullableInt(name: String): Int? =
        takeUnless { isNull(name) }?.optInt(name)
}
''',
)

write(
    ROOT / "mobile/android/app/src/test/java/org/kundakarlab/nimsfastsummarymobile/ReportRequestOptimizerTest.kt",
    r'''package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRequestOptimizerTest {
    @Test fun prioritizesCultureThenKeyLabsAndDeduplicatesFinalStage() {
        val preliminary = request("a", "Blood Culture preliminary", "01-Aug-2026", "LAB-1", "preliminary", culture = true)
        val final = request("b", "Blood Culture final", "01-Aug-2026", "LAB-1", "final", culture = true)
        val renal = request("c", "Renal function test", "01-Aug-2026", "LAB-2", "final")
        val cbc = request("d", "Complete hemogram", "01-Aug-2026", "LAB-3", "final")

        val optimized = ReportRequestOptimizer.optimize(listOf(renal, preliminary, cbc, final))

        assertEquals(3, optimized.size)
        assertEquals("b", optimized.first().reportId)
        assertEquals("d", optimized[1].reportId)
        assertTrue(ReportRequestOptimizer.isCulture(optimized.first()))
    }

    private fun request(
        id: String,
        name: String,
        date: String,
        labNo: String,
        stage: String,
        culture: Boolean = false
    ): PreparedReportRequest {
        val row = JSONObject()
            .put("report_name", name)
            .put("date_sent", date)
            .put("lab_study_number", labNo)
            .put("report_stage", stage)
            .put("report_tags", JSONArray().put(if (culture) "culture" else "lab"))
        return PreparedReportRequest(row, "$id.pdf", "https://www.nimsts.edu.in/$id.pdf", id)
    }
}
''',
)

write(
    ROOT / "mobile/android/app/src/test/java/org/kundakarlab/nimsfastsummarymobile/data/cache/ParsedReportJsonCodecTest.kt",
    r'''package org.kundakarlab.nimsfastsummarymobile.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class ParsedReportJsonCodecTest {
    @Test fun roundTripsLabsAndCultureOrganism() {
        val report = ParsedReport(
            reportId = "r1",
            reportName = "CBC and culture",
            dateSent = "01-Aug-2026",
            reportType = "culture",
            labs = listOf(ParsedLabValue("WBC", "WBC/TLC", "TLC", 12400.0, null, "/cumm", null, null, null, Abnormality.HIGH, "01-Aug-2026", ParseConfidence.HIGH)),
            cultures = listOf(ParsedCultureValue("Blood", null, "01-Aug-2026", "Klebsiella pneumoniae", GrowthStatus.GROWTH_DETECTED, emptyList(), emptySet(), emptyList(), ParseConfidence.HIGH)),
            processorName = "test"
        )
        val decoded = ParsedReportJsonCodec.decode(ParsedReportJsonCodec.encode(report))
        assertEquals(12400.0, decoded.labs.first().numericValue!!, 0.01)
        assertEquals("Klebsiella pneumoniae", decoded.cultures.first().organism)
        assertTrue(decoded.cultures.first().growthStatus == GrowthStatus.GROWTH_DETECTED)
    }
}
''',
)

print("Fast report pipeline migration applied")
