// Keep only this already-validated session alive until explicit stop or Bluetooth restart.
let residentTimer = null;
let residentConfiguration = null;
const residentControl = '/data/misc/bluedroid/handoff-runtime.control';
const residentStatus = '/data/misc/bluedroid/handoff-runtime.status';
let residentInstance = null;
function residentSnapshot(error) {
  const state = rpc.exports.status();
  let identity;
  try {
    identity = rpc.exports.diidentity();
  } catch (diError) {
    // Cleanup confirmation must not depend on optional DI inspection.
    identity = {handle:0,vendor:0,source:0,disabled:true,error:String(diError)};
  }
  const result = {pid:Process.id,instance_id:residentInstance,updated_at_ms:Date.now(),state:state,
    di_identity:state.di_identity===true,identity:identity,resume:rpc.exports.resumestatus ? rpc.exports.resumestatus() : null,
    configuration:residentConfiguration,error:error || null};
  File.writeAllText(residentStatus,JSON.stringify(result)+'\n');
  return result;
}
rpc.exports.startresident = function(configuration,instanceId) {
  if (residentTimer !== null) throw new Error('Resident session already started');
  residentConfiguration = configuration || null;
  residentInstance = instanceId || Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);
  File.writeAllText(residentControl,'run\n');
  const first = residentSnapshot(null);
  residentTimer = setInterval(function() {
    try {
      if (File.readAllText(residentControl).trim() === 'stop') {
        const status = rpc.exports.disable();
        if (status.state === 'disabled' && status.active_sdp === 0) {
          clearInterval(residentTimer); residentTimer = null;
        }
      }
      residentSnapshot(null);
    } catch (error) {
      try { residentSnapshot(String(error)); } catch (_) { console.error(String(error)); }
    }
  },5000);
  return first;
};
