package org.kundakarlab.nimsresults

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Last validated structured patient snapshot, not a background HIS synchronizer.
 * AES-GCM using non-exportable Android Keystore key. No source PDFs or cookies.
 * Cache restore never claims to have checked current NIMS completeness.
 */
class ClinicalSnapshotCache(context: Context) {
    private val prefs = context.getSharedPreferences("nims_clinical_cache_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun save(data: ClinicalBundle): Boolean = runCatching {
        require(Regex("""\d{15}""").matches(data.patient.crNo))
        require(data.results.isNotEmpty())
        val root = JSONObject().apply {
            put("version", 3)
            put("storedAt", System.currentTimeMillis())
            put("retrievedAt", data.retrievedAt)
            put("patient", JSONObject().apply {
                put("name", data.patient.name)
                put("crNo", data.patient.crNo)
                put("age", data.patient.age)
                put("sex", data.patient.sex)
            })
            put("results", JSONArray().apply {
                data.results.forEach { row ->
                    put(JSONObject().apply {
                        put("id", row.id); put("test", row.test)
                        put("parameter", row.parameter); put("date", row.date)
                        put("valueText", row.valueText)
                        put("numericValue", row.numericValue ?: JSONObject.NULL)
                        put("unit", row.unit); put("refRange", row.refRange)
                        put("abnormal", row.abnormal); put("sourceKey", row.sourceKey)
                        put("sentDate", row.sentDate); put("reportedDate", row.reportedDate)
                        put("labNumber", row.labNumber); put("orderDate", row.orderDate)
                        put("sourceOrderId", row.sourceOrderId); put("sampleNumber", row.sampleNumber)
                        put("reportNumber", row.reportNumber); put("reportStage", row.reportStage)
                        put("documentReference", row.documentReference)
                        put("packingListNumber", row.packingListNumber)
                        put("sourceRow", row.sourceRow)
                        put("collectionDateTime", row.collectionDateTime)
                        put("receivedDateTime", row.receivedDateTime)
                        put("validationDateTime", row.validationDateTime)
                        put("specimenType", row.specimenType)
                        put("accessionNumber", row.accessionNumber)
                        put("requisitionDateTime", row.requisitionDateTime)
                        put("laboratory", row.laboratory)
                    })
                }
            })
            // Save source-index identifiers separately; never relabel the
            // Custom Sheet result date as a collection/reporting timestamp.
            put("reports", JSONArray().apply {
                data.reports.forEach { report ->
                    put(JSONObject().apply {
                        put("id", report.id); put("title", report.title)
                        put("date", report.date); put("department", report.department)
                        put("url", report.url); put("sentDate", report.sentDate)
                        put("reportedDate", report.reportedDate); put("labNumber", report.labNumber)
                        put("orderDate", report.orderDate); put("sampleNumber", report.sampleNumber)
                        put("reportNumber", report.reportNumber); put("reportStage", report.reportStage)
                        put("sourceOrderId", report.sourceOrderId)
                        put("documentReference", report.documentReference)
                        put("packingListNumber", report.packingListNumber)
                        put("sourceRow", report.sourceRow)
                        put("collectionDateTime", report.collectionDateTime)
                        put("sourceSampleLabText", report.sourceSampleLabText)
                        put("sourceTag", report.sourceTag)
                    })
                }
            })
            put("enquiryCapture", JSONObject(InvestigationEnquiryParser.toJson(
                EnquiryCapture(data.enquiryOrders, data.enquiryCapturedAt)
            )))
            put("rawRows", JSONArray().apply {
                data.rawRows.forEach { source ->
                    put(JSONObject().apply {
                        put("groupIndex", source.groupIndex)
                        put("rowIndex", source.rowIndex)
                        put("investigation", source.investigation)
                        put("sourceJson", source.sourceJson)
                    })
                }
            })
        }
        val raw = root.toString().toByteArray(Charsets.UTF_8)
        val zipped = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        require(zipped.size <= 8_000_000) { "Cache exceeds size cap" }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(zipped)
        prefs.edit().putString("snapshot", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    fun load(): ClinicalBundle? = runCatching {
        val value = prefs.getString("snapshot", null) ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val zipped = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        val raw = GZIPInputStream(ByteArrayInputStream(zipped)).use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val length = stream.read(buffer)
                if (length < 0) break
                total += length
                require(total <= 30_000_000) { "Inflated cache size limit exceeded" }
                output.write(buffer, 0, length)
            }
            output.toString("UTF-8")
        }
        val root = JSONObject(raw)
        require(root.optInt("version") in 1..3)
        val storedAt = root.getLong("storedAt")
        require(System.currentTimeMillis() - storedAt in 0..MAX_AGE_MS)
        val p = root.getJSONObject("patient")
        val cr = p.optString("crNo")
        require(Regex("""\d{15}""").matches(cr))
        val results = root.getJSONArray("results")
        val rows = (0 until results.length()).map { i ->
            val item = results.getJSONObject(i)
            fun field(key: String) = item.optString(key, "")
            LabResult(
                id=field("id"), test=field("test"), parameter=field("parameter"),
                date=field("date"), valueText=field("valueText"),
                numericValue=if(item.isNull("numericValue")) null else
                    item.optDouble("numericValue").takeIf(Double::isFinite),
                unit=field("unit"), refRange=field("refRange"), abnormal=field("abnormal"),
                sourceKey=field("sourceKey"), sentDate=field("sentDate"),
                reportedDate=field("reportedDate"), labNumber=field("labNumber"),
                orderDate=field("orderDate"), sourceOrderId=field("sourceOrderId"),
                sampleNumber=field("sampleNumber"), reportNumber=field("reportNumber"),
                reportStage=field("reportStage"), documentReference=field("documentReference"),
                packingListNumber=field("packingListNumber"), sourceRow=field("sourceRow"),
                collectionDateTime=field("collectionDateTime"),
                receivedDateTime=field("receivedDateTime"),
                validationDateTime=field("validationDateTime"),
                specimenType=field("specimenType"), accessionNumber=field("accessionNumber"),
                requisitionDateTime=field("requisitionDateTime"), laboratory=field("laboratory")
            )
        }
        require(rows.isNotEmpty())
        val sources = root.optJSONArray("rawRows") ?: JSONArray()
        val rawRows = (0 until sources.length()).map { i ->
            val item = sources.getJSONObject(i)
            RawInvestigationRow(item.optInt("groupIndex"), item.optInt("rowIndex"),
                item.optString("investigation"), item.optString("sourceJson"))
        }
        val inventory = root.optJSONArray("reports") ?: JSONArray()
        val reports = (0 until inventory.length()).map { i ->
            val item = inventory.getJSONObject(i)
            fun field(key: String) = item.optString(key, "")
            ReportRef(
                id=field("id"), title=field("title"), date=field("date"),
                department=field("department"), url=field("url"),
                sentDate=field("sentDate"), reportedDate=field("reportedDate"),
                labNumber=field("labNumber"), orderDate=field("orderDate"),
                sampleNumber=field("sampleNumber"), reportNumber=field("reportNumber"),
                reportStage=field("reportStage"), sourceOrderId=field("sourceOrderId"),
                documentReference=field("documentReference"),
                packingListNumber=field("packingListNumber"), sourceRow=field("sourceRow"),
                collectionDateTime=field("collectionDateTime"),
                sourceSampleLabText=field("sourceSampleLabText")
            )
        }
        val enquiry = root.optJSONObject("enquiryCapture")?.let { capture ->
            runCatching {
                val records=capture.optJSONArray("rows") ?: JSONArray()
                if (records.length() == 0) EnquiryCapture(emptyList(),capture.optLong("capturedAt"))
                else InvestigationEnquiryParser.fromJson(capture.toString())
            }.getOrNull()
        }
        ClinicalBundle(
            patient=PatientInfo(p.optString("name"), cr, p.optString("age"), p.optString("sex")),
            results=rows, reports=reports,
            source="nims_encrypted_offline_cache", retrievedAt=root.optLong("retrievedAt"),
            rawRows=rawRows,
            enquiryOrders=enquiry?.rows.orEmpty(),
            enquiryCapturedAt=enquiry?.capturedAt ?: 0L
        )
    }.getOrNull()

    @Synchronized
    fun clear() { prefs.edit().clear().commit() }

    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS,null) as? SecretKey)?.let { return it }
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORM="AES/GCM/NoPadding"
        const val KEY_ALIAS="nims-results-clinical-cache-v1"
        const val MAX_AGE_MS=72L*60L*60L*1000L
    }
}
