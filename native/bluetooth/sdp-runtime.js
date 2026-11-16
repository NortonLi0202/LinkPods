// Common DI operations. All module identities and memory layout come from the selected adapter.
let diSaved = null;
let diProfile = null;
let diVerified = null;
function diConfiguration(profile) {
  if(diVerified && profile===diProfile)return diVerified;
  const layout=profile && profile.sdp_identity;
  const fields=['primary_handle_offset','record_count_offset','records_offset','record_stride',
    'record_handle_offset','attribute_count_offset','attributes_offset','attribute_stride',
    'attribute_length_offset','attribute_id_offset','attribute_type_offset','attribute_value_pointer_offset',
    'max_records','max_attributes'];
  if(!layout || fields.some(key=>!Number.isSafeInteger(layout[key]) || layout[key]<0)
      || layout.record_stride===0 || layout.attribute_stride===0 || layout.max_records<1 || layout.max_records>64
      || layout.max_attributes<1 || layout.max_attributes>50)throw new Error('Invalid sdp_identity layout');
  const observed=rpc.exports.probe(profile);
  if(!observed.ok)throw new Error('SDP module identity probe failed');
  const module=Process.getModuleByName(profile.module_name);
  if(module.path!==profile.module_path)throw new Error('SDP module identity mismatch');
  function at(offset,size) {
    if(!Number.isSafeInteger(offset) || offset<0 || offset+size>module.size)throw new Error('SDP layout outside module');
    return module.base.add(offset);
  }
  diProfile=profile;diVerified={layout:layout,at:at};
  return diVerified;
}
rpc.exports.prepareidentity=function(profile){diConfiguration(profile);return {ok:true};};
function currentDiFields(profile) {
  const config=diConfiguration(profile),l=config.layout,at=config.at;
  const primary=at(l.primary_handle_offset,4).readU32(),count=at(l.record_count_offset,2).readU16();
  if(count>l.max_records || primary===0)throw new Error('Invalid local SDP table');
  const fields={};
  for(let i=0;i<count;i++) {
    const offset=l.records_offset+i*l.record_stride;
    at(offset,l.record_stride);
    if(at(offset+l.record_handle_offset,4).readU32()!==primary)continue;
    const n=at(offset+l.attribute_count_offset,2).readU16();
    if(n>l.max_attributes || l.attributes_offset+n*l.attribute_stride>l.record_stride)throw new Error('Invalid SDP attribute layout');
    for(let j=0;j<n;j++) {
      const a=offset+l.attributes_offset+j*l.attribute_stride;
      const id=at(a+l.attribute_id_offset,2).readU16();
      if(id!==0x201 && id!==0x205)continue;
      if(at(a+l.attribute_length_offset,4).readU32()!==2 || at(a+l.attribute_type_offset,1).readU8()!==1)
        throw new Error('Invalid DI attribute');
      fields[id]=at(a+l.attribute_value_pointer_offset,8).readPointer();
    }
  }
  if(!fields[0x201] || !fields[0x205])throw new Error('Missing DI fields');
  return {primary:primary,fields:fields};
}
function diRead(p){return (p.readU8()<<8)|p.add(1).readU8();}
function diWrite(p,value){p.writeByteArray([(value>>>8)&255,value&255]);}
function diDisabled(){try{return rpc.exports.status().di_identity!==true;}catch(error){return true;}}
rpc.exports.diidentity=function(){
  if(diDisabled())return {handle:0,vendor:0,source:0,disabled:true};
  const d=currentDiFields(diProfile);
  return {handle:d.primary,vendor:diRead(d.fields[0x201]),source:diRead(d.fields[0x205])};
};
rpc.exports.appleidentity=function(profile){
  if(diDisabled())return {handle:0,vendor:0,source:0,disabled:true};
  if(diSaved)throw new Error('DI identity already enabled');
  const d=currentDiFields(profile);
  diProfile=profile;
  diSaved={handle:d.primary,vendor:d.fields[0x201],source:d.fields[0x205],
    oldVendor:diRead(d.fields[0x201]),oldSource:diRead(d.fields[0x205])};
  diWrite(diSaved.vendor,0x004c);diWrite(diSaved.source,0x0001);
  return rpc.exports.diidentity();
};
function restoreDi(){
  if(!diSaved)return;
  const d=currentDiFields(diProfile);
  if(d.primary!==diSaved.handle || !d.fields[0x201].equals(diSaved.vendor) || !d.fields[0x205].equals(diSaved.source))
    throw new Error('DI record replaced before restoration');
  diWrite(diSaved.vendor,diSaved.oldVendor);diWrite(diSaved.source,diSaved.oldSource);
  if(diRead(diSaved.vendor)!==diSaved.oldVendor || diRead(diSaved.source)!==diSaved.oldSource)throw new Error('DI restoration failed');
  diSaved=null;
}
const diOriginalDisable=rpc.exports.disable;
rpc.exports.disable=function(){restoreDi();return diOriginalDisable();};
