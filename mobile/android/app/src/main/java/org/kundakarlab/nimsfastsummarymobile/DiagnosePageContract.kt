package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject

object DiagnosePageContract {
    fun viewReportRows(json: JSONObject): Int = json.optInt("viewReportRows")
}
