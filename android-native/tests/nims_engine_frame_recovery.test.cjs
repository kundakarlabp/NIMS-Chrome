'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(
  path.join(__dirname,'../app/src/main/assets/nims_engine.js'),'utf8');

test('renamed nested CR frame is accepted only after NIMS shell and CR validation',async()=>{
  const cr='111111111111111';
  const stages=[],errors=[];
  let applies=0, receipts=0,patients=0;
  const checkbox=()=>({checked:false,click(){this.checked=true;}});
  const tests=[checkbox()],dates=[checkbox()];
  const bar={textContent:'Name: Synthetic CR No: '+cr};
  const windowForForm={fetch:async()=>({clone(){return {text:async()=>
    JSON.stringify({crNo:cr, groups:[{testName:'CBC'}]})}}})};
  const apply={textContent:'Apply',click(){applies++; void windowForForm.fetch('/GETMETABOLICDATA');}};
  const panel={querySelectorAll:()=>[apply]};
  const formDoc={
    body:{textContent:'',innerText:'Name: Synthetic CR No: '+cr},
    querySelector:s=>({
      '#patCrNo':{value:'',dispatchEvent(){}},
      '#labTrendsPatientBar':bar,
      '#labTrendsCustomBtn':{textContent:'Custom Sheet',click(){}},
      '#labTrendsMetabolicPanel':panel,
    })[s]||null,
    querySelectorAll:s=>s==='iframe,frame'?[]:
      s.includes('labTrendsMetaTestChk')?tests:
      s.includes('labTrendsMetaDateChk')?dates:
      s==='button'?[{textContent:'Go',click(){}}]:[]
  };
  const renamed={id:'nims_trends_dynamic_2026',contentDocument:formDoc,contentWindow:windowForForm};
  const parentDoc={body:{textContent:'NIMS',innerText:'NIMS'},
    querySelector:()=>null,querySelectorAll:s=>s==='iframe,frame'?[renamed]:[]};
  const outer={contentDocument:parentDoc};
  const doc={
    body:{textContent:'Home Menu Logout',innerText:'Home Menu Logout'},
    querySelector:()=>null,
    querySelectorAll:s=>s==='iframe,frame'?[outer]:[],
    getElementById:()=>null
  };
  const runtime={
    window:{},document:doc,
    location:{href:'https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action'},
    AndroidNimsBridge:{
      onPatient(){patients++;},onStage(_id,x){stages.push(x);},
      onBulk(){receipts++;},onError(_id,e){errors.push(e);}
    },
    Event:class {},setTimeout,clearTimeout,
    URL,URLSearchParams,FormData:class{},console
  };
  vm.createContext(runtime);
  vm.runInContext(source,runtime);
  await runtime.window.NativeNimsEngine.run(cr,'synthetic-request');
  await new Promise(r=>setTimeout(r,40));
  assert.deepEqual(errors,[]);
  assert.equal(patients,1);
  assert.equal(applies,1);
  assert.equal(receipts,1);
  assert(stages.includes('opening_trends'));
  assert(tests[0].checked && dates[0].checked);
});

test('never treat a menu-only shell as if the CR page loaded',()=>{
  assert.match(source,/inner\?\.querySelector\(UI\.crInput\)/);
  assert.match(source,/Ambiguous NIMS CR trends frames/);
  assert.match(source,/requested CR results/);
  assert.match(source,/NIMS patient changed while fetching results/);
});
