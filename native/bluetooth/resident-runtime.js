// Keep only this already-validated session alive until explicit stop or Bluetooth restart.
let residentTimer = null;
let residentConfiguration = null;
const residentControl = '/data/misc/bluedroid/handoff-runtime.control';
const residentStatus = '/data/misc/bluedroid/handoff-runtime.status';
function residentSnapshot(error) {
  const state = rpc.exports.status();
  const result = {pid:Process.id,updated_at_ms:Date.now(),state:state,
    identity:rpc.exports.diidentity(),resume:rpc.exports.resumestatus ? rpc.exports.resumestatus() : null,
    configuration:residentConfiguration,error:error || null};
  File.writeAllText(residentStatus,JSON.stringify(result)+'\n');
  return result;
}
rpc.exports.startresident = function(configuration) {
  if (residentTimer !== null) throw new Error('Resident session already started');
  residentConfiguration = configuration || null;
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
