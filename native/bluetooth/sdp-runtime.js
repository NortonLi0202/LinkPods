// Firmware-specific runtime experiment. Profile identity is verified by the host agent.
let diSaved = null;
let diObserver = null;
const diServed = [];
rpc.exports.resetacl = function(action, address) {
  if (!diSaved || agent.state !== 'enabled') throw new Error('Requires enabled, scoped Apple DI experiment');
  const bytes = address.split(':').map(v=>parseInt(v,16));
  if (bytes.length !== 6 || bytes.some((v,i)=>v !== agent.targetBytes[i])) throw new Error('Wrong ACL target');
  const p = Process.getModuleByName('libbluetooth_jni.so').base.add(action.offset);
  const expected = action.prologue.match(/../g).map(v=>parseInt(v,16));
  const current = Array.from(new Uint8Array(p.readByteArray(expected.length)));
  if (current.some((v,i)=>v!==expected[i])) throw new Error('ACL function identity mismatch');
  const raw = Memory.alloc(6); raw.writeByteArray(bytes);
  const disconnect = new NativeFunction(p,'int',['pointer','int']);
  return {status:disconnect(raw,1),transport:1};
};
function currentDiFields() {
  const b = Process.getModuleByName('libbluetooth_jni.so').base;
  const primary = b.add(0xdf1668).readU32();
  const count = b.add(0xdf166c).readU16();
  if (count > 64 || primary === 0) throw new Error('Invalid local SDP table');
  const fields = {};
  for (let i = 0; i < count; i++) {
    const rec = b.add(0xdf1670 + i * 0x4c0);
    if (rec.readU32() !== primary) continue;
    const n = rec.add(8).readU16();
    if (n > 50) throw new Error('Invalid local attribute count');
    for (let j = 0; j < n; j++) {
      const a = rec.add(16 + j * 24);
      const id = a.add(16).readU16();
      if (id !== 0x201 && id !== 0x205) continue;
      if (a.readU32() !== 2 || a.add(18).readU8() !== 1) throw new Error('Invalid DI attribute');
      fields[id] = a.add(8).readPointer();
    }
  }
  if (!fields[0x201] || !fields[0x205]) throw new Error('Missing DI fields');
  return {primary:primary, fields:fields};
}
function diRead(p) { return (p.readU8() << 8) | p.add(1).readU8(); }
function diWrite(p,v) { p.writeByteArray([(v >>> 8) & 255,v & 255]); }
rpc.exports.diidentity = function() {
  const d = currentDiFields();
  return {handle:d.primary,vendor:diRead(d.fields[0x201]),source:diRead(d.fields[0x205]),served:diServed.slice()};
};
rpc.exports.appleidentity = function() {
  if (diSaved) throw new Error('DI experiment already enabled');
  const d = currentDiFields();
  diSaved = {handle:d.primary,vendor:d.fields[0x201],source:d.fields[0x205],
    oldVendor:diRead(d.fields[0x201]),oldSource:diRead(d.fields[0x205])};
  diWrite(diSaved.vendor,0x004c);
  diWrite(diSaved.source,0x0001);
  diObserver = Interceptor.attach(Process.getModuleByName('libbluetooth_jni.so').base.add(0x7e574c), {
    onEnter(args) {
      const a = args[1];
      if (a.add(16).readU16() === 0x201 && a.readU32() === 2 && diServed.length < 8)
        diServed.push(diRead(a.add(8).readPointer()));
    }
  });
  return rpc.exports.diidentity();
};
function restoreDi() {
  if (!diSaved) return;
  const d = currentDiFields();
  if (d.primary !== diSaved.handle || !d.fields[0x201].equals(diSaved.vendor) ||
      !d.fields[0x205].equals(diSaved.source)) throw new Error('DI record replaced before restoration');
  diWrite(diSaved.vendor,diSaved.oldVendor);
  diWrite(diSaved.source,diSaved.oldSource);
  if (diRead(diSaved.vendor) !== diSaved.oldVendor || diRead(diSaved.source) !== diSaved.oldSource)
    throw new Error('DI restoration verification failed');
  diSaved = null;
}
const diOriginalDisable = rpc.exports.disable;
rpc.exports.disable = function() {
  restoreDi();
  if (diObserver) { diObserver.detach(); diObserver = null; }
  return diOriginalDisable();
};
