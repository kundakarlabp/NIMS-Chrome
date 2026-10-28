'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/nims_engine.js'),'utf8');

test('native engine contains no date-window batching or second Apply',()=>{
  assert.doesNotMatch(source,/planDateBatches|applyNextBatch|fetching_older_results/);
  assert.match(source,/bulk_selection:/);
  assert.match(source,/staged\.requestId/);
});
test('native engine: all dates and tests, one request, one accepted response',async()=>{
  const stub=source
    .replace(/  async function openTrends\(\) \{[\s\S]*?\n  \}\n\n  function bodyIsBulk/, 
      "  async function openTrends() {return document.getElementById('mock-frame');}\n\n  function bodyIsBulk")
    .replace(/  function probe\(\) \{[\s\S]*?\n  \}\n\n  function parsePatient/,
      "  function probe(){return {authenticated:true};}\n\n  function parsePatient");
  assert.notEqual(stub,source);
  let requests=0,applies=0,receipts=0,patient=0,selection='',errors=[];
  const checkbox=()=>({checked:false,click(){this.checked=true;}});
  const tests=[checkbox(),checkbox()],dates=[checkbox(),checkbox(),checkbox()];
  const patientBar={textContent:'Name: Synthetic CR No: 111111111111111'};
  const win={fetch:async()=>{
    requests++;
    return {clone(){return {text:async()=>JSON.stringify({
      status:1,crNo:'111111111111111',
      groups:[{testName:'CBC',rows:[{parameterName:'WBC',valuesByDate:{'15-Oct-2026':'8.1'}}]}]
    })}}};
  }};
  const apply={textContent:'Apply',click(){applies++;void win.fetch('?hmode=GETMETABOLICDATA');}};
  const panel={querySelectorAll:()=>[apply]};
  const input={dispatchEvent(){}};
  const button={textContent:'Go',click(){}};
  const custom={textContent:'Custom Sheet',click(){}};
  const doc={body:{innerText:'Name: Synthetic CR No: 111111111111111'},
    querySelector:q=>({'#patCrNo':input,'#labTrendsPatientBar':patientBar,
      '#labTrendsCustomBtn':custom,'#labTrendsMetabolicPanel':panel})[q]||null,
    querySelectorAll:q=>q.includes('labTrendsMetaTestChk')?tests:
      q.includes('labTrendsMetaDateChk')?dates:q==='button'?[button]:[]};
  const frame={contentDocument:doc,contentWindow:win};
  const runtime={window:{},document:{getElementById:()=>frame},
    AndroidNimsBridge:{onPatient:()=>patient++,onStage:(_id,v)=>{if(v.startsWith('bulk_selection:'))selection=v;},
      onBulk:()=>receipts++,onError:(_id,e)=>errors.push(e)},
    Event:class {},setTimeout,clearTimeout,URLSearchParams,FormData:class{},console};
  vm.createContext(runtime);vm.runInContext(stub,runtime);
  await runtime.window.NativeNimsEngine.run('111111111111111','request-1');
  await new Promise(r=>setTimeout(r,40));
  assert.deepEqual(errors,[]);
  assert.equal(patient,1);
  assert.equal(selection,'bulk_selection:2/2/3/3');
  assert.equal(applies,1);
  assert.equal(requests,1);
  assert.equal(receipts,1);
  assert(tests.every(x=>x.checked)&&dates.every(x=>x.checked));
});
