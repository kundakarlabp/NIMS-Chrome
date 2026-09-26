package org.kundakarlab.nimsfastsummarymobile

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class BackgroundReportRow(
    val reportId: String,
    val token: String,
    val reportName: String,
    val dateSent: String,
    val reportType: String
)

data class BackgroundReportList(
    val patientName: String,
    val returnedCrNo: String,
    val age: String,
    val sex: String,
    val rows: List<BackgroundReportRow>
)

object NimsBackgroundReportListParser {
    private val printReport = Regex("""printReport\s*\(\s*(['"])([^'"]+)\1\s*\)""", RegexOption.IGNORE_CASE)
    private val datePattern = Regex("""\b(\d{1,2}[-/](?:\d{1,2}|[A-Za-z]{3})[-/]\d{2,4})\b""")
    private val patientNamePattern = Regex(
        """(?:Patient\s*Name|Name\s*of\s*Patient|Pat(?:ient)?\s*Name)\s*[:\-]?\s*([A-Za-z][A-Za-z .'-]{2,80}?)(?=\s+(?:Age|Sex|Gender|CR\s*(?:No|Number))\b|$)""",
        RegexOption.IGNORE_CASE
    )
    private val crPattern = Regex(
        """(?:CR\s*(?:No|Number)|Patient\s*CR)\s*[:\-]?\s*(\d{15})""",
        RegexOption.IGNORE_CASE
    )
    private val agePattern = Regex("""\bAge\s*[:\-]?\s*(\d{1,3}\s*(?:Y|Yr|Yrs|Years)?)""", RegexOption.IGNORE_CASE)
    private val sexPattern = Regex("""\b(?:Sex|Gender)\s*[:\-]?\s*(Male|Female|M|F)\b""", RegexOption.IGNORE_CASE)

    fun parse(html: String): BackgroundReportList {
        val doc = Jsoup.parse(html)
        val rows = buildList {
            doc.select("tr").forEach { tr ->
                val clickable = tr.select("[onclick]").firstOrNull { node ->
                    printReport.containsMatchIn(node.attr("onclick"))
                } ?: return@forEach
                val token = printReport.find(clickable.attr("onclick"))?.groupValues?.getOrNull(2)?.trim().orEmpty()
                if (!NimsReportTemplate.isSafeTransientReportArg(token)) return@forEach

                val cells = tr.select("td,th").map { clean(it.text()) }.filter { it.isNotBlank() }
                val date = cells.firstNotNullOfOrNull { datePattern.find(it)?.groupValues?.getOrNull(1) }.orEmpty()
                val name = bestReportName(cells, clickable)
                if (name.isBlank()) return@forEach
                add(
                    BackgroundReportRow(
                        reportId = stableReportId(token),
                        token = token,
                        reportName = name.take(180),
                        dateSent = date,
                        reportType = inferType(name, cells.joinToString(" "))
                    )
                )
            }
        }.distinctBy { it.token }

        val bodyText = clean(doc.body()?.text().orEmpty())
        val patient = patientNamePattern.find(bodyText)?.groupValues?.getOrNull(1)
            ?.replace(Regex("\\s{2,}"), " ")
            ?.trim()
            ?.take(100)
            .orEmpty()

        val returnedCr = doc.selectFirst("input[name=patCrNo],input#patCrNo")
            ?.attr("value")
            ?.filter(Char::isDigit)
            ?.takeIf { it.length == 15 }
            ?: crPattern.find(bodyText)?.groupValues?.getOrNull(1).orEmpty()
        val age = agePattern.find(bodyText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val sex = sexPattern.find(bodyText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return BackgroundReportList(patientName = patient, returnedCrNo = returnedCr, age = age, sex = sex, rows = rows)
    }

    fun looksLikeLoginOrExpired(html: String, finalUrl: String = ""): Boolean {
        val text = Jsoup.parse(html).text().lowercase()
        val url = finalUrl.lowercase()
        return "loginlogin.action" in url ||
            "session expired" in text ||
            "please login again" in text ||
            ("captcha" in text && ("password" in text || "user id" in text || "username" in text))
    }

    private fun bestReportName(cells: List<String>, clickable: Element): String {
        val actionText = clean(clickable.text())
        return cells
            .asSequence()
            .filterNot { it == actionText }
            .filterNot { datePattern.containsMatchIn(it) }
            .filterNot { it.equals("view report", true) || it.equals("view", true) || it.matches(Regex("""\d+""")) }
            .maxByOrNull { candidate ->
                val letters = candidate.count(Char::isLetter)
                letters * 4 - candidate.length.coerceAtMost(80)
            }
            .orEmpty()
    }

    private fun inferType(name: String, all: String): String {
        val text = "$name $all".lowercase()
        return when {
            listOf("culture", "bactec", "microbiology", "sensitivity", "gram stain", "fungal").any(text::contains) -> "culture"
            listOf("histopath", "biopsy", "cytology").any(text::contains) -> "pathology"
            else -> "lab"
        }
    }

    private fun clean(value: String): String = value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun stableReportId(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
