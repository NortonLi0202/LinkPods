/*
 * LibrePods handoff-bridge Frida agent
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Generic, offset-free Frida agent. All module addresses, function offsets
 * and struct field offsets are supplied by the local profile at runtime.
 * This agent never verifies the module file hash; that is the host's job.
 *
 * Only offline development is intended here.
 */
'use strict';

var CCB_LCB = 'ccb_lcb';
var CCB_RCB = 'ccb_rcb';
var RCB_WIRE_PSM = 'rcb_wire_psm';
var RCB_PREFERRED_MODE = 'rcb_preferred_mode';
var LCB_ADDRESS = 'lcb_address';

var L2CAP_STATE = 'l2cap';
var SDP_STATE = 'sdp';

var MAX_FIELD_OFFSET = 65536;

function makeStatus(state, extra) {
  var s = {
    state: state || 'disabled',
    active_sdp: 0,
    restore_failures: 0,
    last_error: null,
    l2cap_matches: 0,
    l2cap_rewrites: 0,
    sdp_writes: 0,
    sdp_restores: 0
  };
  if (extra) {
    for (var k in extra) {
      if (Object.prototype.hasOwnProperty.call(extra, k)) {
        s[k] = extra[k];
      }
    }
  }
  return s;
}

var agent = {
  state: 'disabled',
  accepting: false,
  l2capListener: null,
  sdpListener: null,
  profile: null,
  resolvedOffsets: null,
  targetBytes: null,
  spoofVendor: false,
  diIdentity: true,
  activeSdp: 0,
  sdpRecords: new Map(),
  restoreFailures: 0,
  lastError: null,
  stats: {
    l2cap_matches: 0,
    l2cap_rewrites: 0,
    sdp_writes: 0,
    sdp_restores: 0
  }
};

function noteError(message) {
  agent.lastError = String(message);
}

function noteRestoreFailure(message) {
  agent.restoreFailures += 1;
  noteError(message);
  agent.accepting = false;
  agent.state = 'draining';
}

function isSafeNonNegativeInt(v) {
  return Number.isSafeInteger(v) && v >= 0;
}

function isHexString(v) {
  return typeof v === 'string' && v.length > 0 && v.length % 2 === 0 &&
    /^[0-9a-fA-F]+$/.test(v);
}

function fail(reason) {
  return { ok: false, reason: String(reason) };
}

function checkResolution(resolution) {
  if (resolution === undefined || resolution === null) {
    return { enabled: false };
  }
  if (typeof resolution !== 'object') {
    return { error: 'resolution must be an object' };
  }
  if (resolution.schema !== 1) {
    return { error: 'unsupported resolution.schema' };
  }
  if (resolution.mode !== 'prologue_scan') {
    return { error: 'unsupported resolution.mode' };
  }
  return { enabled: true, mode: resolution.mode };
}

function scanPrologue(mod, hex) {
  var pattern = hex.match(/.{2}/g).join(' ');
  var ranges;
  try {
    ranges = mod.enumerateRanges('r-x');
  } catch (e) {
    return { error: 'range enumeration failed: ' + String(e) };
  }
  if (!ranges || ranges.length === 0) {
    return { error: 'no executable module ranges' };
  }
  var matches = [];
  for (var i = 0; i < ranges.length; i++) {
    try {
      matches = matches.concat(Memory.scanSync(ranges[i].base, ranges[i].size, pattern));
    } catch (e) {
      return { error: 'scan failed: ' + String(e) };
    }
  }
  if (matches.length !== 1) {
    return { error: 'expected exactly one prologue match, got ' + matches.length };
  }
  var offset = parseInt(matches[0].address.sub(mod.base).toString(16), 16);
  if (!isSafeNonNegativeInt(offset) || offset >= mod.size) {
    return { error: 'resolved offset outside module' };
  }
  return { offset: offset };
}

function checkProfileShape(profile) {
  if (profile === null || typeof profile !== 'object') {
    return 'profile must be an object';
  }
  if (profile.schema_version !== 1) {
    return 'unsupported schema_version';
  }
  if (typeof profile.module_name !== 'string' || profile.module_name.length === 0) {
    return 'module_name must be a non-empty string';
  }
  if (typeof profile.module_path !== 'string' || profile.module_path.length === 0) {
    return 'module_path must be a non-empty string';
  }
  if (typeof profile.file_sha256 !== 'string') {
    return 'file_sha256 must be a string';
  }

  var resolution = checkResolution(profile.resolution);
  if (resolution.error) {
    return resolution.error;
  }

  var buildId = profile.build_id;
  if (buildId === undefined) {
    if (!resolution.enabled) {
      return 'build_id must be an object';
    }
  } else {
    if (buildId === null || typeof buildId !== 'object') {
      return 'build_id must be an object';
    }
    if (!isSafeNonNegativeInt(buildId.offset)) {
      return 'build_id.offset must be a non-negative safe integer';
    }
    if (!isHexString(buildId.hex)) {
      return 'build_id.hex must be a non-empty even-length hex string';
    }
  }

  var functions = profile.functions;
  if (functions === null || typeof functions !== 'object') {
    return 'functions must be an object';
  }
  var names = ['l2cap', 'sdp'];
  for (var i = 0; i < names.length; i++) {
    var fn = functions[names[i]];
    if (fn === null || typeof fn !== 'object') {
      return 'functions.' + names[i] + ' must be an object';
    }
    if (fn.offset !== undefined) {
      if (!isSafeNonNegativeInt(fn.offset)) {
        return 'functions.' + names[i] + '.offset must be a non-negative safe integer';
      }
    } else if (!resolution.enabled) {
      return 'functions.' + names[i] + '.offset must be a non-negative safe integer';
    }
    if (!isHexString(fn.prologue)) {
      return 'functions.' + names[i] + '.prologue must be a non-empty even-length hex string';
    }
  }

  var offsets = profile.offsets;
  if (offsets === null || typeof offsets !== 'object') {
    return 'offsets must be an object';
  }
  var fields = [CCB_LCB, CCB_RCB, RCB_WIRE_PSM, RCB_PREFERRED_MODE, LCB_ADDRESS];
  for (var j = 0; j < fields.length; j++) {
    var value = offsets[fields[j]];
    if (!isSafeNonNegativeInt(value)) {
      return 'offsets.' + fields[j] + ' must be a non-negative safe integer';
    }
    if (value >= MAX_FIELD_OFFSET) {
      return 'offsets.' + fields[j] + ' must be < 65536';
    }
  }

  if (!isSafeNonNegativeInt(profile.aacp_psm) || profile.aacp_psm > 0xffff) {
    return 'aacp_psm must be a 16-bit non-negative integer';
  }
  if (!isSafeNonNegativeInt(profile.ertm_mode) || profile.ertm_mode > 0xff) {
    return 'ertm_mode must be a 8-bit non-negative integer';
  }
  return null;
}

function bytesEqualHex(bytes, hex) {
  if (bytes === null || bytes === undefined) {
    return false;
  }
  var actualBytes = new Uint8Array(bytes);
  var expectedLength = hex.length / 2;
  if (actualBytes.length !== expectedLength) return false;
  for (var i = 0; i < expectedLength; i++) {
    var expected = parseInt(hex.substr(i * 2, 2), 16);
    if (actualBytes[i] !== expected) {
      return false;
    }
  }
  return true;
}

function parseMac(mac) {
  if (typeof mac !== 'string') {
    return null;
  }
  if (!/^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$/.test(mac)) {
    return null;
  }
  var parts = mac.split(':');
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    out.push(parseInt(parts[i], 16));
  }
  return out;
}

function checkOptions(options) {
  if (options === null || typeof options !== 'object') {
    return 'options must be an object';
  }
  if (parseMac(options.target_address) === null) {
    return 'target_address must be six hex octets separated by colons';
  }
  if (options.spoof_vendor !== undefined && typeof options.spoof_vendor !== 'boolean') {
    return 'spoof_vendor must be a boolean';
  }
  return null;
}

function currentStatus() {
  return makeStatus(agent.state, {
    active_sdp: agent.activeSdp,
    restore_failures: agent.restoreFailures,
    last_error: agent.lastError,
    l2cap_matches: agent.stats.l2cap_matches,
    l2cap_rewrites: agent.stats.l2cap_rewrites,
    sdp_writes: agent.stats.sdp_writes,
    sdp_restores: agent.stats.sdp_restores,
    di_identity: agent.diIdentity !== false
  });
}

function safeSend(message) {
  try {
    send(message);
  } catch (e) {
    /* logging must never break cleanup */
  }
}

function probe(profile) {
  var reason = checkProfileShape(profile);
  if (reason !== null) {
    return fail(reason);
  }

  if (Process.arch !== 'arm64') {
    return fail('unsupported arch: ' + String(Process.arch));
  }
  if (Process.pointerSize !== 8) {
    return fail('unsupported pointerSize: ' + String(Process.pointerSize));
  }

  var mod;
  try {
    mod = Process.getModuleByName(profile.module_name);
  } catch (e) {
    return fail('module not found: ' + String(profile.module_name));
  }
  if (mod === null || mod === undefined) {
    return fail('module not found: ' + String(profile.module_name));
  }

  var base;
  var size;
  try {
    base = mod.base;
    size = mod.size;
  } catch (e) {
    return fail('module metadata unavailable');
  }
  if (base === null || base === undefined || typeof base.add !== 'function') {
    return fail('module base unavailable');
  }
  if (!isSafeNonNegativeInt(size) || size === 0) {
    return fail('module size invalid');
  }
  if (typeof mod.path !== 'string' || mod.path !== profile.module_path) {
    return fail('module_path mismatch');
  }

  var resolution = checkResolution(profile.resolution);
  var details = {
    module: profile.module_name,
    base: base.toString(),
    size: size,
    checks: {}
  };

  var checks = [];
  var exact = true;
  if (profile.build_id !== undefined) {
    checks.push({ label: 'build_id', offset: profile.build_id.offset, hex: profile.build_id.hex });
  } else {
    exact = false;
  }
  var functionNames = ['l2cap', 'sdp'];
  for (var n = 0; n < functionNames.length; n++) {
    var name = functionNames[n];
    if (profile.functions[name].offset !== undefined) {
      checks.push({ label: 'functions.' + name + '.prologue',
        offset: profile.functions[name].offset, hex: profile.functions[name].prologue });
    } else {
      exact = false;
    }
  }

  var mismatch = null;
  for (var i = 0; i < checks.length && exact; i++) {
    var c = checks[i];
    var length = c.hex.length / 2;
    if (c.offset + length > size) {
      mismatch = c.label + ' range outside module';
      exact = false;
      break;
    }
    var actual;
    try {
      actual = base.add(c.offset).readByteArray(length);
    } catch (e) {
      mismatch = c.label + ' read failed: ' + String(e);
      exact = false;
      break;
    }
    if (!bytesEqualHex(actual, c.hex)) {
      mismatch = c.label + ' mismatch';
      exact = false;
    } else {
      details.checks[c.label] = true;
    }
  }

  var offsets = {};
  if (exact) {
    for (var e = 0; e < functionNames.length; e++) {
      offsets[functionNames[e]] = profile.functions[functionNames[e]].offset;
    }
  } else {
    if (!resolution.enabled) {
      return fail(mismatch || 'profile does not match the loaded module');
    }
    for (var s = 0; s < functionNames.length; s++) {
      var scanName = functionNames[s];
      var scanned = scanPrologue(mod, profile.functions[scanName].prologue);
      if (scanned.error) {
        return fail('functions.' + scanName + ': ' + scanned.error);
      }
      var expectedLength = profile.functions[scanName].prologue.length / 2;
      var scannedBytes;
      try {
        scannedBytes = base.add(scanned.offset).readByteArray(expectedLength);
      } catch (scanError) {
        return fail('functions.' + scanName + ' read failed: ' + String(scanError));
      }
      if (!bytesEqualHex(scannedBytes, profile.functions[scanName].prologue)) {
        return fail('functions.' + scanName + ' resolved prologue mismatch');
      }
      offsets[scanName] = scanned.offset;
      details.checks['functions.' + scanName + '.prologue'] = true;
    }
  }

  agent.resolvedOffsets = offsets;
  details.resolution = exact ? 'exact' : resolution.mode;
  details.offsets = offsets;
  return { ok: true, module: details.module, base: details.base, size: details.size,
    checks: details.checks, resolution: details.resolution, offsets: offsets };
}

function onL2capEnter(args) {
  var self = this;
  self.matched = false;
  self.modeAddress = null;
  self.originalMode = 0;

  if (!agent.accepting) {
    return;
  }

  var ccb = args[0];
  if (ccb === null || ccb === undefined || typeof ccb.isNull !== 'function' || ccb.isNull()) {
    return;
  }

  var profile = agent.profile;
  if (profile === null) {
    return;
  }

  try {
    var lcb = ccb.add(profile.offsets[CCB_LCB]).readPointer();
    var rcb = ccb.add(profile.offsets[CCB_RCB]).readPointer();
    if (lcb === null || rcb === null || lcb.isNull() || rcb.isNull()) {
      return;
    }

    var psm = rcb.add(profile.offsets[RCB_WIRE_PSM]).readU16();
    if (psm !== profile.aacp_psm) {
      return;
    }

    var addressBuffer = lcb.add(profile.offsets[LCB_ADDRESS]).readByteArray(6);
    var target = agent.targetBytes;
    if (addressBuffer === null || target === null) {
      return;
    }
    var addressBytes = new Uint8Array(addressBuffer);
    for (var i = 0; i < 6; i++) {
      if (addressBytes[i] !== target[i]) {
        return;
      }
    }

    var modeAddress = rcb.add(profile.offsets[RCB_PREFERRED_MODE]);
    var mode = modeAddress.readU8();
    if (mode !== profile.ertm_mode) {
      return;
    }

    self.matched = true;
    self.modeAddress = modeAddress;
    self.originalMode = mode;
  } catch (e) {
    self.matched = false;
    self.modeAddress = null;
    noteError('l2cap enter: ' + String(e));
  }
}

function onL2capLeave(retval) {
  var self = this;
  if (!agent.accepting || !self.matched || self.modeAddress === null) {
    return;
  }
  agent.stats.l2cap_matches += 1;
  try {
    if (retval === null || retval === undefined || typeof retval.toInt32 !== 'function') {
      return;
    }
    if (retval.toInt32() !== 0) {
      return;
    }
    self.modeAddress.writeU8(self.originalMode);
    retval.replace(1);
    agent.stats.l2cap_rewrites += 1;
  } catch (e) {
    noteError('l2cap leave: ' + String(e));
  } finally {
    self.matched = false;
    self.modeAddress = null;
  }
}

function onSdpEnter(args) {
  this.entry = null;
  var record = args[0];
  if (!record || typeof record.isNull !== 'function' || record.isNull()) return;
  var pinned = false;
  try {
    var key = record.toString();
    var entry = agent.sdpRecords.get(key);
    // New records are untouched while draining; existing temporary records still
    // need an invocation reference so the final return restores exactly once.
    if (!entry && (!agent.accepting || !agent.spoofVendor)) return;
    Script.pin();
    pinned = true;
    if (!entry) {
      var pair = record.readU32();
      record.writeU32(0x0001004c);
      entry = { key: key, record: record, originalPair: pair, depth: 0 };
      agent.sdpRecords.set(key, entry);
      agent.stats.sdp_writes += 1;
    }
    entry.depth += 1;
    this.entry = entry;
    agent.activeSdp += 1;
    pinned = false; // ownership transferred to this invocation's onLeave
  } catch (e) {
    if (pinned) {
      try { Script.unpin(); } catch (e2) { noteError('sdp enter unpin: ' + String(e2)); }
    }
    noteError('sdp enter: ' + String(e));
  }
}

function onSdpLeave(retval) {
  var entry = this.entry;
  if (!entry) return;
  this.entry = null;
  try {
    entry.depth -= 1;
    if (entry.depth === 0) {
      try {
        entry.record.writeU32(entry.originalPair);
        agent.stats.sdp_restores += 1;
      } catch (e) {
        noteRestoreFailure('sdp restore: ' + String(e));
      } finally {
        agent.sdpRecords.delete(entry.key);
      }
    }
  } finally {
    agent.activeSdp -= 1;
    try {
      if (agent.activeSdp === 0 && agent.state === 'draining') finishDrain();
    } finally {
      try { Script.unpin(); } catch (e2) { noteError('sdp unpin: ' + String(e2)); }
    }
  }
}

function detachListeners() {
  var ok = true;
  if (agent.l2capListener !== null) {
    try {
      agent.l2capListener.detach();
      agent.l2capListener = null;
    } catch (e) {
      ok = false;
      noteError('l2cap detach: ' + String(e));
    }
  }
  if (agent.sdpListener !== null) {
    try {
      agent.sdpListener.detach();
      agent.sdpListener = null;
    } catch (e) {
      ok = false;
      noteError('sdp detach: ' + String(e));
    }
  }
  try {
    Interceptor.flush();
  } catch (e) {
    ok = false;
    noteError('flush: ' + String(e));
  }
  return ok && agent.l2capListener === null && agent.sdpListener === null;
}

function finishDrain() {
  if (agent.state !== 'draining') {
    return;
  }
  agent.accepting = false;
  if (!detachListeners()) return;
  agent.state = 'disabled';
  agent.profile = null;
  agent.resolvedOffsets = null;
  agent.targetBytes = null;
  agent.spoofVendor = false;
  agent.diIdentity = true;
  safeSend({ event: 'disabled', reason: 'drain-complete' });
}

function enable(profile, options) {
  if (agent.state !== 'disabled') {
    return makeStatus(agent.state, {
      active_sdp: agent.activeSdp,
      restore_failures: agent.restoreFailures,
      last_error: 'enable rejected: state is ' + agent.state
    });
  }

  if (options === null || typeof options !== 'object') {
    return makeStatus('disabled', { last_error: 'options must be an object' });
  }
  var optionsError = checkOptions(options);
  if (optionsError !== null) {
    return makeStatus('disabled', { last_error: optionsError });
  }

  var profileError = checkProfileShape(profile);
  if (profileError !== null) {
    return makeStatus('disabled', { last_error: profileError });
  }

  var probeResult;
  try {
    probeResult = probe(profile);
  } catch (e) {
    return makeStatus('disabled', { last_error: 'probe failed: ' + String(e) });
  }
  if (!probeResult.ok) {
    return makeStatus('disabled', { last_error: 'probe failed: ' + probeResult.reason });
  }

  var mod;
  var targetBytes = parseMac(options.target_address);
  if (targetBytes === null) {
    return makeStatus('disabled', { last_error: 'invalid target_address' });
  }
  try {
    mod = Process.getModuleByName(profile.module_name);
  } catch (e) {
    return makeStatus('disabled', { last_error: 'module not found' });
  }

  var l2capAddress;
  var sdpAddress;
  var resolved = agent.resolvedOffsets;
  if (resolved === null || resolved === undefined ||
      !isSafeNonNegativeInt(resolved.l2cap) || !isSafeNonNegativeInt(resolved.sdp)) {
    return makeStatus('disabled', { last_error: 'resolved function offsets unavailable' });
  }
  try {
    l2capAddress = mod.base.add(resolved.l2cap);
    sdpAddress = mod.base.add(resolved.sdp);
  } catch (e) {
    return makeStatus('disabled', { last_error: 'address computation failed' });
  }

  try {
    agent.profile = JSON.parse(JSON.stringify(profile));
  } catch (e) {
    return makeStatus('disabled', { last_error: 'profile snapshot failed: ' + String(e) });
  }
  agent.targetBytes = targetBytes;
  agent.diIdentity = probeResult.resolution === 'exact';
  agent.spoofVendor = options.spoof_vendor === true && agent.diIdentity;
  agent.activeSdp = 0;
  agent.sdpRecords = new Map();
  agent.restoreFailures = 0;
  agent.lastError = null;

  agent.accepting = false;

  var l2capListener = null;
  var sdpListener = null;
  try {
    l2capListener = Interceptor.attach(l2capAddress, {
      onEnter: onL2capEnter,
      onLeave: onL2capLeave
    });
    agent.l2capListener = l2capListener;

    if (options.spoof_vendor && agent.diIdentity) {
      sdpListener = Interceptor.attach(sdpAddress, {
        onEnter: onSdpEnter,
        onLeave: onSdpLeave
      });
      agent.sdpListener = sdpListener;
    }
    Interceptor.flush();
  } catch (e) {
    agent.accepting = false;
    agent.state = 'draining';
    noteError('install failed: ' + String(e));
    finishDrain();
    safeSend({ event: 'error', message: agent.lastError });
    return currentStatus();
  }

  agent.state = 'enabled';
  agent.accepting = true;
  safeSend({ event: 'enabled', spoof_vendor: agent.spoofVendor });
  return currentStatus();
}

function disable() {
  if (agent.state === 'disabled') {
    return currentStatus();
  }
  agent.accepting = false;
  if (agent.activeSdp > 0) {
    agent.state = 'draining';
    safeSend({ event: 'draining', active_sdp: agent.activeSdp });
    return currentStatus();
  }
  agent.state = 'draining';
  finishDrain();
  return currentStatus();
}

function status() {
  return currentStatus();
}

rpc.exports = {
  probe: probe,
  enable: enable,
  disable: disable,
  status: status
};
