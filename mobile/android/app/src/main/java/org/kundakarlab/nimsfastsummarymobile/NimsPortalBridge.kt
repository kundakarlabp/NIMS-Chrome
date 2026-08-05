package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject

/** JavaScript probes used by the native workflow. No credentials are read. */
object NimsPortalBridge {
    val probeScript: String = """
        (function(){
          function collect(doc,out,seen,depth){
            if(!doc||depth>7||seen.indexOf(doc)>=0)return;
            seen.push(doc);out.push(doc);
            let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
            for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
          }
          function visible(el){
            if(!el||el.disabled)return false;
            try{
              const style=el.ownerDocument.defaultView.getComputedStyle(el);
              if(style.display==='none'||style.visibility==='hidden'||Number(style.opacity)===0)return false;
              const rect=el.getBoundingClientRect();
              return rect.width>0&&rect.height>0;
            }catch(e){return true;}
          }
          function actionText(el){
            return String((el&&((el.innerText||el.textContent||el.value||el.title||el.getAttribute&&el.getAttribute('aria-label'))))||'').replace(/\s+/g,' ').trim();
          }
          function hrefOf(doc){try{return String(doc.location&&doc.location.href||'');}catch(e){return '';}}
          function actions(doc){try{return [...doc.querySelectorAll('a,button,input[type=button],input[type=submit],[role=button]')];}catch(e){return [];}}
          function prepareLogin(docs){
            if(window.__nimsLoginPreparationState==='login_clicked')return 'login_clicked';
            const loginPattern=/^(?:nims\s+)?(?:user\s+|employee\s+|hospital\s+)?(?:login|log\s*in|sign\s*in)$/i;
            for(const d of docs){
              const direct=actions(d).find(el=>{
                const text=actionText(el);
                let href='';try{href=String(el.href||el.getAttribute&&el.getAttribute('href')||'');}catch(e){}
                return visible(el)&&!/logout|sign\s*out/i.test(text)&&(
                  loginPattern.test(text)||
                  (/login/i.test(href)&&!/logout/i.test(href)&&!/loginLogin\.action(?:$|[?#])/i.test(href))
                );
              });
              if(direct){
                try{window.__nimsLoginPreparationState='login_clicked';direct.click();return 'login_clicked';}catch(e){}
              }
            }
            for(const d of docs){
              let togglers=[];try{togglers=[...d.querySelectorAll('.navbar-toggler,.menu-toggle,[data-bs-toggle=collapse],[data-toggle=collapse],button[aria-label*=menu i],button[title*=menu i]')];}catch(e){}
              const toggle=togglers.find(visible)||actions(d).find(el=>visible(el)&&/^(menu|navigation|☰)$/i.test(actionText(el)));
              if(toggle){
                try{
                  toggle.click();
                  window.__nimsLoginPreparationState='menu_opened';
                  setTimeout(function(){
                    try{
                      const refreshed=[];collect(document,refreshed,[],0);
                      for(const rd of refreshed){
                        const target=actions(rd).find(el=>visible(el)&&loginPattern.test(actionText(el))&&!/logout|sign\s*out/i.test(actionText(el)));
                        if(target){window.__nimsLoginPreparationState='login_clicked';target.click();return;}
                      }
                    }catch(e){}
                  },300);
                  return 'menu_opened';
                }catch(e){}
              }
            }
            return 'not_found';
          }

          const docs=[];collect(document,docs,[],0);
          let passwordVisible=false,usernameVisible=false,captchaVisible=false,loginActionVisible=false;
          let crReady=false,reportRows=0,logoutControl=false,protectedModule=false,sessionExpired=false;
          let publicSignals=false;
          for(const d of docs){
            let body='';try{body=(d.body&&d.body.innerText)||'';}catch(e){}
            const lower=String(body).toLowerCase();
            const href=hrefOf(d);
            let inputs=[];try{inputs=[...d.querySelectorAll('input,textarea')];}catch(e){}
            const pageActions=actions(d);

            if(inputs.some(x=>visible(x)&&String(x.type||'').toLowerCase()==='password'))passwordVisible=true;
            if(inputs.some(x=>visible(x)&&/loginname|username|userid|user\s*id|user_id/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||''))))usernameVisible=true;
            if(inputs.some(x=>visible(x)&&/captcha|verification\s*code|security\s*code/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||''))))captchaVisible=true;
            try{if([...d.querySelectorAll('img,canvas')].some(x=>visible(x)&&/captcha|verification/i.test((x.alt||'')+' '+(x.id||'')+' '+(x.className||''))))captchaVisible=true;}catch(e){}
            if(pageActions.some(x=>visible(x)&&/^(?:login|log\s*in|sign\s*in|submit)$/i.test(actionText(x))))loginActionVisible=true;

            if(inputs.some(x=>visible(x)&&!x.readOnly&&String(x.type||'').toLowerCase()!=='hidden'&&/\bcr\s*(?:no|number)?\b|crno|crnum|patcrno|cr_number/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||''))))crReady=true;
            try{reportRows=Math.max(reportRows,pageActions.filter(x=>/view\s*report/i.test(actionText(x))).length);}catch(e){}

            if(pageActions.some(x=>/^(?:logout|log\s*out|sign\s*out)$/i.test(actionText(x))))logoutControl=true;
            if(/\/HISInvestigationG5\//i.test(href)||/viewcrnowisereportprocess\.cnt/i.test(href)||/cr\s*wise\s*(?:result|report)/i.test(lower))protectedModule=true;
            if(/session\s*(?:has\s*)?expired|invalid\s*session|please\s*login\s*again|session\s*timeout/i.test(lower))sessionExpired=true;
            if(/\bstatistics\b/i.test(lower)&&/recommended\s+to\s+use\s+firefox|designed\s+and\s+developed\s+by\s+c-?dac/i.test(lower))publicSignals=true;
          }

          try{if(typeof window.__nimsCrFieldReady==='function'&&window.__nimsCrFieldReady())crReady=true;}catch(e){}
          if(crReady||reportRows>0)protectedModule=true;

          const loginVisible=passwordVisible||(usernameVisible&&(captchaVisible||loginActionVisible));
          const publicLanding=publicSignals&&!loginVisible&&!crReady&&reportRows===0&&!logoutControl&&!protectedModule;
          const authenticated=!sessionExpired&&!loginVisible&&!publicLanding&&(crReady||reportRows>0||logoutControl||protectedModule);
          let loginPreparation='not_needed';
          if(!authenticated&&!loginVisible&&!sessionExpired&&publicLanding)loginPreparation=prepareLogin(docs);

          return JSON.stringify({
            loginVisible:loginVisible,
            passwordVisible:passwordVisible,
            usernameVisible:usernameVisible,
            captchaVisible:captchaVisible,
            crReady:crReady,
            reportRows:reportRows,
            logoutControl:logoutControl,
            protectedModule:protectedModule,
            publicLanding:publicLanding,
            authenticated:authenticated,
            sessionExpired:sessionExpired,
            loginPreparation:loginPreparation,
            documentCount:docs.length,
            href:String(location.href||'')
          });
        })();
    """.trimIndent()

    fun submitCrScript(crNumber: String): String {
        val escaped = JSONObject.quote(crNumber.filter(Char::isDigit))
        return """
            (function(){
              const value=$escaped;
              try{
                if(typeof window.__nimsSubmitCrNumber==='function') return JSON.stringify(window.__nimsSubmitCrNumber(value));
              }catch(e){}
              const proxy=document.getElementById('__nims_cr_proxy_input');
              const go=document.getElementById('__nims_cr_proxy_go');
              if(proxy&&go){proxy.value=value;go.click();return JSON.stringify({ok:true,reason:'proxy_started'});}
              return JSON.stringify({ok:false,reason:'bridge_not_ready'});
            })();
        """.trimIndent()
    }

    fun resultListProbeScript(expectedCr: String = ""): String {
        val escapedCr = JSONObject.quote(expectedCr.filter(Char::isDigit))
        return """
            (function(){
              const expectedCr=$escapedCr;
              function collect(doc,out,seen,depth){
                if(!doc||depth>7||seen.indexOf(doc)>=0)return;
                seen.push(doc);out.push(doc);
                let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
                for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
              }
              function hash(value){
                let h=2166136261;
                for(let i=0;i<value.length;i++){h^=value.charCodeAt(i);h=Math.imul(h,16777619);}
                return (h>>>0).toString(16);
              }
              function rowIdentity(row){
                if(!row)return '';
                return [
                  row.transientPrintReportArg||row.fileName||row.reportArg||'',
                  row.report_name||row.reportName||row.investigation||row.testName||'',
                  row.date_sent||row.dateSent||row.reportDate||''
                ].join('|');
              }
              const docs=[];collect(document,docs,[],0);
              let bestRows=[];
              let bestFallback=0;
              let crMatch=false;
              for(const d of docs){
                try{
                  if(window.NimsReportCore&&typeof window.NimsReportCore.selectRowsForModeFromDoc==='function'){
                    const rows=window.NimsReportCore.selectRowsForModeFromDoc('bulk_fast',d)||[];
                    if(rows.length>bestRows.length)bestRows=rows;
                  }
                }catch(e){}
                try{bestFallback=Math.max(bestFallback,[...d.querySelectorAll('a,button,input')].filter(x=>/view\s*report/i.test((x.innerText||x.value||x.title||''))).length);}catch(e){}
                if(expectedCr){
                  try{
                    const inputs=[...d.querySelectorAll('input,textarea')];
                    if(inputs.some(x=>String(x.value||'').replace(/\D/g,'')===expectedCr))crMatch=true;
                  }catch(e){}
                  try{
                    const digits=String((d.body&&d.body.innerText)||'').replace(/\D/g,'');
                    if(digits.indexOf(expectedCr)>=0)crMatch=true;
                  }catch(e){}
                }
              }
              const count=Math.max(bestRows.length,bestFallback);
              const raw=bestRows.slice(0,8).map(rowIdentity).join('||')+'#'+bestRows.length;
              return JSON.stringify({
                ready:count>0,
                rowCount:count,
                documentCount:docs.length,
                signature:bestRows.length?hash(raw):'',
                crMatch:crMatch
              });
            })();
        """.trimIndent()
    }

    val prepareMappingScript: String = """
        (function(){
          function collect(doc,out,seen,depth){
            if(!doc||depth>7||seen.indexOf(doc)>=0)return;
            seen.push(doc);out.push(doc);
            let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
            for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
          }
          const docs=[];collect(document,docs,[],0);
          if(!window.NimsReportCore)return JSON.stringify({ok:false,reason:'core_missing'});
          for(let i=docs.length-1;i>=0;i--){
            try{
              const rows=window.NimsReportCore.selectRowsForModeFromDoc('bulk_fast',docs[i])||[];
              if(rows.length){
                const click=window.NimsReportCore.clickFirstReportForMode('test_direct',docs[i]);
                window.__nimsReportDocumentIndex=i;
                return JSON.stringify({ok:true,rowCount:rows.length,documentIndex:i,click:click||null});
              }
            }catch(e){}
          }
          return JSON.stringify({ok:false,reason:'rows_not_ready',documentCount:docs.length});
        })();
    """.trimIndent()

    val discoverMappingScript: String = """
        (function(){
          function collect(doc,out,seen,depth){
            if(!doc||depth>7||seen.indexOf(doc)>=0)return;
            seen.push(doc);out.push(doc);
            let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
            for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
          }
          const docs=[];collect(document,docs,[],0);
          if(!window.NimsReportCore)return JSON.stringify({discovered:false,errorCode:'core_missing'});
          for(let i=docs.length-1;i>=0;i--){
            try{
              const result=window.NimsReportCore.discoverSetPdfTemplate(docs[i]);
              if(result&&result.discovered){result.documentIndex=i;return JSON.stringify(result);}
            }catch(e){}
          }
          return JSON.stringify({discovered:false,errorCode:'mapping_not_ready',documentCount:docs.length});
        })();
    """.trimIndent()

    val selectRowsScript: String = """
        (function(){
          function collect(doc,out,seen,depth){
            if(!doc||depth>7||seen.indexOf(doc)>=0)return;
            seen.push(doc);out.push(doc);
            let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
            for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
          }
          const docs=[];collect(document,docs,[],0);
          if(!window.NimsReportCore)return JSON.stringify([]);
          for(let i=docs.length-1;i>=0;i--){
            try{
              const rows=window.NimsReportCore.selectRowsForModeFromDoc('bulk_fast',docs[i])||[];
              if(rows.length)return JSON.stringify(rows);
            }catch(e){}
          }
          return JSON.stringify([]);
        })();
    """.trimIndent()

    val logoutOtherSessionsScript: String = """
        (function(){
          function collect(doc,out,seen,depth){if(!doc||depth>7||seen.indexOf(doc)>=0)return;seen.push(doc);out.push(doc);let f=[];try{f=doc.querySelectorAll('iframe,frame');}catch(e){}for(const x of f){try{const c=x.contentDocument||(x.contentWindow&&x.contentWindow.document);if(c)collect(c,out,seen,depth+1);}catch(e){}}}
          const docs=[];collect(document,docs,[],0);
          for(const d of docs){
            let all=[];try{all=[...d.querySelectorAll('a,button,input[type=button],input[type=submit]')];}catch(e){}
            const target=all.find(x=>/logout.*other|terminate.*session|force.*login|close.*session/i.test((x.innerText||x.value||x.title||'')));
            if(target){try{target.click();return 'clicked';}catch(e){}}
          }
          return 'not_available';
        })();
    """.trimIndent()
}
