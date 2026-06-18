package org.kundakarlab.nimsfastsummarymobile.data.processing

object CanonicalLabCodes {
    private val aliases = mapOf(
        "hb" to "HB", "hemoglobin" to "HB", "haemoglobin" to "HB",
        "wbc" to "WBC", "tlc" to "WBC", "total wbc" to "WBC", "total leucocyte count" to "WBC", "total leukocyte count" to "WBC",
        "platelet" to "PLT", "platelets" to "PLT", "platelet count" to "PLT",
        "creatinine" to "CREAT", "serum creatinine" to "CREAT",
        "sodium" to "NA", "serum sodium" to "NA", "potassium" to "K", "serum potassium" to "K",
        "total bilirubin" to "TBIL", "direct bilirubin" to "DBIL",
        "ast" to "AST", "sgot" to "AST", "ast sgot" to "AST", "alt" to "ALT", "sgpt" to "ALT", "alt sgpt" to "ALT",
        "alkaline phosphatase" to "ALP", "alp" to "ALP", "albumin" to "ALB",
        "crp" to "CRP", "c reactive protein" to "CRP", "procalcitonin" to "PCT", "pct" to "PCT",
        "pt" to "PT", "prothrombin time" to "PT", "inr" to "INR"
    )
    fun normalize(value: String): String {
        val cleaned = value.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return aliases[cleaned] ?: cleaned.uppercase().replace(" ", "_").ifBlank { "UNKNOWN" }
    }
}
