package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject

object DiagnosePageContract {
    fun viewReportRows(json: JSONObject): Int = json.optInt("viewReportRows")
    fun blockedFrames(json: JSONObject): Int = json.optInt("blockedFrames")
    fun reachableDocuments(json: JSONObject): Int = json.optInt("reachableDocuments")
}
