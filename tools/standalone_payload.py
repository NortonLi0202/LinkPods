"""Prepare pinned local standalone payload inputs; does not execute them."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
import importlib.util
import tempfile

_registry_spec=importlib.util.spec_from_file_location('adapter_registry',Path(__file__).with_name('adapter_registry.py'))
registry=importlib.util.module_from_spec(_registry_spec);_registry_spec.loader.exec_module(registry)
_catalog_spec=importlib.util.spec_from_file_location('payload_catalog',Path(__file__).with_name('payload_catalog.py'))
payload_catalog=importlib.util.module_from_spec(_catalog_spec);_catalog_spec.loader.exec_module(payload_catalog)

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


def prepare_bridge_assets(assets,root,binary,binary_sha256,agent_source):
    assets,root=Path(assets),Path(root)
    adapters=registry.load_registry(root)
    bridge=ROOT/'native/bluetooth'
    spec=importlib.util.spec_from_file_location('runtime_bundle_builder',bridge/'prepare-runtime-bundle.py')
    builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
    result=[]
    for adapter in adapters:
        base='adapters/'+adapter['id']+'/'
        destination=assets/base;destination.mkdir(parents=True)
        profile=json.loads((root/adapter['profile']).read_text(encoding='utf-8-sig'))
        (destination/'profile.json').write_bytes(android_text(json.dumps(profile,sort_keys=True,separators=(',',':'))))
        placeholder='FE:DC:BA:98:76:54'
        with tempfile.TemporaryDirectory(prefix='linkpods-template-') as temporary:
            template=Path(temporary)/'bundle'
            builder.build_bundle(root/adapter['profile'],placeholder,template,agent_source,binary,binary_sha256,
                                 instance_id='__LINKPODS_BRIDGE_INSTANCE__')
            for name,out in [('agent.js','agent.template.js'),('inject-bluetooth.sh','inject.template.sh')]:
                text=(template/name).read_text(encoding='utf-8').replace(placeholder,'__LINKPODS_TARGET_ADDRESS__')
                (destination/out).write_bytes(android_text(text))
        record={key:adapter[key] for key in ('id','module_path','build_id_offset','profile_sha256','match') if adapter.get(key) is not None}
        for key in ('sha256','build_id'):
            if adapter.get(key) is not None:record[key]=adapter[key]
        if adapter.get('match')=='prologue':record['reference_sha256']=profile.get('file_sha256')
        result.append(record)
        result[-1].update(profile=base+'profile.json',agent=base+'agent.template.js',launcher=base+'inject.template.sh')
    return result


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
    adapters=prepare_bridge_assets(output/'assets/root',ROOT,PAYLOADS/INPUTS['frida']['file'],PINS['frida'],
                                   ROOT/'third_party/sources/librepods/agent.js')
    shutil.copyfile(ROOT/'apps/android/native/DFRoot-LICENSE.txt',output/'assets/root/DFRoot-LICENSE.txt')
    targets=json.loads((ROOT/'compatibility/targets.json').read_text(encoding='utf-8'))['temporary_root_targets']
    if len(targets)!=1:raise ValueError('exactly one temporary Root target is required')
    target=targets[0]
    catalog=payload_catalog.load(ROOT/'compatibility/payloads.json')
    if len(catalog)!=1:raise ValueError('exactly one packaged Root payload is supported')
    if catalog[0]['target']!=target:raise ValueError('payload target must match temporary Root target')
    (output/'assets/root/payloads.json').write_bytes(
        android_text(json.dumps({'schema':1,'payloads':catalog},sort_keys=True,separators=(',',':'))))
    paths={a['module_path'] for a in adapters if a.get('sha256')==target['bluetooth_sha256']}
    if not paths:
        paths={a['module_path'] for a in adapters
               if a.get('match')=='prologue' and a.get('reference_sha256')==target['bluetooth_sha256']}
    if len(paths)!=1:raise ValueError('temporary Root target requires one exact module path')
    files={p.relative_to(output/'assets/root').as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
           for p in (output/'assets/root').rglob('*') if p.is_file()}
    (output/'assets/root/manifest.json').write_text(json.dumps({'schema':2,'package':'lp.pods','target':target,
        'temporary_root_module_path':paths.pop(),'adapters':adapters,'files':files},indent=2)+'\n',encoding='utf-8')
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
            registry.safe_path(name)
            with archive.open('assets/root/'+name) as stream:
                digest=hashlib.sha256()
                for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
            if digest.hexdigest()!=expected:raise ValueError('APK asset hash mismatch: '+name)
        for key,name in [('loader','libksud.so'),('module','diagnostic.ko'),('frida','frida-inject')]:
            if manifest['files'].get(name)!=pins[key]:raise ValueError('APK pinned binary mismatch: '+name)
        if manifest.get('schema')!=2:raise ValueError('unsupported Root payload manifest schema')
        catalog=json.loads(archive.read('assets/root/payloads.json'))
        if (catalog.get('schema')!=1 or not isinstance(catalog.get('payloads'),list) or len(catalog['payloads'])!=1
                or (manifest.get('target') is not None and catalog['payloads'][0].get('target')!=manifest.get('target'))):
            raise ValueError('APK payload catalog mismatch')
        catalog_artifacts=catalog['payloads'][0].get('artifacts')
        if (not isinstance(catalog_artifacts,dict) or catalog_artifacts.get('module') not in manifest['files']
                or catalog_artifacts.get('loader') not in manifest['files']):
            raise ValueError('APK payload artifact missing')
        adapters=manifest.get('adapters',[])
        ids=set();identities=set();prologue_paths=set()
        for adapter in adapters:
            identifier=adapter['id'];base='adapters/'+identifier+'/'
            registry.safe_path(base+'profile.json')
            if identifier in ids:raise ValueError('duplicate adapter id')
            ids.add(identifier)
            registry.safe_path(adapter['module_path'],absolute=True)
            match=adapter.get('match','exact')
            if match not in ('exact','prologue'):raise ValueError('invalid adapter match mode')
            for field,name in [('profile','profile.json'),('agent','agent.template.js'),('launcher','inject.template.sh')]:
                if adapter[field]!=base+name or adapter[field] not in manifest['files']:raise ValueError('invalid adapter asset reference')
            profile=json.loads(archive.read('assets/root/'+adapter['profile']))
            if match=='exact':
                identity=(adapter['module_path'],adapter['sha256'],adapter['build_id'])
                if identity in identities:raise ValueError('ambiguous adapter identity')
                identities.add(identity)
                if (profile.get('module_path'),profile.get('file_sha256'),profile.get('build_id',{}).get('hex'))!=identity or profile['build_id']['offset']!=adapter['build_id_offset'] or registry.profile_hash(profile)!=adapter['profile_sha256']:
                    raise ValueError('APK adapter profile identity mismatch')
            else:
                resolution=profile.get('resolution')
                if (not isinstance(resolution,dict) or resolution.get('schema')!=1 or resolution.get('mode')!='prologue_scan'
                        or profile.get('module_path')!=adapter['module_path'] or registry.profile_hash(profile)!=adapter['profile_sha256']):
                    raise ValueError('APK prologue adapter profile mismatch')
                if adapter['module_path'] in prologue_paths:raise ValueError('ambiguous prologue adapter')
                prologue_paths.add(adapter['module_path'])
            launcher=archive.read('assets/root/'+adapter['launcher'])
            if b'\r' in launcher:raise ValueError('Android shell template must use LF')
            if b'am start-foreground-service' in launcher:raise ValueError('Root helper must not start service after cancellation')
            for field in ('agent','launcher'):
                if adapter['profile_sha256'].encode() not in archive.read('assets/root/'+adapter[field]):raise ValueError('runtime profile hash mismatch')
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
