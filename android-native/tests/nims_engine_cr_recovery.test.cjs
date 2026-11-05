'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

const code=fs.readFileSync(
  path.join(__dirname,'../app/src/main/assets/nims_engine.js'),'utf8');

function scenario({correctAfter=2,wrong=false,spaced=false}={}) {
  const cr='123456789012345',other='999999999999999';
  let clicks=0,patientCount=0,bulks=0,applies=0;
  const stages=[],errors=[],checkbox=()=>({
    checked:false,click(){this.checked=true;}
  });
  const tests=[checkbox()],dates=[checkbox()];
  const input={value:'',dispatchEvent(){}};
  const bar={textContent:'',getAttribute(){return null}};
  const patientText=()=>bar.textContent||'';
  const go={textContent:'Go',click(){
    clicks++;
    if(clicks>=correctAfter) {
      bar.textContent=wrong?'CR No: '+other:
        spaced?'Patient CR No: 12345 67890 12345':'CR No: '+cr;
    }
  }};
  const win={fetch:async()=>({
    clone(){return {text:async()=>JSON.stringify({
      crNo:cr,groups:[{testName:'CBC'}]
    })}}
  })};
  const apply={textContent:'Apply',click(){
    applies++;void win.fetch('?hmode=GETMETABOLICDATA');
  }};
  const panel={querySelectorAll:()=>[apply]};
  const doc={
    body:{get innerText(){return 'Name: Synthetic Patient\n'+patientText()}},
    querySelector(selector){
      return ({'#patCrNo':input,'#labTrendsPatientBar':
        clicks>=correctAfter ?bar:null,
        '#labTrendsCustomBtn':{textContent:'Custom Sheet',click(){}},
        '#labTrendsMetabolicPanel':panel})[selector]||null;
    },
    querySelectorAll(selector){
      if(selector==='iframe,frame')return [];
      if(selector.includes('labTrendsMetaTestChk'))return tests;
      if(selector.includes('labTrendsMetaDateChk'))return dates;
      if(selector==='button')return [go];
      return [];
    }
  };
  const frame={contentDocument:doc,contentWindow:win};
  const mockTop={getElementById:()=>frame};
  let tick=0;
  const js=code.replace(
    /  async function openTrends\(\) \{[\s\S]*?\n  \}\n\n  function bodyIsBulk/,
    "  async function openTrends() {return document.getElementById('mock');}\n\n  function bodyIsBulk"
  );
  assert.notEqual(js,code);
  const runtime={
    window:{},document:mockTop,
    AndroidNimsBridge:{
      onPatient(){patientCount++},
      onStage(_requestId,value){stages.push(value)},
      onBulk(){bulks++},
      onError(_requestId,value){errors.push(value)}
    },Date:{now(){tick+=9000;return tick;}},
    Event:class{},setTimeout,clearTimeout,
    URL,URLSearchParams,FormData:class{},console
  };
  vm.createContext(runtime);
  vm.runInContext(js,runtime);
  return {runtime,cr,errors,stages,
    get clicks(){return clicks;},
    get patientCount(){return patientCount;},
    get bulks(){return bulks;},
    get applies(){return applies;}
  };
}

test('slow patient search gets one safe retry and then receives Custom Sheet',async()=>{
  const env=scenario({correctAfter:2});
  await env.runtime.window.NativeNimsEngine.run(env.cr,'slow-request');
  await new Promise(resolve=>setTimeout(resolve,40));
  assert.deepEqual(env.errors,[]);
  assert.equal(env.clicks,2);
  assert.equal(env.patientCount,1);
  assert.equal(env.applies,1);
  assert.equal(env.bulks,1);
  assert(env.stages.includes('cr_lookup_retry'));
  assert(env.stages.includes('cr_panel_confirmed'));
});

test('formatted NIMS CR in signed patient bar is accepted',async()=>{
  const env=scenario({correctAfter:1,spaced:true});
  await env.runtime.window.NativeNimsEngine.run(env.cr,'formatted-request');
  await new Promise(resolve=>setTimeout(resolve,40));
  assert.deepEqual(env.errors,[]);
  assert.equal(env.patientCount,1);
});

test('another patient in result bar cannot be accepted or bulk fetched',async()=>{
  const env=scenario({correctAfter:1,wrong:true});
  await env.runtime.window.NativeNimsEngine.run(env.cr,'wrong-request');
  assert.equal(env.patientCount,0);
  assert.equal(env.bulks,0);
  assert.equal(env.applies,0);
  assert.equal(env.clicks,2);
  assert.equal(env.errors.length,1);
  assert.match(env.errors[0],/requested CR results did not load/);
  assert(env.stages.includes('cr_panel_identity_unconfirmed'));
});

test('do not trust a typed CR value when the HIS has no result panel',()=>{
  assert.match(code,/patientBarMatches\(confirmedBar,crNo\)/);
  assert.match(code,/if\(input && go && visible\(go\)/);
  assert.match(code,/cr_panel_missing/);
});
