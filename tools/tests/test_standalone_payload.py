import importlib.util
from pathlib import Path
import unittest
import io
import json
import hashlib
import zipfile

spec=importlib.util.spec_from_file_location('standalone_payload',Path(__file__).parents[1]/'standalone_payload.py')
payload=importlib.util.module_from_spec(spec)
spec.loader.exec_module(payload)


class RelocationTests(unittest.TestCase):
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
        files={'libksud.so':b'loader','diagnostic.ko':b'module','frida-inject':b'frida',
               'inject.template.sh':b'#!/system/bin/sh\nset -eu\n', 'agent.template.js':b'// agent'}
        original=dict(files)
        for name,value in (changes or {}).items():
            if name.startswith('assets/root/') and name!='assets/root/manifest.json':files[name.removeprefix('assets/root/')]=value
        manifest={'package':'lp.pods','files':{name:hashlib.sha256(data).hexdigest() for name,data in files.items()}}
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
                        {'assets/root/inject.template.sh':b'#!/system/bin/sh\r\n'},
                        {'assets/root/inject.template.sh':b'am start-foreground-service service\n'}]:
            archive,pins=self.archive(changes)
            with self.subTest(changes=list(changes)),self.assertRaises(ValueError):payload.verify_apk(archive,pins)


if __name__=='__main__': unittest.main()
