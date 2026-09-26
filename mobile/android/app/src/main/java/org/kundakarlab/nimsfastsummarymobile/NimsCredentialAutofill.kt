package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject

/**
 * Login helper scripts. Username/password are supplied only from the local
 * Keystore-backed credential vault. CAPTCHA text is never read back into Kotlin.
 */
object NimsCredentialAutofill {
    fun fillScript(username: String, password: String): String {
        val user = JSONObject.quote(username)
        val pass = JSONObject.quote(password)
        return """
            (function(){
              function collect(doc,out,seen,depth){
                if(!doc||depth>7||seen.indexOf(doc)>=0)return;
                seen.push(doc);out.push(doc);
                let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
                for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
              }
              function visible(el){
                if(!el||el.disabled)return false;
                try{const s=el.ownerDocument.defaultView.getComputedStyle(el);const r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)!==0&&r.width>0&&r.height>0;}catch(e){return true;}
              }
              function signal(el){
                try{el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}
              }
              const docs=[];collect(document,docs,[],0);
              let filledUser=false,filledPassword=false,captchaFound=false;
              for(const d of docs){
                let inputs=[];try{inputs=[...d.querySelectorAll('input,textarea')];}catch(e){}
                const password=inputs.find(x=>visible(x)&&String(x.type||'').toLowerCase()==='password');
                const username=inputs.find(x=>visible(x)&&/loginname|username|userid|user\s*id|user_id/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||'')));
                const captcha=inputs.find(x=>visible(x)&&/captcha|verification\s*code|security\s*code/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||'')));
                if(username){username.value=$user;signal(username);filledUser=true;}
                if(password){password.value=$pass;signal(password);filledPassword=true;}
                if(captcha){captchaFound=true;try{captcha.focus();}catch(e){}}
              }
              return JSON.stringify({ok:filledUser&&filledPassword,filledUser:filledUser,filledPassword:filledPassword,captchaFound:captchaFound});
            })();
        """.trimIndent()
    }

    val authenticateScript: String = """
        (function(){
          function collect(doc,out,seen,depth){
            if(!doc||depth>7||seen.indexOf(doc)>=0)return;
            seen.push(doc);out.push(doc);
            let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
            for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
          }
          function visible(el){
            if(!el||el.disabled)return false;
            try{const s=el.ownerDocument.defaultView.getComputedStyle(el);const r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)!==0&&r.width>0&&r.height>0;}catch(e){return true;}
          }
          function label(el){return String((el&&((el.innerText||el.textContent||el.value||el.title)||(el.getAttribute&&el.getAttribute('aria-label'))))||'').replace(/\s+/g,' ').trim();}
          const docs=[];collect(document,docs,[],0);
          for(const d of docs){
            let inputs=[];try{inputs=[...d.querySelectorAll('input,textarea')];}catch(e){}
            const password=inputs.find(x=>visible(x)&&String(x.type||'').toLowerCase()==='password');
            if(!password)continue;
            const captcha=inputs.find(x=>visible(x)&&/captcha|verification\s*code|security\s*code/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||'')));
            if(captcha&&!String(captcha.value||'').trim()){try{captcha.focus();}catch(e){}return JSON.stringify({ok:false,reason:'captcha_required'});}
            let actions=[];try{actions=[...d.querySelectorAll('button,input[type=submit],input[type=button],a,[role=button]')];}catch(e){}
            const action=actions.find(x=>visible(x)&&/^(?:login|log\s*in|sign\s*in|submit|authenticate)$/i.test(label(x)));
            if(action){try{action.click();return JSON.stringify({ok:true,reason:'clicked_login'});}catch(e){}}
            const form=password.form||d.querySelector('form');
            if(form){try{if(typeof form.requestSubmit==='function')form.requestSubmit();else form.submit();return JSON.stringify({ok:true,reason:'submitted_form'});}catch(e){}}
            return JSON.stringify({ok:false,reason:'login_action_not_found'});
          }
          return JSON.stringify({ok:false,reason:'login_form_not_found'});
        })();
    """.trimIndent()
}
