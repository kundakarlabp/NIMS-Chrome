package org.kundakarlab.nimsfastsummarymobile.data.processing

object CanonicalLabCodes {
    private val aliases = mapOf(
        "hb" to "HB", "hemoglobin" to "HB", "haemoglobin" to "HB",
        "wbc" to "WBC", "tlc" to "WBC", "total wbc" to "WBC", "total leucocyte count" to "WBC", "total leukocyte count" to "WBC",
        "platelet" to "PLT", "platelets" to "PLT", "platelet count" to "PLT",
        "creatinine" to "CREAT", "serum creatinine" to "CREAT",
        "sodium" to "NA", "serum sodium" to "NA", "potassium" to "K", "serum potassium" to "K",
        "glucose" to "GLUCOSE", "serum glucose" to "GLUCOSE", "blood glucose" to "GLUCOSE",
        "rbs" to "GLUCOSE", "grbs" to "GLUCOSE", "random blood glucose" to "GLUCOSE", "random blood sugar" to "GLUCOSE", "random plasma glucose" to "GLUCOSE",
        "total bilirubin" to "TBIL", "serum bilirubin total" to "TBIL", "bilirubin total" to "TBIL", "direct bilirubin" to "DBIL",
        "ast" to "AST", "sgot" to "AST", "ast sgot" to "AST", "alt" to "ALT", "sgpt" to "ALT", "alt sgpt" to "ALT",
        "alkaline phosphatase" to "ALP", "alp" to "ALP", "albumin" to "ALB",
        "crp" to "CRP", "c reactive protein" to "CRP",
        "hscrp" to "HSCRP", "hs crp" to "HSCRP", "high sensitivity crp" to "HSCRP", "high sensitivity c reactive protein" to "HSCRP",
        "procalcitonin" to "PCT", "pct" to "PCT",
        "esr" to "ESR", "erythrocyte sedimentation rate" to "ESR",
        "gm" to "GM", "gm index" to "GM", "galactomannan" to "GM", "galactomannan index" to "GM", "aspergillus galactomannan" to "GM",
        "beta d glucan" to "BDG", "1 3 beta d glucan" to "BDG", "bdg" to "BDG",
        "pt" to "PT", "prothrombin time" to "PT", "inr" to "INR"
    )

    fun normalize(value: String): String {
        val cleaned = value.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return aliases[cleaned] ?: cleaned.uppercase().replace(" ", "_").ifBlank { "UNKNOWN" }
    }
}
