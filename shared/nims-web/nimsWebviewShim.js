// Android-only compatibility and native CR-search bridge for legacy NIMS pages.
(function(w){
  if(!w)return;
  try{
    if(typeof w.date_time==="undefined")w.date_time=function(){return""};

    function patch(jq){
      if(!jq||!jq.fn||jq.fn.__nimsOffsetPatched||typeof jq.fn.offset!=="function")return false;
      var old=jq.fn.offset;
      jq.fn.offset=function(){var v=old.apply(this,arguments);return v==null?{top:0,left:0}:v};
      jq.fn.__nimsOffsetPatched=true;
      return true
    }
    function hook(name){
      var value;
      try{value=w[name]}catch(e){}
      if(value&&patch(value))return true;
      try{
        Object.defineProperty(w,name,{
          configurable:true,enumerable:true,
          get:function(){return value},
          set:function(v){value=v;patch(v)}
        });
        return true
      }catch(e){return false}
    }
    var hj=hook("jQuery"),hd=hook("$");
    if((!hj||!hd)&&typeof w.setInterval==="function"){
      var n=0,t=w.setInterval(function(){
        n+=1;
        if(patch(w.jQuery)||patch(w.$)||n>200)w.clearInterval(t)
      },50)
    }

    var errors=[];
    w.__nimsShimErrors=errors;
    if(typeof w.addEventListener==="function")w.addEventListener("error",function(ev){
      try{
        if(errors.length<12)errors.push(
          String(ev&&ev.message||"error")+" @"+
          String(ev&&ev.filename||"").split("/").pop()+":"+
          String(ev&&ev.lineno||"?")
        )
      }catch(e){}
    });

    function report(extra){
      try{
        var b=w.nimsAndroidBridge,d=w.document,body=d&&d.body,u="",list=errors.slice(0,6);
        if(!b||typeof b.postMessage!=="function")return false;
        try{var x=new URL(w.location.href);u=x.hostname+x.pathname}catch(e){}
        if(extra)list.unshift(extra);
        b.postMessage(JSON.stringify({
          type:"nims_frame_debug",url:u,
          children:body?body.querySelectorAll("*").length:0,
          textLen:body&&body.innerText?body.innerText.trim().length:0,
          height:body?body.scrollHeight||0:0,
          errors:list.slice(0,6)
        }));
        return true
      }catch(e){return false}
    }

    function investigation(node){
      for(var e=node,i=0;e&&i<7;e=e.parentElement,i+=1){
        try{
          var oc=e.getAttribute?e.getAttribute("onclick")||"":"",
              tx=String(e.innerText||e.textContent||e.value||"").replace(/\s+/g," ").trim();
          if(/menuSelected\s*\(\s*['"]Investigation['"]\s*,\s*true\s*\)/i.test(oc)||/^Investigation$/i.test(tx))return true
        }catch(err){}
      }
      return false
    }

    function install(){
      var d=w.document;
      if(!d||d.__nimsNativeCrOpenInstalled||typeof d.addEventListener!=="function")return;
      d.__nimsNativeCrOpenInstalled=true;
      d.addEventListener("click",function(ev){
        if(!investigation(ev&&ev.target))return;
        var tries=0;
        function open(){
          tries+=1;
          try{
            var f=(d.getElementById&&d.getElementById("frmMainMenu"))||
                  (d.querySelector&&d.querySelector('iframe[name="frmMainMenu"],frame[name="frmMainMenu"]')),
                child=f&&f.contentWindow;
            if(child&&typeof child.callMenu==="function"){
              child.callMenu("/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt","Cr_No_Wise_Result_Report_Printing_New");
              report("NAV native_cr_open action=called_child_callMenu");
              return
            }
            if(typeof w.callMenu==="function"){
              w.callMenu("/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt","Cr No Wise Result Report Printing New");
              report("NAV native_cr_open action=called_top_callMenu");
              return
            }
          }catch(e){
            report("NAV native_cr_open error="+String(e&&e.message||"unknown").slice(0,100));
            return
          }
          if(tries<4&&typeof w.setTimeout==="function")w.setTimeout(open,500);
          else report("NAV native_cr_open error=native_callMenu_unavailable")
        }
        if(typeof w.setTimeout==="function")w.setTimeout(open,250);else open()
      },true)
    }

    if(w.document&&typeof w.setTimeout==="function"){
      var attempts=0,fire=function(){
        attempts+=1;
        if(!report()&&attempts<6)w.setTimeout(fire,1000)
      };
      if(w.document.readyState==="loading"&&typeof w.addEventListener==="function"){
        w.addEventListener("DOMContentLoaded",function(){install();w.setTimeout(fire,800)},{once:true})
      }else{
        install();w.setTimeout(fire,800)
      }
    }
  }catch(e){
    if(w.console&&typeof w.console.error==="function")w.console.error("NIMS WebView shim failed",e)
  }
})(typeof window!=="undefined"?window:null);
