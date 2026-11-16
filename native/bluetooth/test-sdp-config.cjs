const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
function exercise(script,profile,generated=false) {
  function run(changed=profile,validIdentity=true,validTable=true,failEnable=false) {
    const memory=Buffer.alloc(4096),writes=[];
    class Pointer {
      constructor(offset){this.offset=offset;}
      add(offset){return new Pointer(this.offset+offset);}
      readU32(){return memory.readUInt32LE(this.offset);}
      readU16(){return memory.readUInt16LE(this.offset);}
      readU8(){return memory.readUInt8(this.offset);}
      readPointer(){return new Pointer(this.readU32());}
      equals(other){return this.offset===other.offset;}
      writeByteArray(bytes){writes.push(this.offset);Buffer.from(bytes).copy(memory,this.offset);}
    }
    const layout=profile.sdp_identity;
    memory.writeUInt32LE(validTable?42:0,layout.primary_handle_offset);memory.writeUInt16LE(1,layout.record_count_offset);
    memory.writeUInt32LE(42,layout.records_offset+layout.record_handle_offset);
    memory.writeUInt16LE(2,layout.records_offset+layout.attribute_count_offset);
    for(let index=0;index<2;index++) {
      const attr=layout.records_offset+layout.attributes_offset+index*layout.attribute_stride;
      memory.writeUInt32LE(2,attr+layout.attribute_length_offset);
      memory.writeUInt16LE(index===0?0x201:0x205,attr+layout.attribute_id_offset);
      memory.writeUInt8(1,attr+layout.attribute_type_offset);
      memory.writeUInt32LE(3000+index*8,attr+layout.attribute_value_pointer_offset);
      memory.writeUInt16BE(index===0?0x1234:2,3000+index*8);
    }
    const module={name:profile.module_name,path:profile.module_path,size:memory.length,base:new Pointer(0)};
    let hooked=false;
    const context=vm.createContext({Process:{id:42,getModuleByName:name=>{assert.equal(name,profile.module_name);return module;}},Date,Math,JSON,
      rpc:{exports:{probe:()=>{assert.equal(hooked,false,'original prologues can only be checked before hooks');return {ok:validIdentity};},enable:()=>{assert.equal(validTable,true,'invalid SDP must fail before attaching listeners');hooked=true;if(failEnable)throw new Error('partial enable');return {state:'enabled'};},status:()=>({state:hooked?'enabled':'disabled',active_sdp:0}),disable:()=>{hooked=false;return {state:'disabled',active_sdp:0};}}},
      File:{writeAllText:()=>{},readAllText:()=>''},setInterval:()=>1,clearInterval:()=>{},console:{log:()=>{}}});
    if(generated) {
      try {vm.runInContext(script.replace(/const runtimeProfile = [^\n]*;\n/,'const runtimeProfile = '+JSON.stringify(changed)+';\n'),context);}
      catch(error) {assert.equal(hooked,false,'startup failure must restore partial listeners');throw error;}
    }
    else {vm.runInContext(script,context);context.rpc.exports.appleidentity(changed);}
    return {memory,writes,context};
  }
  const result=run();assert.equal(result.memory.readUInt16BE(3000),0x4c);assert.equal(result.memory.readUInt16BE(3008),1);
  result.context.rpc.exports.disable();assert.equal(result.memory.readUInt16BE(3000),0x1234);assert.equal(result.memory.readUInt16BE(3008),2);
  assert.throws(()=>run(profile,false),/identity|probe|false/i);
  for(const field of Object.keys(profile.sdp_identity)) {
    const changed=JSON.parse(JSON.stringify(profile));delete changed.sdp_identity[field];
    assert.throws(()=>run(changed),/layout|sdp_identity/i);
  }
  const wrongPath={...profile,module_path:'/wrong/module.so'};
  assert.throws(()=>run(wrongPath),/module|identity/i);
  if(generated) {
    assert.throws(()=>run(profile,true,false),/Invalid local SDP table/);
    assert.throws(()=>run(profile,true,true,true),/partial enable/);
  }
}
const base=JSON.parse(fs.readFileSync(__dirname+'/profile.json','utf8'));
if(process.argv[2]) {
  const profile=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
  exercise(fs.readFileSync(process.argv[2],'utf8'),profile,true);
} else {
  for(let index=0;index<2;index++) {
    const profile={...base,module_name:'synthetic-'+index+'.so',module_path:'/apex/synthetic-'+index+'.so',sdp_identity:{
      primary_handle_offset:32+index*16,record_count_offset:36+index*16,records_offset:128+index*256,
      record_stride:128+index*64,record_handle_offset:0,attribute_count_offset:8,attributes_offset:16,
      attribute_stride:24+index*8,attribute_length_offset:0,attribute_id_offset:16,attribute_type_offset:18,
      attribute_value_pointer_offset:8,max_records:2,max_attributes:3}};
    exercise(fs.readFileSync(__dirname+'/sdp-runtime.js','utf8'),profile);
  }
}
console.log('PASS: profile-specific SDP layout, restoration and identity/layout rejection');
