const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const writes = new Map();
const configuration = {target_address:'AA:BB:CC:DD:EE:01',profile_sha256:'a'.repeat(64),bundle_id:'b'.repeat(64)};
const context = vm.createContext({Process:{id:42}, Date, JSON,
  rpc:{exports:{status:()=>({state:'enabled'}),diidentity:()=>({}),disable:()=>({state:'disabled',active_sdp:0})}},
  File:{writeAllText:(path,value)=>writes.set(path,value),readAllText:path=>writes.get(path)},
  setInterval:()=>1,clearInterval:()=>{}});
vm.runInContext(fs.readFileSync(__dirname+'/resident-runtime.js','utf8'),context);
context.rpc.exports.startresident(configuration);
const report=JSON.parse(writes.get('/data/misc/bluedroid/handoff-runtime.status'));
assert.deepEqual(report.configuration,configuration);
assert.equal(report.pid,42);
assert.equal(report.state.state,'enabled');
assert.throws(()=>context.rpc.exports.startresident(configuration),/already started/);
console.log('PASS: resident reports the exact target and configuration used for safe reuse');
