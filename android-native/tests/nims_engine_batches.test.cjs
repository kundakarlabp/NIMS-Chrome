const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const srcPath = path.join(__dirname,'../app/src/main/assets/nims_engine.js');
const source = fs.readFileSync(srcPath,'utf8');
const original = 'window.NativeNimsEngine = { probe, run, continueRun };';
assert.ok(source.includes(original),'staged JavaScript export missing');
const instrumented = source.replace(original,
  'window.NativeNimsEngine = { probe, run, continueRun, __planDateBatches: planDateBatches };');
const context={window:{},document:{},setTimeout,clearTimeout};
vm.runInNewContext(instrumented,context);
const plan=context.window.NativeNimsEngine.__planDateBatches;
const input = value => ({
  value,
  getAttribute: () => null,
  closest: () => null,
  parentElement: {textContent:value}
});
const native = result => JSON.parse(JSON.stringify(result));

test('newest 14 days first, older history afterwards without skipped dates',()=>{
  const dates=[
    input('05-Sep-2026'),input('26-Oct-2026'),input('24-Oct-2026'),
    input('04-Sep-2026')
  ];
  const batches=native(plan(dates));
  assert.deepEqual(batches,[[1,2],[0,3]]);
  assert.deepEqual(batches.flat().sort(),[0,1,2,3]);
});
test('unknown NIMS date markup uses single complete fetch rather than discarding history',()=>{
  assert.deepEqual(native(plan([input('some unknown day'),input('26-Oct-2026')])),[null]);
  assert.deepEqual(native(plan([])),[null]);
});
test('a large 14-day window is split into memory-bounded batches',()=>{
  const dates=Array.from({length:52},(_,i)=>input(i%2?'25-Oct-2026':'26-Oct-2026'));
  const batches=native(plan(dates));
  assert.deepEqual(batches.map(b=>b.length),[24,24,4]);
  assert.equal(batches.flat().length,52);
});
