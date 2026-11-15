"""Build an explicit, new runtime bundle on the host. Never deploy or inject."""
import argparse
from pathlib import Path
import hashlib
import json
import re
import shlex
import shutil
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def resident_guard(configuration):
    expected = shlex.quote('"configuration":' + json.dumps(configuration, separators=(',', ':')))
    return '''resident_reuse() {
    resident_status=$1
    resident_pid=$2
    resident_now=$3
    [ -r "$resident_status" ] || return 1
    grep -q '"pid":'"$resident_pid"',' "$resident_status" || return 1
    grep -q '"state":{"state":"enabled"' "$resident_status" || return 1
    resident_stamp=$(sed -n 's/.*"updated_at_ms":\\([0-9]*\\).*/\\1/p' "$resident_status")
    case "$resident_stamp" in ''|*[!0-9]*) return 1;; esac
    resident_age=$((resident_now - resident_stamp / 1000))
    [ "$resident_age" -ge 0 ] && [ "$resident_age" -le 15 ] || return 1
    grep -Fq __CONFIGURATION__ "$resident_status" || return 35
    return 0
}
'''.replace('__CONFIGURATION__', expected)


def build_bundle(profile_path, target_address, output, agent_source, binary, expected_binary_sha256):
    target = target_address.strip().upper() if isinstance(target_address, str) else ''
    if not re.fullmatch(r'(?:[0-9A-F]{2}:){5}[0-9A-F]{2}', target) or target in ('00:00:00:00:00:00', '02:00:00:00:00:00'):
        raise ValueError('an explicit, valid target Bluetooth address is required')
    output = Path(output).resolve()
    for protected in (HERE / 'runtime-bundle', ROOT / 'releases'):
        if output == protected.resolve() or output.is_relative_to(protected.resolve()):
            raise ValueError('cannot overwrite archived or existing runtime bundle paths')
    if output.exists():
        raise FileExistsError(output)
    profile = json.loads(Path(profile_path).read_text(encoding='utf-8-sig'))
    digest = profile.get('file_sha256', '')
    path = profile.get('module_path', '')
    if not re.fullmatch(r'[0-9a-f]{64}', digest) or not isinstance(path, str) or not re.fullmatch(r'/[A-Za-z0-9_./-]+', path):
        raise ValueError('profile requires an exact SHA-256 and absolute module path')
    payload = Path(binary).read_bytes()
    if hashlib.sha256(payload).hexdigest() != expected_binary_sha256:
        raise ValueError('Frida binary SHA-256 mismatch')
    source = Path(agent_source).read_text(encoding='utf-8-sig')
    for name in ('sdp-runtime.js', 'resident-runtime.js'):
        source += '\n' + (HERE / name).read_text(encoding='utf-8-sig')
    source += '\nconst runtimeProfile = ' + json.dumps(profile) + ';\n'
    source += 'const runtimeProbe = rpc.exports.probe(runtimeProfile);\n'
    source += 'if (!runtimeProbe.ok) throw new Error(JSON.stringify(runtimeProbe));\n'
    source += 'rpc.exports.enable(runtimeProfile,{target_address:' + json.dumps(target) + ',spoof_vendor:false});\n'
    source += 'rpc.exports.appleidentity();\n'
    configuration = {'target_address': target,
                     'profile_sha256': hashlib.sha256(json.dumps(profile, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
                     'bundle_id': hashlib.sha256(source.encode()).hexdigest()}
    source += 'const runtimeReady = rpc.exports.startresident(' + json.dumps(configuration, separators=(',', ':')) + ');\n'
    source += "console.log('HANDOFF_RUNTIME_READY ' + JSON.stringify(runtimeReady));\n"
    launcher = '''#!/system/bin/sh
set -eu
__RESIDENT_GUARD__
runtime=/data/adb/handoff
set -- $(/system/bin/pidof com.android.bluetooth)
[ "$#" -eq 1 ] || exit 31
bt_pid=$1
case "$bt_pid" in ''|*[!0-9]*) exit 32;; esac
name=$(/system/bin/tr '\\000' '\\n' < "/proc/$bt_pid/cmdline" | /system/bin/head -n 1)
[ "$name" = com.android.bluetooth ] || exit 33
library_hash=$(/system/bin/sha256sum "/proc/$bt_pid/root"__MODULE__)
library_hash=${library_hash%% *}
[ "$library_hash" = __HASH__ ] || exit 30
status=/data/misc/bluedroid/handoff-runtime.status
already=1
if resident_reuse "$status" "$bt_pid" "$(/system/bin/date +%s)"; then
    already=0
else
    resident_result=$?
    if [ "$resident_result" -eq 35 ]; then
        echo 'Existing resident has a different or unknown target/configuration; stop it before changing bundles.' >&2
        exit 35
    fi
fi
if [ "$already" -ne 0 ]; then
    "$runtime/frida-inject" -p "$bt_pid" -s "$runtime/agent.js" -e
fi
'''
    launcher = launcher.replace('__MODULE__', shlex.quote(path)).replace('__HASH__', shlex.quote(digest)).replace('__TARGET__', shlex.quote(target)).replace('__RESIDENT_GUARD__', resident_guard(configuration))
    output.mkdir(parents=True)
    (output / 'agent.js').write_bytes(source.replace('\r\n', '\n').encode())
    (output / 'inject-bluetooth.sh').write_bytes(launcher.encode())
    shutil.copyfile(binary, output / 'frida-inject')
    files = {p.name: {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'bytes': p.stat().st_size}
             for p in sorted(output.iterdir()) if p.is_file()}
    manifest = output / 'manifest.json'
    manifest.write_text(json.dumps(files, indent=2) + '\n', encoding='utf-8')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target-address', required=True)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--agent-source', type=Path, default=ROOT / 'third_party/sources/librepods/agent.js')
    args = parser.parse_args()
    entry = json.loads((ROOT / 'third_party/payloads/manifest.json').read_text(encoding='utf-8-sig'))['inputs']['frida']
    expected = entry['sha256']
    print(build_bundle(args.profile, args.target_address, args.output, args.agent_source,
                       ROOT / 'third_party/payloads' / entry['file'], expected))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
