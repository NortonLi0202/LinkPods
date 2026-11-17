"""Host-only project checks and immutable-by-convention release snapshots. No ADB."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APPS = ('apps/android',)
SOURCE_EXTENSIONS = {'.java', '.xml', '.ps1', '.md', '.txt', '.jar', '.c', '.h', '.png'}


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else _digest(stream)


def _digest(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def record(path):
    return {'sha256': sha256(path), 'bytes': path.stat().st_size}


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def verify_manifest(path):
    path = Path(path)
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    errors = []
    files = data.get('files', {})
    if not files:
        return [f'{path}: empty file manifest']
    for name, expected in files.items():
        target = (path.parent / name).resolve()
        if not target.is_relative_to(path.parent.resolve()):
            errors.append(f'{path}: unsafe file path {name}')
        elif not target.is_file():
            errors.append(f'{path}: missing {name}')
        elif sha256(target) != expected['sha256'] or (expected.get('bytes') is not None and target.stat().st_size != expected['bytes']):
            errors.append(f'{path}: mismatch {name}')
    return errors


def snapshot(root, release_id, source_name, apk):
    root = Path(root).resolve()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', release_id):
        raise ValueError('release ID must be a simple unique directory name')
    if source_name not in APPS:
        raise ValueError('source must be the managed Android application path')
    source = root / source_name
    if source.is_symlink() or not source.resolve().is_relative_to(root):
        raise ValueError('unsafe source path')
    apk = Path(apk).resolve()
    if not apk.is_file():
        raise FileNotFoundError(apk)
    if not source.is_dir():
        raise FileNotFoundError(source)
    selected = []
    for file in sorted(source.rglob('*')):
        relative = file.relative_to(source)
        if 'build' in relative.parts or '__pycache__' in relative.parts:
            continue
        if file.is_symlink():
            raise ValueError(f'symlink in source: {relative}')
        if file.is_file() and (file.suffix.lower() in SOURCE_EXTENSIONS or file.name == 'LICENSE'):
            selected.append(file)
    if not selected:
        raise ValueError('empty source snapshot')
    releases = root / 'releases'
    releases.mkdir(exist_ok=True)
    destination = releases / release_id
    # mkdir is exclusive: never overwrite an existing successful or partial release.
    destination.mkdir()
    shutil.copyfile(apk, destination / 'app.apk')
    with zipfile.ZipFile(destination / 'source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in selected:
            archive.write(file, file.relative_to(source).as_posix())
    manifest = destination / 'manifest.json'
    write_json(manifest, {
        'schema': 1, 'release_id': release_id,
        'created_utc': datetime.now(timezone.utc).isoformat(),
        'source': source_name, 'original_apk': apk.relative_to(root).as_posix() if apk.is_relative_to(root) else apk.name,
        'acceptance': 'archive only; deployment and listening must be recorded separately',
        'source_files': {file.relative_to(source).as_posix(): record(file) for file in selected},
        'files': {name: record(destination / name) for name in ('app.apk', 'source.zip')}
    })
    return manifest


def run(args, cwd=ROOT):
    print('+ ' + ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, check=True)


def _linked(path):
    info=path.lstat()
    return path.is_symlink() or bool(getattr(info,'st_file_attributes',0)&getattr(stat,'FILE_ATTRIBUTE_REPARSE_POINT',0x400))


def _protected_candidate(path):
    if _linked(path) or path.suffix.lower() in {'.jks','.keystore','.pem','.key','.p12','.pfx'}:
        return True
    if path.is_dir():
        for directory,dirs,files in os.walk(path,followlinks=False):
            for name in dirs+files:
                child=Path(directory)/name
                if _linked(child) or child.suffix.lower() in {'.jks','.keystore','.pem','.key','.p12','.pfx'}:
                    return True
    return False


def clean(root,apply=False):
    """Only managed app build outputs; preview by default. Keep signing identities."""
    root=Path(root).resolve()
    plan=[]
    for app in APPS:
        build=root/app/'build'
        if not build.exists():continue
        ancestors=[root/app,build]
        if any(_linked(path) for path in ancestors):
            raise ValueError('refuse linked build directory: '+app)
        for candidate in sorted(build.iterdir()):
            target=candidate.resolve()
            if not target.is_relative_to(root) or target==root:
                if _linked(candidate):continue
                raise ValueError('cleanup target escaped project')
            if _protected_candidate(candidate):continue
            size=sum(p.stat().st_size for p in candidate.rglob('*') if p.is_file()) if candidate.is_dir() else candidate.stat().st_size
            item={'path':candidate.relative_to(root).as_posix(),'bytes':size}
            if apply:
                if candidate.is_dir():shutil.rmtree(candidate)
                else:candidate.unlink()
            plan.append(item)
    return plan


def check(root,require_inputs=False):
    config = json.loads((root / 'project.json').read_text(encoding='utf-8'))
    errors = []
    for name in (config['product']['source'], config['release_notes'], config['issues']):
        if not (root / name).exists():
            errors.append(f'missing status reference: {name}')
    for area in ('releases', 'third_party/sources'):
        for manifest in sorted((root / area).glob('**/manifest.json')):
            errors.extend(verify_manifest(manifest))
    inputs=root/'third_party/payloads'
    try:
        spec=importlib.util.spec_from_file_location('project_adapter_registry',Path(__file__).parent/'adapter_registry.py')
        registry=importlib.util.module_from_spec(spec);spec.loader.exec_module(registry)
        registry.load_registry(root)
    except (OSError,ValueError,KeyError) as error:
        errors.append('invalid bridge adapter registry: '+str(error))
    missing=[]
    for name,entry in json.loads((inputs/'manifest.json').read_text(encoding='utf-8-sig'))['inputs'].items():
        path=(inputs/entry['file']).resolve()
        if path.parent!=inputs.resolve():errors.append('unsafe payload input path: '+name)
        elif not path.is_file():missing.append(name)
        elif sha256(path)!=entry['sha256']:errors.append('payload input mismatch: '+name)
    for name,entry in json.loads((root/'third_party/dependencies.json').read_text(encoding='utf-8'))['dependencies'].items():
        path=(root/entry['path']).resolve()
        if not path.is_relative_to(root.resolve()) or path==root.resolve():errors.append('unsafe dependency input path: '+name)
        elif not path.is_file():missing.append(name)
        elif sha256(path)!=entry['sha256']:errors.append('dependency input mismatch: '+name)
    if require_inputs:
        errors.extend('missing local binary input: '+name for name in missing)
    elif missing:
        print('Source-only check; local binary inputs not supplied: '+', '.join(missing))
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode('utf-8').split('\0')
    forbidden_suffixes = {'.apk', '.zip', '.jar', '.jks', '.keystore', '.log', '.img', '.qcow2', '.pem', '.key', '.so', '.ko', '.exe'}
    excluded_roots = {'DFRoot', 'DFRoot-diag-worktree', 'KernelSU-source', 'KernelSU-diag-worktree',
                      'LibrePods-handoff-worktree', 'device-info', 'ready', 'firmware-tools'}
    # Values are never printed. A match is reviewed by filename only.
    secret_pattern = re.compile(rb'(?:-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{32,})')
    for name in filter(None, tracked):
        file = root / name
        if file.suffix.lower() in forbidden_suffixes or 'build' in Path(name).parts or Path(name).parts[0] in excluded_roots:
            errors.append(f'forbidden tracked artifact: {name}')
        if file.is_file() and file.stat().st_size > 2_000_000:
            errors.append(f'oversize tracked file: {name}')
        if file.is_file() and file.stat().st_size <= 2_000_000 and secret_pattern.search(file.read_bytes()):
            errors.append(f'possible credential; review locally: {name}')
    for error in errors:
        print('FAIL: ' + error)
    print(f'Checked {len(list(filter(None, tracked)))} managed files; {len(errors)} errors. No device operations.')
    return 1 if errors else 0


def test(root):
    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tools/tests', '-v'], root)
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
    javac = str(jdk / 'javac.exe') if jdk else shutil.which('javac')
    java = str(jdk / 'java.exe') if jdk else shutil.which('java')
    node = shutil.which('node')
    if not javac or not java or not node:
        raise RuntimeError('JDK (JAVA_HOME or PATH) and Node.js are required')
    source = root / APPS[0]
    package = source / 'src/local/airpods/handoffprobe'
    tests = ['RoutingTest', 'AudioLinkTest', 'PeerRoutingTest', 'DeviceConfigTest', 'RootAccessTest', 'BackendPolicyTest', 'RequestGateTest', 'RootProtocolTest', 'RootTargetTest', 'RootWorkQueueTest', 'EarbudCommandsTest', 'EarbudUiPolicyTest', 'AapPacketsTest', 'ConnectionPolicyTest', 'BridgeAdapterRegistryTest', 'BridgeRuntimeLeaseTest', 'AirPodsModelsTest', 'AirPodsInformationTest', 'MultiGenerationEarbudTest']
    with tempfile.TemporaryDirectory(prefix='handoff-host-tests-') as output:
        sources = [package / (name + '.java') for name in ('RoutingPackets', 'RoutingPolicy', 'AudioLinkPolicy', 'PeerRoutingPolicy', 'SmartRouting', 'DeviceConfig', 'RootAccess', 'BackendPolicy', 'RequestGate', 'RootProtocol', 'RootTarget', 'RootWorkQueue', 'EarbudCommands', 'EarbudSnapshot', 'EarbudUiPolicy', 'AirPodsModels', 'AirPodsInformation')]
        sources += [package / 'AapPackets.java']
        sources += [package / (name+'.java') for name in ('ConnectionPolicy','BridgeAdapterRegistry','BridgeRuntimeLease')]
        sources += [source / 'tests' / (name + '.java') for name in tests]
        run([javac, '-encoding', 'UTF-8', '-d', output, *sources], root)
        for name in tests:
            run([java, '-cp', output, 'local.airpods.handoffprobe.' + name], root)
    run([node, 'native/bluetooth/test-resident-config.cjs'], root)
    run([node, 'native/bluetooth/test-sdp-config.cjs'], root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('status', 'test'):
        commands.add_parser(name)
    checks=commands.add_parser('check')
    checks.add_argument('--with-local-inputs',action='store_true',help='also require all pinned binary build dependencies')
    cleanup=commands.add_parser('clean',help='preview reproducible app build outputs; signing files are protected')
    cleanup.add_argument('--apply',action='store_true',help='delete the previewed build outputs')
    archive = commands.add_parser('snapshot')
    archive.add_argument('release_id')
    archive.add_argument('--source', choices=APPS, required=True)
    archive.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'status':
        print((ROOT / 'project.json').read_text(encoding='utf-8'))
    elif args.command == 'check':
        return check(ROOT,require_inputs=args.with_local_inputs)
    elif args.command == 'test':
        test(ROOT)
    elif args.command == 'clean':
        plan=clean(ROOT,apply=args.apply)
        print(json.dumps({'mode':'applied' if args.apply else 'preview','bytes':sum(item['bytes'] for item in plan),'targets':plan},ensure_ascii=False,indent=2))
    else:
        print(snapshot(ROOT, args.release_id, args.source, args.apk))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        sys.exit(1)
