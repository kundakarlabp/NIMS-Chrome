package org.kundakarlab.nimsfastsummarymobile

/** Navigation calls that keep the authenticated NIMS shell contract intact. */
object NimsPortalNavigationScripts {
    val openCrModule: String = """
        (function(){
          try{
            if(window.NimsReportCore&&typeof window.NimsReportCore.openCrWiseResultsDirect==='function'){
              return JSON.stringify(window.NimsReportCore.openCrWiseResultsDirect(document));
            }
          }catch(e){}
          return JSON.stringify({ok:false,errorCode:'core_not_ready'});
        })();
    """.trimIndent()
}
