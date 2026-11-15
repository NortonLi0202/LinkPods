import importlib.util
from pathlib import Path
import unittest
import io
import json
import hashlib
import zipfile
import tempfile

spec=importlib.util.spec_from_file_location('standalone_payload',Path(__file__).parents[1]/'standalone_payload.py')
payload=importlib.util.module_from_spec(spec)
spec.loader.exec_module(payload)


class RelocationTests(unittest.TestCase):
    def test_registry_packages_both_synthetic_profiles_without_private_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); (root/'compatibility').mkdir(); (root/'profiles').mkdir()
            entries=[]
            for index in range(2):
                profile={'module_path':'/apex/test/lib%d.so'%index,'file_sha256':str(index)*64,
                         'build_id':{'hex':'ab'*16,'offset':32}}
                path='profiles/%d.json'%index; (root/path).write_text(json.dumps(profile))
                entries.append({'id':'synthetic-%d'%index,'module_path':profile['module_path'],
                                'sha256':profile['file_sha256'],'build_id':'ab'*16,'profile':path})
            (root/'compatibility/adapters.json').write_text(json.dumps({'schema':1,'profiles':entries}))
            binary=root/'binary';binary.write_bytes(b'synthetic binary')
            agent=root/'agent.js';agent.write_text('// synthetic source')
            assets=root/'assets';assets.mkdir()
            adapters=payload.prepare_bridge_assets(assets,root,binary,hashlib.sha256(binary.read_bytes()).hexdigest(),agent)
            self.assertEqual(len(adapters),2)
            for adapter in adapters:
                script=(assets/adapter['agent']).read_text()
                self.assertIn(adapter['profile_sha256'],script)
                self.assertIn('__LINKPODS_TARGET_ADDRESS__',script)
                self.assertTrue((assets/adapter['profile']).is_file())

    def test_standalone_diagnostic_skips_manager_and_module_lifecycle(self):
        source='HEAD\nstatic void preclean_link(int fd) {delete_old_files();}\nstatic int start_late_load(void *opaque) { run_late_load_and_restart_manager(); }\nstatic int capture_finish(void *opaque) {return 0;}\nTAIL'
        result=payload.standalone_diagnostic(source)
        self.assertNotIn('run_late_load_and_restart_manager',result)
        self.assertNotIn('delete_old_files',result)
        self.assertIn('static int capture_finish',result)
        self.assertIn('return 0;',result)
        with self.assertRaises(ValueError):payload.standalone_diagnostic('unexpected upstream source')
    def test_android_shell_templates_have_only_lf(self):
        data=payload.android_text('set -eu\r\necho test\n')
        self.assertEqual(data,b'set -eu\necho test\n')
    def test_only_eight_bootstrap_paths_are_changed(self):
        original=b'ELF\x00'+b'|'.join([payload.ORIGINAL_PATH]*8)+b'\x00unchanged'
        result=payload.relocate(original)
        self.assertEqual(len(result),len(original))
        self.assertEqual(result.count(payload.STANDALONE_PATH),8)
        self.assertEqual(result.replace(payload.STANDALONE_PATH,payload.ORIGINAL_PATH),original)

    def test_unexpected_input_refused(self):
        for count in (0,1,7,9):
            with self.subTest(count=count),self.assertRaises(ValueError): payload.relocate(payload.ORIGINAL_PATH*count)

    def test_profile_requires_known_complete_system(self):
        known={'model':'SM-X810','android_sdk':36,'firmware':'X810ZCS6EZH3',
               'kernel':'5.15.189-android13-8-3251856','bluetooth_sha256':'a'*64}
        self.assertTrue(payload.target_matches(known,known))
        for key in known:
            changed=dict(known); changed.pop(key)
            self.assertFalse(payload.target_matches(changed,known),key)
        self.assertFalse(payload.target_matches(dict(known,kernel='other'),known))

    def archive(self,changes=None):
        profile={'module_path':'/apex/test/lib.so','file_sha256':'a'*64,'build_id':{'hex':'ab'*16,'offset':32}}
        profile_bytes=json.dumps(profile,sort_keys=True,separators=(',',':')).encode()
        profile_hash=hashlib.sha256(profile_bytes).hexdigest()
        base='adapters/synthetic/'
        files={'libksud.so':b'loader','diagnostic.ko':b'module','frida-inject':b'frida',
               base+'inject.template.sh':b'#!/system/bin/sh\n# '+profile_hash.encode()+b'\n',
               base+'agent.template.js':b'// '+profile_hash.encode(),base+'profile.json':profile_bytes}
        original=dict(files)
        for name,value in (changes or {}).items():
            if name.startswith('assets/root/') and name!='assets/root/manifest.json':files[name.removeprefix('assets/root/')]=value
        manifest={'schema':2,'package':'lp.pods','adapters':[{'id':'synthetic','module_path':profile['module_path'],
            'sha256':'a'*64,'build_id':'ab'*16,'build_id_offset':32,'profile_sha256':profile_hash,
            'profile':base+'profile.json','agent':base+'agent.template.js','launcher':base+'inject.template.sh'}],
            'files':{name:hashlib.sha256(data).hexdigest() for name,data in files.items()}}
        contents={'assets/root/'+name:data for name,data in files.items()}
        contents['assets/root/manifest.json']=json.dumps(manifest).encode()
        contents['lib/arm64-v8a/libdfroot.so']=payload.STANDALONE_PATH*8
        contents['lib/arm64-v8a/liblpbootstrap.so']=b'bootstrap'
        contents['classes.dex']=b'RootBrokerMain StandaloneRootProvider'
        contents.update(changes or {})
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as archive:
            for name,value in contents.items(): archive.writestr(name,value)
        data.seek(0)
        pins={key:hashlib.sha256(original[name]).hexdigest() for key,name in
              [('loader','libksud.so'),('module','diagnostic.ko'),('frida','frida-inject')]}
        return data,pins

    def test_verify_archive_pins_and_launcher(self):
        archive,pins=self.archive()
        payload.verify_apk(archive,pins)
        for changes in [{'assets/root/frida-inject':b'changed'},
                        {'assets/root/adapters/synthetic/inject.template.sh':b'#!/system/bin/sh\r\n'},
                        {'assets/root/adapters/synthetic/inject.template.sh':b'am start-foreground-service service\n'}]:
            archive,pins=self.archive(changes)
            with self.subTest(changes=list(changes)),self.assertRaises(ValueError):payload.verify_apk(archive,pins)

    def test_android_rejects_windows_zip_asset_separators(self):
        archive,pins=self.archive()
        # Both central and local names are malformed; Windows ZipInfo normalizes
        # filename, so inspecting that property alone misses the Android failure.
        malformed=io.BytesIO(archive.getvalue().replace(b'assets/root/',b'assets/root\\'))
        with self.assertRaisesRegex(ValueError,'noncanonical APK entry'):
            payload.verify_apk(malformed,pins)

    def test_registry_archive_rejects_ambiguous_identity_profile_and_asset_paths(self):
        for mutate in [
            lambda manifest: manifest['adapters'].append(dict(manifest['adapters'][0],id='other')),
            lambda manifest: manifest['adapters'][0].update(profile='../profile.json'),
            lambda manifest: manifest['adapters'][0].update(profile_sha256='c'*64),
            lambda manifest: manifest['adapters'][0].update(build_id='cd'*16),
            lambda manifest: manifest['files'].update({'../escape':'a'*64}),
        ]:
            original,pins=self.archive()
            with zipfile.ZipFile(original) as source:
                files={name:source.read(name) for name in source.namelist()}
            manifest=json.loads(files['assets/root/manifest.json']);mutate(manifest)
            files['assets/root/manifest.json']=json.dumps(manifest).encode()
            changed=io.BytesIO()
            with zipfile.ZipFile(changed,'w') as archive:
                for name,data in files.items():archive.writestr(name,data)
            changed.seek(0)
            with self.assertRaises((ValueError,KeyError)):payload.verify_apk(changed,pins)


if __name__=='__main__': unittest.main()
