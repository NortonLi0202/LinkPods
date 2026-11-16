import hashlib
import importlib.util
import json
from pathlib import Path
SDP_LAYOUT=json.loads(Path(__file__).with_name('sdp_fixture.json').read_text())
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('bundle', Path(__file__).parents[2] / 'native/bluetooth/prepare-runtime-bundle.py')
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.profile = self.root / 'profile.json'
        self.profile.write_text(json.dumps({'sdp_identity': SDP_LAYOUT, 'file_sha256': 'a' * 64, 'module_path': '/apex/test/lib64/test.so',
                                          'build_id': {'hex': 'b' * 40}}))
        self.agent = self.root / 'agent.js'
        self.agent.write_text('// fixture agent')
        self.binary = self.root / 'frida-inject'
        self.binary.write_bytes(b'fixture binary')
        self.digest = hashlib.sha256(self.binary.read_bytes()).hexdigest()

    def build(self, address='AA:BB:CC:DD:EE:01', digest=None):
        return bundle.build_bundle(self.profile, address, self.root / 'output', self.agent, self.binary, digest or self.digest)

    def test_explicit_address_in_agent_and_launcher(self):
        manifest = self.build()
        self.assertTrue(manifest.is_file())
        script = (manifest.parent / 'inject-bluetooth.sh').read_text()
        self.assertIn('AA:BB:CC:DD:EE:01', script)
        self.assertIn('a' * 64, script)
        self.assertIn('updated_at_ms', script, 'keep the resident-session freshness guard')
        self.assertIn('already', script)
        self.assertNotIn('am start-foreground-service', script,
                         'product launchers must not start the retired probe application')
        self.assertIn('AA:BB:CC:DD:EE:01', (manifest.parent / 'agent.js').read_text())
        self.assertNotIn('rpc.exports.installresume', (manifest.parent / 'agent.js').read_text(),
                         'inactive single-device resume experiments must not ship in new bundles')

    def test_invalid_target_writes_nothing(self):
        for address in [None, '', 'bad', '00:00:00:00:00:00', '02:00:00:00:00:00', 'AA:BB:CC:DD:EE:01;id']:
            with self.subTest(address=address), self.assertRaises(ValueError): self.build(address)
        self.assertFalse((self.root / 'output').exists())

    def test_hash_mismatch_writes_nothing(self):
        with self.assertRaises(ValueError): self.build(digest='c' * 64)
        self.assertFalse((self.root / 'output').exists())

    def test_existing_output_refused(self):
        self.build()
        with self.assertRaises(FileExistsError): self.build()

    def test_generated_scripts_apply_each_profiles_sdp_layout(self):
        import shutil,subprocess
        node=shutil.which('node')
        if not node:self.skipTest('Node required')
        for index in range(2):
            profile=json.loads(self.profile.read_text())
            profile['module_name']='synthetic-%d.so'%index
            profile['sdp_identity']=dict(SDP_LAYOUT,records_offset=128+index*256,record_stride=128+index*64,
                                         attribute_stride=24+index*8)
            self.profile.write_text(json.dumps(profile))
            output=self.root/('generated-%d'%index)
            bundle.build_bundle(self.profile,'AA:BB:CC:DD:EE:01',output,self.agent,self.binary,self.digest)
            result=subprocess.run([node,str(bundle.HERE/'test-sdp-config.cjs'),str(output/'agent.js'),str(self.profile)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)

    def test_protected_runtime_output_refused(self):
        with self.assertRaises(ValueError):
            bundle.build_bundle(self.profile, 'AA:BB:CC:DD:EE:01', bundle.HERE / 'runtime-bundle', self.agent, self.binary, self.digest)

    def test_fresh_session_for_different_target_is_not_reused(self):
        import shutil, subprocess
        shell = Path('C:/Program Files/Git/bin/bash.exe')
        if not shell.is_file():
            found = shutil.which('bash')
            if not found: self.skipTest('bash required for Android shell policy test')
            shell = Path(found)
        configuration = {'target_address': 'AA:BB:CC:DD:EE:01', 'profile_sha256': 'a'*64, 'bundle_id': 'b'*64}
        guard = bundle.resident_guard(configuration)
        state = {'pid': 42, 'updated_at_ms': 99000, 'state': {'state': 'enabled'}, 'configuration': configuration}
        file = self.root/'status.json'
        def decision():
            file.write_text(json.dumps(state,separators=(',',':')))
            result = subprocess.run([str(shell), '-c', guard+'\nresident_reuse "$1" 42 100', 'test', file.as_posix()], capture_output=True)
            return result.returncode
        self.assertEqual(decision(),0, 'same fresh configuration may be reused')
        state['configuration'] = dict(configuration,target_address='AA:BB:CC:DD:EE:02')
        self.assertEqual(decision(),35, 'different target requires stopping existing bridge')
        state.pop('configuration')
        self.assertEqual(decision(),35, 'legacy unidentified resident cannot be reused')
        state['updated_at_ms']=1000
        self.assertEqual(decision(),1, 'stale resident is not an active reuse candidate')


if __name__ == '__main__': unittest.main()
