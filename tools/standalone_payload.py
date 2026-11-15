"""Prepare pinned local standalone payload inputs; does not execute them."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
import importlib.util
import tempfile

ROOT=Path(__file__).resolve().parents[1]
ORIGINAL_PATH=b'/data/user_de/0/df.root/bootstrap'
STANDALONE_PATH=b'/data/user_de/0/lp.pods/bootstrap'
PAYLOADS=ROOT/'third_party/payloads'
INPUTS=json.loads((PAYLOADS/'manifest.json').read_text(encoding='utf-8-sig'))['inputs']
PINS={name:record['sha256'] for name,record in INPUTS.items()}


def android_text(text):
    return text.replace('\r\n','\n').encode('utf-8')


def standalone_diagnostic(source):
    start='static void preclean_link('
    end='static int capture_finish('
    if source.count(start)!=1 or source.count(end)!=1 or source.count('static int start_late_load(')!=1:
        raise ValueError('unexpected diagnostic source structure')
    left=source.index(start);right=source.index(end)
    if right<=left:raise ValueError('unexpected diagnostic source order')
    return source[:left]+'''/* Standalone mode loads only the explicit candidate. Do not execute module
 * lifecycle scripts, prune user modules or restart any manager/application. */
static int start_late_load(void *opaque) {
    (void)opaque;
    return 0;
}

'''+source[right:]


def relocate(data):
    if len(ORIGINAL_PATH)!=len(STANDALONE_PATH) or data.count(ORIGINAL_PATH)!=8:
        raise ValueError('expected exactly eight fixed-width bootstrap paths')
    return data.replace(ORIGINAL_PATH,STANDALONE_PATH)


def target_matches(observed,expected):
    return all(key in expected and expected[key] is not None and expected[key]==observed.get(key)
               for key in ('model','android_sdk','firmware','kernel','bluetooth_sha256'))


def pinned(path,name):
    data=path.read_bytes()
    if hashlib.sha256(data).hexdigest()!=PINS[name]: raise ValueError('local payload hash mismatch: '+name)
    return data


def prepare(output):
    output=Path(output)
    if output.exists(): raise FileExistsError(output)
    original=pinned(PAYLOADS/INPUTS['original_apk']['file'],'original_apk')
    import io
    with zipfile.ZipFile(io.BytesIO(original)) as archive:
        exploit=relocate(archive.read('lib/arm64-v8a/libdfroot.so'))
    inputs={'libksud.so':pinned(PAYLOADS/INPUTS['loader']['file'],'loader'),
            'diagnostic.ko':pinned(PAYLOADS/INPUTS['module']['file'],'module'),
            'frida-inject':pinned(PAYLOADS/INPUTS['frida']['file'],'frida')}
    output.mkdir(parents=True)
    (output/'native').mkdir(); (output/'assets/root').mkdir(parents=True)
    diagnostic=ROOT/'third_party/sources/dfroot/diagnostic_android.c'
    (output/'standalone-diagnostic.c').write_bytes(android_text(standalone_diagnostic(diagnostic.read_text(encoding='utf-8-sig'))))
    (output/'native/libdfroot.so').write_bytes(exploit)
    for name,data in inputs.items(): (output/'assets/root'/name).write_bytes(data)
    bridge=ROOT/'native/bluetooth'
    sources=[ROOT/'third_party/sources/librepods/agent.js']
    profile=json.loads((bridge/'profile.json').read_text(encoding='utf-8-sig'))
    (output/'assets/root/profile.json').write_text(json.dumps(profile,separators=(',',':')),encoding='utf-8')
    spec=importlib.util.spec_from_file_location('runtime_bundle_builder',bridge/'prepare-runtime-bundle.py')
    builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
    placeholder='FE:DC:BA:98:76:54'
    with tempfile.TemporaryDirectory(prefix='linkpods-template-') as temporary:
        template=Path(temporary)/'bundle'
        builder.build_bundle(bridge/'profile.json',placeholder,template,sources[0],
                             PAYLOADS/INPUTS['frida']['file'],PINS['frida'])
        for name,destination in [('agent.js','agent.template.js'),('inject-bluetooth.sh','inject.template.sh')]:
            text=(template/name).read_text(encoding='utf-8').replace(placeholder,'__LINKPODS_TARGET_ADDRESS__')
            (output/'assets/root'/destination).write_bytes(android_text(text))
    shutil.copyfile(ROOT/'apps/android/native/DFRoot-LICENSE.txt',output/'assets/root/DFRoot-LICENSE.txt')
    target=json.loads((ROOT/'compatibility/targets.json').read_text(encoding='utf-8'))['temporary_root_targets'][0]
    if target['bluetooth_sha256']!=profile['file_sha256']:raise ValueError('target and Bluetooth profile mismatch')
    files={p.relative_to(output/'assets/root').as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
           for p in (output/'assets/root').iterdir() if p.is_file()}
    (output/'assets/root/manifest.json').write_text(json.dumps({'schema':1,'package':'lp.pods','target':target,'files':files},indent=2)+'\n',encoding='utf-8')
    (output/'provenance.json').write_text(json.dumps({'original_pins':PINS,'relocated_exploit_sha256':hashlib.sha256(exploit).hexdigest(),
                'relocation_count':8,'device_execution':False},indent=2)+'\n',encoding='utf-8')


def verify_apk(path,pins=None):
    pins=PINS if pins is None else pins
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            # ZipInfo.filename silently normalizes Windows separators; Android does not.
            if '\\' in entry.orig_filename:
                raise ValueError('noncanonical APK entry: '+entry.orig_filename)
        manifest=json.loads(archive.read('assets/root/manifest.json'))
        if manifest.get('package')!='lp.pods':raise ValueError('standalone payload package mismatch')
        for name,expected in manifest['files'].items():
            with archive.open('assets/root/'+name) as stream:
                digest=hashlib.sha256()
                for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
            if digest.hexdigest()!=expected:raise ValueError('APK asset hash mismatch: '+name)
        for key,name in [('loader','libksud.so'),('module','diagnostic.ko'),('frida','frida-inject')]:
            if manifest['files'].get(name)!=pins[key]:raise ValueError('APK pinned binary mismatch: '+name)
        launcher=archive.read('assets/root/inject.template.sh')
        if b'\r' in launcher:raise ValueError('Android shell template must use LF')
        if b'am start-foreground-service' in launcher:raise ValueError('Root helper must not start service after cancellation')
        exploit=archive.read('lib/arm64-v8a/libdfroot.so')
        if exploit.count(STANDALONE_PATH)!=8 or ORIGINAL_PATH in exploit:raise ValueError('APK bootstrap relocation mismatch')
        if b'late-load' in archive.read('lib/arm64-v8a/liblpbootstrap.so'):
            raise ValueError('standalone bootstrap unexpectedly includes userspace late-load')
        dex=archive.read('classes.dex')
        if b'RootBrokerMain' not in dex or b'StandaloneRootProvider' not in dex:raise ValueError('APK missing Root components')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    group=parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--output',type=Path);group.add_argument('--verify-apk',type=Path)
    args=parser.parse_args()
    if args.verify_apk:verify_apk(args.verify_apk);print('Standalone APK payload verification passed; device not executed.')
    else:prepare(args.output)
